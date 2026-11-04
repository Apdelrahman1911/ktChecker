/*
 * ktchecker owned-child supervisor, preview-1. See README.md for the wire and
 * trust boundary. The actual fork child is the session/group leader. This
 * single-threaded process is its ONLY reaper and retains it (including as a
 * zombie) until the LAST group signal has completed. There is no PID lookup,
 * rediscovery, check-is-alive cleanup, or post-reap signaling fallback.
 */
#define _POSIX_C_SOURCE 200809L
#define _DARWIN_C_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#ifdef __linux__
#include <sys/prctl.h>
#elif !defined(__APPLE__)
#error unsupported_owned_child_supervisor_platform
#endif

enum {
    COMPLETED = 0, CANCELLED = 1, TIMEOUT = 2, OUTPUT_LIMIT = 3,
    PARENT_LOST = 4, IO_FAILURE = 5, EXEC_FAILURE = 6, SETUP_FAILURE = 7,
    SHUTDOWN_UNVERIFIED = 8, PROTOCOL_FAILURE = 9, OWNERSHIP_LOST = 10
};
enum {
    F_EXIT = 1, F_REAPED = 2, F_QUIESCENT = 4, F_GROUP = 8,
    F_EXEC = 16, F_CANCELLED = 32, F_TIMEOUT = 64, F_OUTPUT = 128,
    F_PARENT = 256, F_IO = 512, F_EXEC_FAILED = 1024,
    F_PROTOCOL = 2048, F_OWNERSHIP = 4096, F_SHUTDOWN = 8192
};
enum { FRAME_MAX = 8192, TERM_GRACE_MS = 250, CLEANUP_MS = 2000, FLUSH_MS = 500 };

struct supervisor {
    pid_t child;
    bool owned, group, granted, ready_seen, exec_eof, exec_failed, leader_normal;
    bool signals_finished, reaping_started, stopping, killed, terminal;
    bool wire_broken, control_closed, control_cancelled, wait_failed;
    int child_exit, out, err, ready, gate;
    uint32_t cause, flags;
    uint64_t start, timeout, output_limit, stop_at, kill_at, deadline;
    uint64_t observed, captured;
    unsigned char pending[FRAME_MAX + 5];
    size_t pending_size, pending_offset;
    unsigned char ready_bytes[8];
    size_t ready_size;
};

static volatile sig_atomic_t interrupted;

static void on_signal(int value) { (void)value; interrupted = 1; }

static uint64_t now_ms(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return UINT64_MAX;
    return (uint64_t)value.tv_sec * 1000u + (uint64_t)value.tv_nsec / 1000000u;
}

static void close_fd(int *fd) {
    if (*fd >= 0) { (void)close(*fd); *fd = -1; }
}

static bool set_fd_flags(int fd, bool nonblocking) {
    int flags = fcntl(fd, F_GETFD);
    if (flags < 0 || fcntl(fd, F_SETFD, flags | FD_CLOEXEC) < 0) return false;
    if (!nonblocking) return true;
    flags = fcntl(fd, F_GETFL);
    return flags >= 0 && fcntl(fd, F_SETFL, flags | O_NONBLOCK) >= 0;
}

static bool make_pipe(int pair[2]) {
    if (pipe(pair) != 0) return false;
    if (set_fd_flags(pair[0], false) && set_fd_flags(pair[1], false)) return true;
    close_fd(&pair[0]); close_fd(&pair[1]); return false;
}

static void put32(unsigned char *bytes, uint32_t value) {
    for (int i = 3; i >= 0; --i) { bytes[i] = (unsigned char)value; value >>= 8; }
}

static void put64(unsigned char *bytes, uint64_t value) {
    for (int i = 7; i >= 0; --i) { bytes[i] = (unsigned char)value; value >>= 8; }
}

static void note(struct supervisor *s, uint32_t cause, uint32_t flags) {
    if (s->cause == COMPLETED && cause != COMPLETED) s->cause = cause;
    s->flags |= flags;
}

static bool parse_bound(const char *text, uint64_t limit, uint64_t *result) {
    uint64_t value = 0;
    if (*text == '\0') return false;
    for (const unsigned char *p = (const unsigned char *)text; *p; ++p) {
        if (*p < '0' || *p > '9' || value > (limit - (*p - '0')) / 10u) return false;
        value = value * 10u + (*p - '0');
    }
    if (value == 0 || value > limit) return false;
    *result = value; return true;
}

static int exit_from_wait(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/* Observation does NOT establish ownership by finding a PID. Ownership begins
 * only at fork(), SIGCHLD is explicitly non-auto-reaping, and no waitpid occurs
 * before signals_finished. WNOWAIT deliberately preserves the kernel PID hold.
 * An unexpected wait error revokes ALL signaling rather than guessing a group.
 */
static bool observe_leader(struct supervisor *s) {
    if (!s->owned || s->reaping_started) return false;
    siginfo_t info;
    memset(&info, 0, sizeof(info));
    int result;
    do { result = waitid(P_PID, (id_t)s->child, &info, WEXITED | WNOHANG | WNOWAIT); }
    while (result < 0 && errno == EINTR);
    if (result < 0 || (info.si_pid != 0 && info.si_pid != s->child)) {
        s->owned = false; s->wait_failed = true;
        note(s, OWNERSHIP_LOST, F_OWNERSHIP | F_SHUTDOWN); return false;
    }
    if (info.si_pid == s->child) {
        int code = -1;
        if (info.si_code == CLD_EXITED) code = info.si_status;
        else if (info.si_code == CLD_KILLED || info.si_code == CLD_DUMPED) code = 128 + info.si_status;
        if (code < 0 || code > 255 || ((s->flags & F_EXIT) && code != s->child_exit)) {
            s->owned = false; s->wait_failed = true;
            note(s, OWNERSHIP_LOST, F_OWNERSHIP | F_SHUTDOWN); return false;
        }
        s->flags |= F_EXIT; s->child_exit = code; s->leader_normal = info.si_code == CLD_EXITED;
    }
    return true;
}

/* This is the ONLY real signaling site. It cannot be used after any reap.
 * A group is selected only after the fork child's private setsid handshake.
 * Without that handshake the exec gate is closed: the one held child cannot
 * have exec'd a provider or created provider descendants.
 */
static void signal_owned(struct supervisor *s, int number) {
    if (s->signals_finished || s->reaping_started || !s->owned) return;
    if (!observe_leader(s)) return;
    if (!s->group && s->granted) {
        s->owned = false;
        note(s, OWNERSHIP_LOST, F_OWNERSHIP | F_SHUTDOWN); return;
    }
    pid_t destination = s->group ? -s->child : s->child;
    if (kill(destination, number) != 0 && errno != ESRCH) note(s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN);
}

static void queue_frame(struct supervisor *s, unsigned char tag, const unsigned char *bytes, size_t size) {
    if (s->pending_size != 0 || size > FRAME_MAX) {
        note(s, PROTOCOL_FAILURE, F_PROTOCOL); s->wire_broken = true; return;
    }
    s->pending[0] = tag; put32(s->pending + 1, (uint32_t)size);
    memcpy(s->pending + 5, bytes, size);
    s->pending_size = size + 5; s->pending_offset = 0;
}

static void pump_wire(struct supervisor *s) {
    if (s->wire_broken || s->pending_size == 0) return;
    ssize_t count = write(STDOUT_FILENO, s->pending + s->pending_offset,
                          s->pending_size - s->pending_offset);
    if (count > 0) {
        s->pending_offset += (size_t)count;
        if (s->pending_offset == s->pending_size) s->pending_offset = s->pending_size = 0;
    } else if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        s->wire_broken = true;
        note(s, PARENT_LOST, F_PARENT | F_IO);
    }
}

static void read_control(struct supervisor *s) {
    if (s->control_closed) return;
    unsigned char bytes[64];
    ssize_t count = read(STDIN_FILENO, bytes, sizeof(bytes));
    if (count == 0) {
        s->control_closed = true;
        /* The one-shot JVM cancellation control is C followed by EOF. That is
         * an acknowledged request, not an unsolicited parent disappearance. */
        if (!s->control_cancelled) note(s, PARENT_LOST, F_PARENT);
    } else if (count > 0) {
        for (ssize_t i = 0; i < count; ++i) {
            if (bytes[i] == 'C') { s->control_cancelled = true; note(s, CANCELLED, F_CANCELLED); }
            else note(s, PROTOCOL_FAILURE, F_PROTOCOL);
        }
    } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        s->control_closed = true; note(s, IO_FAILURE, F_IO);
    }
}

static void fail_child(int fd, unsigned char marker) {
    unsigned char record[5]; record[0] = marker; put32(record + 1, (uint32_t)errno);
    size_t offset = 0;
    while (offset < sizeof(record)) {
        ssize_t count = write(fd, record + offset, sizeof(record) - offset);
        if (count > 0) offset += (size_t)count;
        else if (count < 0 && errno == EINTR) continue;
        else break;
    }
    _exit(126);
}

static void child_exec(int out[2], int err[2], int ready[2], int gate[2], char **command) {
    close_fd(&out[0]); close_fd(&err[0]); close_fd(&ready[0]); close_fd(&gate[1]);
    struct sigaction action;
    memset(&action, 0, sizeof(action)); action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    const int signals[] = { SIGCHLD, SIGPIPE, SIGTERM, SIGINT, SIGHUP };
    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i) {
        if (sigaction(signals[i], &action, NULL) != 0) fail_child(ready[1], 'S');
    }
    sigset_t empty; sigemptyset(&empty);
    if (sigprocmask(SIG_SETMASK, &empty, NULL) != 0) fail_child(ready[1], 'S');
    int null_input = open("/dev/null", O_RDONLY);
    if (null_input < 0 || dup2(null_input, STDIN_FILENO) < 0 ||
        dup2(out[1], STDOUT_FILENO) < 0 || dup2(err[1], STDERR_FILENO) < 0) fail_child(ready[1], 'S');
    close_fd(&null_input); close_fd(&out[1]); close_fd(&err[1]);
    if (setsid() != getpid()) fail_child(ready[1], 'S');
    unsigned char value = 'R'; ssize_t count;
    do { count = write(ready[1], &value, 1); } while (count < 0 && errno == EINTR);
    if (count != 1) { errno = EIO; fail_child(ready[1], 'S'); }
    do { count = read(gate[0], &value, 1); } while (count < 0 && errno == EINTR);
    if (count != 1 || value != 'G') { errno = EIO; fail_child(ready[1], 'S'); }
    close_fd(&gate[0]);
    execv(command[0], command); /* exact owner-selected argv, never a shell */
    fail_child(ready[1], 'E');
}

static void read_ready(struct supervisor *s) {
    if (s->ready < 0) return;
    unsigned char bytes[8];
    ssize_t count = read(s->ready, bytes, sizeof(bytes));
    if (count > 0) {
        if (s->ready_size + (size_t)count > sizeof(s->ready_bytes)) {
            note(s, PROTOCOL_FAILURE, F_PROTOCOL); close_fd(&s->ready); return;
        }
        memcpy(s->ready_bytes + s->ready_size, bytes, (size_t)count); s->ready_size += (size_t)count;
        if (s->ready_bytes[0] == 'R') {
            if (!s->ready_seen) { s->ready_seen = true; s->group = true; s->flags |= F_GROUP; }
            if (s->ready_size > 1 && s->ready_bytes[1] != 'E' && s->ready_bytes[1] != 'S') {
                note(s, PROTOCOL_FAILURE, F_PROTOCOL);
            }
            if (s->ready_size > 6) note(s, PROTOCOL_FAILURE, F_PROTOCOL);
            if (s->ready_size == 6 && s->ready_bytes[1] == 'E') {
                s->exec_failed = true; note(s, EXEC_FAILURE, F_EXEC_FAILED);
            } else if (s->ready_size == 6 && s->ready_bytes[1] == 'S') {
                s->exec_failed = true; note(s, SETUP_FAILURE, 0);
            }
        } else if (s->ready_bytes[0] == 'S') {
            if (s->ready_size > 5) note(s, PROTOCOL_FAILURE, F_PROTOCOL);
            if (s->ready_size == 5) { s->exec_failed = true; note(s, SETUP_FAILURE, 0); }
        } else note(s, PROTOCOL_FAILURE, F_PROTOCOL);
    } else if (count == 0) {
        s->exec_eof = true; close_fd(&s->ready);
        bool valid = (s->ready_size == 1 && s->ready_bytes[0] == 'R') ||
            (s->ready_size == 6 && s->ready_bytes[0] == 'R' &&
                (s->ready_bytes[1] == 'E' || s->ready_bytes[1] == 'S')) ||
            (s->ready_size == 5 && s->ready_bytes[0] == 'S');
        if (!valid && !s->stopping) note(s, SETUP_FAILURE, 0);
        if (!(valid && s->granted && !s->exec_failed && s->ready_size == 1) &&
            !s->stopping && !s->exec_failed) note(s, SETUP_FAILURE, 0);
    } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        note(s, IO_FAILURE, F_IO); close_fd(&s->ready);
    }
}

static void grant_exec(struct supervisor *s) {
    if (!s->ready_seen || s->granted || s->stopping || s->cause != COMPLETED || s->gate < 0) return;
    unsigned char value = 'G';
    ssize_t count = write(s->gate, &value, 1);
    if (count == 1) { s->granted = true; close_fd(&s->gate); }
    else if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        note(s, SETUP_FAILURE, 0); close_fd(&s->gate);
    }
}

static void read_output(struct supervisor *s, int *fd, unsigned char tag) {
    if (*fd < 0 || (s->pending_size != 0 && !s->wire_broken && !(s->flags & F_OUTPUT))) return;
    unsigned char bytes[FRAME_MAX];
    ssize_t count = read(*fd, bytes, sizeof(bytes));
    if (count > 0) {
        if (s->observed > (uint64_t)INT64_MAX - (uint64_t)count) {
            note(s, IO_FAILURE, F_IO); close_fd(fd); return;
        }
        s->observed += (uint64_t)count;
        size_t retained = (size_t)count;
        uint64_t available = s->output_limit - s->captured;
        if ((uint64_t)retained > available) retained = (size_t)available;
        if (retained > 0 && !s->wire_broken) {
            queue_frame(s, tag, bytes, retained); s->captured += retained;
        }
        if ((size_t)count > retained) note(s, OUTPUT_LIMIT, F_OUTPUT);
    } else if (count == 0) close_fd(fd);
    else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        close_fd(fd); note(s, IO_FAILURE, F_IO);
    }
}

static void begin_stop(struct supervisor *s, uint64_t now) {
    if (s->stopping) return;
    s->stopping = true; s->stop_at = now; s->deadline = now + CLEANUP_MS;
    s->kill_at = now + (s->cause == COMPLETED ? 0u : TERM_GRACE_MS);
    signal_owned(s, SIGTERM);
}

static void finish_signals(struct supervisor *s) {
    if (s->signals_finished) return;
    close_fd(&s->gate); /* an ungranted child can never proceed to exec */
    signal_owned(s, SIGKILL);
    s->signals_finished = true; /* irreversible, BEFORE any waitpid */
    s->killed = true;
}

static void reaped(struct supervisor *s, pid_t pid, int status) {
    if (pid != s->child) return;
    int code = exit_from_wait(status);
    if (code < 0 || code > 255 || ((s->flags & F_EXIT) && code != s->child_exit)) {
        note(s, OWNERSHIP_LOST, F_OWNERSHIP | F_SHUTDOWN); s->wait_failed = true;
    } else { s->child_exit = code; s->flags |= F_EXIT; s->leader_normal = WIFEXITED(status); }
    s->flags |= F_REAPED; s->owned = false;
}

static void reap_after_last_signal(struct supervisor *s) {
    if (!s->signals_finished) return;
    s->reaping_started = true;
#ifdef __linux__
    /* Subreaping keeps orphaned descendants in our actual waitable child set.
     * ECHILD follows synchronous reparenting on exit; no /proc PID census is
     * substituted for this kernel ownership/quiescence fact.
     */
    for (int i = 0; i < 64; ++i) {
        int status = 0; pid_t pid = waitpid(-1, &status, WNOHANG);
        if (pid > 0) { reaped(s, pid, status); continue; }
        if (pid == 0) return;
        if (errno == EINTR) continue;
        if (errno == ECHILD && (s->flags & F_REAPED) && !s->wait_failed) s->flags |= F_QUIESCENT;
        else { note(s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN); s->wait_failed = true; }
        return;
    }
#else
    if (!(s->flags & F_REAPED)) {
        int status = 0; pid_t pid = waitpid(s->child, &status, WNOHANG);
        if (pid > 0) reaped(s, pid, status);
        else if (pid < 0 && errno != EINTR) { note(s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN); s->wait_failed = true; }
    }
    if (!(s->flags & F_REAPED) || s->wait_failed) return;
    if (!s->group && !s->granted) { s->flags |= F_QUIESCENT; return; }
    /* Darwin: READ-ONLY observation after the LAST signal and leader reap.
     * This number may now be reused. Reuse can only make this proof fail; it
     * MUST NEVER cause another signal. signal_owned cannot run in this phase.
     */
    if (kill(-s->child, 0) != 0) {
        if (errno == ESRCH) s->flags |= F_QUIESCENT;
        else { note(s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN); s->wait_failed = true; }
    }
#endif
}

static bool configure_signals(void) {
    struct sigaction action; memset(&action, 0, sizeof(action)); sigemptyset(&action.sa_mask);
    action.sa_handler = SIG_DFL; /* no inherited SIG_IGN or SA_NOCLDWAIT */
    if (sigaction(SIGCHLD, &action, NULL) != 0) return false;
    action.sa_handler = SIG_IGN;
    if (sigaction(SIGPIPE, &action, NULL) != 0) return false;
    action.sa_handler = on_signal;
    if (sigaction(SIGTERM, &action, NULL) != 0 || sigaction(SIGINT, &action, NULL) != 0 ||
        sigaction(SIGHUP, &action, NULL) != 0) return false;
    sigset_t empty; sigemptyset(&empty);
    return sigprocmask(SIG_SETMASK, &empty, NULL) == 0;
}

static bool launch(struct supervisor *s, char **command) {
    int out[2] = {-1, -1}, err[2] = {-1, -1}, ready[2] = {-1, -1}, gate[2] = {-1, -1};
    bool prepared = make_pipe(out) && make_pipe(err) && make_pipe(ready) && make_pipe(gate);
    if (prepared) prepared = set_fd_flags(out[0], true) && set_fd_flags(err[0], true) &&
        set_fd_flags(ready[0], true) && set_fd_flags(gate[1], true);
    if (prepared) {
        s->child = fork();
        if (s->child == 0) child_exec(out, err, ready, gate, command);
        if (s->child > 0) {
            s->owned = true;
            close_fd(&out[1]); close_fd(&err[1]); close_fd(&ready[1]); close_fd(&gate[0]);
            s->out = out[0]; s->err = err[0]; s->ready = ready[0]; s->gate = gate[1];
            return true;
        }
    }
    for (int i = 0; i < 2; ++i) { close_fd(&out[i]); close_fd(&err[i]); close_fd(&ready[i]); close_fd(&gate[i]); }
    note(s, SETUP_FAILURE, 0); return false;
}

static void poll_once(struct supervisor *s, int delay) {
    struct pollfd fds[5]; nfds_t count = 0;
    if (!s->control_closed) fds[count++] = (struct pollfd){ STDIN_FILENO, POLLIN, 0 };
    if (!s->wire_broken && s->pending_size) fds[count++] = (struct pollfd){ STDOUT_FILENO, POLLOUT, 0 };
    if (s->ready >= 0) fds[count++] = (struct pollfd){ s->ready, POLLIN, 0 };
    if (s->pending_size == 0 || s->wire_broken || (s->flags & F_OUTPUT)) {
        if (s->out >= 0) fds[count++] = (struct pollfd){ s->out, POLLIN, 0 };
        if (s->err >= 0) fds[count++] = (struct pollfd){ s->err, POLLIN, 0 };
    }
    if (poll(fds, count, delay) < 0 && errno != EINTR) note(s, IO_FAILURE, F_IO);
}

static int emit_terminal(struct supervisor *s) {
    unsigned char record[32]; memset(record, 0, sizeof(record));
    put32(record, s->cause); put32(record + 4, s->flags);
    put32(record + 8, (uint32_t)s->child_exit);
    put64(record + 16, s->observed); put64(record + 24, s->captured);
    uint64_t start = now_ms();
    if (start == UINT64_MAX) return 74;
    bool queued = false;
    for (int iterations = 0; iterations < 1024; ++iterations) {
        if (s->wire_broken) return 74;
        pump_wire(s);
        if (!s->pending_size) {
            if (queued) return 0;
            queue_frame(s, 3, record, sizeof(record)); queued = true;
        }
        uint64_t now = now_ms();
        if (now == UINT64_MAX || now - start >= FLUSH_MS) return 74;
        struct pollfd descriptor = { STDOUT_FILENO, POLLOUT, 0 };
        if (poll(&descriptor, 1, 5) < 0 && errno != EINTR) return 74;
    }
    return 74;
}

int main(int argc, char **argv) {
    struct supervisor s; memset(&s, 0, sizeof(s));
    s.child_exit = -1; s.out = s.err = s.ready = s.gate = -1;
    if (argc < 6 || argc > 4101 || strcmp(argv[1], "--ktc-owned-child-v1") != 0 || strcmp(argv[4], "--") != 0 ||
        argv[5][0] != '/' || !parse_bound(argv[2], 3600000, &s.timeout) || !parse_bound(argv[3], 16777216, &s.output_limit)) return 64;
    size_t argv_bytes = 0;
    for (int i = 5; i < argc; ++i) {
        size_t size = strlen(argv[i]);
        if (size > 65536 || argv_bytes > 1048576 - size - 1) return 64;
        argv_bytes += size + 1;
    }
    s.start = now_ms();
    if (s.start == UINT64_MAX || !configure_signals() || !set_fd_flags(STDIN_FILENO, true) ||
        !set_fd_flags(STDOUT_FILENO, true)) return 70;
    memcpy(s.pending, "KTCOWN1\n", 8); s.pending_size = 8;
#ifdef __linux__
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0) {
        note(&s, SETUP_FAILURE, 0); s.flags |= F_QUIESCENT; return emit_terminal(&s);
    }
#endif
    read_control(&s);
    if (interrupted) note(&s, CANCELLED, F_CANCELLED);
    if (s.cause != COMPLETED || !launch(&s, argv + 5)) {
        s.flags |= F_QUIESCENT; return emit_terminal(&s);
    }
    bool alternate = false;
    for (;;) {
        uint64_t now = now_ms();
        if (now == UINT64_MAX) {
            note(&s, IO_FAILURE, F_IO);
            now = s.stopping ? s.deadline : s.start + s.timeout;
        }
        read_control(&s);
        read_ready(&s);
        if (interrupted) note(&s, CANCELLED, F_CANCELLED);
        if (!s.stopping && now - s.start >= s.timeout) note(&s, TIMEOUT, F_TIMEOUT);
        if (!s.signals_finished) (void)observe_leader(&s);
        if (!s.stopping && (s.cause != COMPLETED || (s.flags & F_EXIT))) begin_stop(&s, now);
        grant_exec(&s);
        pump_wire(&s);
        if (alternate) { read_output(&s, &s.err, 2); read_output(&s, &s.out, 1); }
        else { read_output(&s, &s.out, 1); read_output(&s, &s.err, 2); }
        alternate = !alternate;
        if (!s.stopping && s.cause != COMPLETED) begin_stop(&s, now);
        if (s.stopping && now >= s.kill_at) finish_signals(&s);
        if (s.signals_finished) reap_after_last_signal(&s);
        if (s.stopping && (s.flags & F_QUIESCENT) && s.out < 0 && s.err < 0 && s.ready < 0) break;
        if (s.stopping && now >= s.deadline) {
            if (!(s.flags & F_QUIESCENT)) note(&s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN);
            if (s.out >= 0 || s.err >= 0 || s.ready >= 0) note(&s, IO_FAILURE, F_IO);
            break;
        }
        poll_once(&s, 10);
    }
    /* Nothing after this point signals any PID/group, including on I/O errors. */
    close_fd(&s.out); close_fd(&s.err); close_fd(&s.ready); close_fd(&s.gate);
    /* CLOEXEC EOF alone also occurs on a pre-exec signal. Confirm a successful
     * exec only after a normal exit, the complete private gate, and no error
     * marker. Reserve the child's internal failure exit126 conservatively:
     * even if a failure marker could not be delivered it cannot look executed.
     * Actual signaled/126 exits remain recorded, but start is unconfirmed. */
    if (s.exec_eof && s.ready_seen && s.granted && !s.exec_failed && s.ready_size == 1 &&
        (s.flags & F_EXIT) && s.leader_normal && s.child_exit != 126) s.flags |= F_EXEC;
    if (s.cause == COMPLETED && !(s.flags & F_EXEC)) note(&s, SETUP_FAILURE, 0);
    if (s.cause == COMPLETED && (s.flags != 31 || s.observed != s.captured || s.child_exit < 0)) {
        note(&s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN);
    }
    return emit_terminal(&s);
}
