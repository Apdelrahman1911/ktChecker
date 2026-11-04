/*
 * ktchecker owned live transport, preview-2. Linux-only, cooperative cleanup
 * for owner-trusted children; NOT a sandbox or a worker/job protocol.
 * LIVE_TRANSPORT_V2.md revision 2 is the wire contract.
 *
 * The actual fork child is held unreaped through the LAST real group signal.
 * All waitpid calls are behind signals_finished. There is no PID discovery,
 * post-reap signal, helper-kill recovery, or live execution/readiness claim.
 */
#define _POSIX_C_SOURCE 200809L
#if !defined(__linux__)
#error unsupported_owned_live_supervisor_platform
#endif
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

enum {
    COMPLETED = 0, CANCELLED = 1, TIMEOUT = 2, OUTPUT_LIMIT = 3,
    PARENT_LOST = 4, IO_FAILURE = 5, EXEC_FAILURE = 6, SETUP_FAILURE = 7,
    SHUTDOWN_UNVERIFIED = 8, PROTOCOL_FAILURE = 9, OWNERSHIP_LOST = 10,
    INPUT_LIMIT = 11, CONTROL_LIMIT = 12
};
enum {
    F_EXIT = 1, F_REAPED = 2, F_QUIESCENT = 4, F_GROUP = 8,
    F_EXEC = 16, F_CANCELLED = 32, F_TIMEOUT = 64, F_OUTPUT = 128,
    F_PARENT = 256, F_IO = 512, F_EXEC_FAILED = 1024,
    F_PROTOCOL = 2048, F_OWNERSHIP = 4096, F_SHUTDOWN = 8192,
    F_INPUT = 16384, F_CONTROL = 32768, F_INPUT_INCOMPLETE = 65536,
    F_STDIN_CLOSED = 131072, F_OUTPUT_EOF = 262144, F_NO_CHILD = 524288,
    F_COMPLETED = 0x6001f
};
enum { BOUND = 1, CHILD_OUT = 2, CHILD_ERR = 3, ACK = 4, FINAL = 5 };
enum { ARM = 17, DATA = 18, DISARM = 19, CLOSE_STDIN = 20, CANCEL = 127 };
enum {
    DATA_MAX = 8192, CONTROL_PAYLOAD_MAX = 8200, CONTROL_OUTPUT_MAX = 85,
    CONTROL_TURN_BYTES = 16384, CONTROL_TURN_RECORDS = 32,
    REQUEST_MAX = 8192, EPOCH_MAX = 4096, TERM_GRACE_MS = 250,
    CLEANUP_MS = 2000, FLUSH_MS = 500, POLL_MS = 10
};
enum { WIRE_NONE, WIRE_CONTROL, WIRE_DATA };

struct supervisor {
    pid_t child;
    bool forked, owned, group, granted, ready_seen, exec_eof, exec_failed;
    bool leader_normal, signals_finished, reaping_started, stopping;
    bool wire_broken, control_closed, control_cancelled, parser_broken;
    bool wait_failed, clock_failed, out_eof, err_eof, bound_flushed;
    bool armed, ordinary_pending;
    int child_exit, input, out, err, ready, gate;
    uint32_t cause, flags;
    uint64_t start, generation_ms, generation_deadline, input_limit, output_limit;
    uint64_t epoch_deadline, last_epoch, kill_at, cleanup_deadline;
    uint64_t input_accepted, input_written, input_ack_flushed;
    uint64_t output_observed, output_captured, requests_accepted, acks_flushed;
    unsigned char nonce[16];
    unsigned char input_bytes[DATA_MAX];
    size_t input_size, input_offset;
    uint64_t input_sequence;
    unsigned char control_header[5], control_payload[CONTROL_PAYLOAD_MAX];
    size_t header_size, payload_size, payload_needed;
    unsigned char control_tag;
    unsigned char control_output[CONTROL_OUTPUT_MAX], data_output[DATA_MAX + 5];
    size_t control_size, control_offset, data_size, data_offset;
    unsigned char control_output_tag, ack_tag;
    uint64_t ack_input_bytes;
    int wire_active;
    unsigned char ready_bytes[6];
    size_t ready_size;
};

static volatile sig_atomic_t interrupted;

static void on_signal(int number) { (void)number; interrupted = 1; }

static void put32(unsigned char *bytes, uint32_t value) {
    for (int i = 3; i >= 0; --i) { bytes[i] = (unsigned char)value; value >>= 8; }
}

static void put64(unsigned char *bytes, uint64_t value) {
    for (int i = 7; i >= 0; --i) { bytes[i] = (unsigned char)value; value >>= 8; }
}

static uint32_t get32(const unsigned char *bytes) {
    uint32_t value = 0;
    for (int i = 0; i < 4; ++i) value = (value << 8) | bytes[i];
    return value;
}

static uint64_t get64(const unsigned char *bytes) {
    uint64_t value = 0;
    for (int i = 0; i < 8; ++i) value = (value << 8) | bytes[i];
    return value;
}

static bool now_ms(uint64_t *result) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0 || value.tv_sec < 0 ||
        value.tv_nsec < 0 || value.tv_nsec >= 1000000000L) return false;
    uint64_t seconds = (uint64_t)value.tv_sec;
    uint64_t fraction = (uint64_t)value.tv_nsec / 1000000u;
    if (seconds > (UINT64_MAX - fraction) / 1000u) return false;
    *result = seconds * 1000u + fraction;
    return true;
}

static bool add_ms(uint64_t base, uint64_t duration, uint64_t *result) {
    if (base > UINT64_MAX - duration) return false;
    *result = base + duration;
    return true;
}

static void close_fd(int *fd) {
    if (*fd >= 0) { (void)close(*fd); *fd = -1; }
}

static void close_input(struct supervisor *s) {
    close_fd(&s->input);
    s->flags |= F_STDIN_CLOSED;
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
    close_fd(&pair[0]); close_fd(&pair[1]);
    return false;
}

static void note(struct supervisor *s, uint32_t cause, uint32_t flags) {
    if (s->cause == COMPLETED && cause != COMPLETED) s->cause = cause;
    s->flags |= flags;
    if (flags & (F_OWNERSHIP | F_SHUTDOWN)) s->flags &= ~(uint32_t)F_QUIESCENT;
}

static void clock_error(struct supervisor *s) {
    s->clock_failed = true;
    note(s, IO_FAILURE, F_IO);
}

static bool parse_bound(const char *text, uint64_t maximum, uint64_t *result) {
    uint64_t value = 0;
    if (*text < '1' || *text > '9') return false;
    for (const unsigned char *p = (const unsigned char *)text; *p; ++p) {
        if (*p < '0' || *p > '9') return false;
        uint64_t digit = (uint64_t)(*p - '0');
        if (value > (maximum - digit) / 10u) return false;
        value = value * 10u + digit;
    }
    if (value == 0 || value > maximum) return false;
    *result = value;
    return true;
}

static bool parse_nonce(const char *text, unsigned char nonce[16]) {
    if (strlen(text) != 32) return false;
    unsigned int nonzero = 0;
    for (size_t i = 0; i < 16; ++i) {
        unsigned int value = 0;
        for (size_t j = 0; j < 2; ++j) {
            unsigned char ch = (unsigned char)text[i * 2 + j];
            unsigned int digit;
            if (ch >= '0' && ch <= '9') digit = (unsigned int)(ch - '0');
            else if (ch >= 'a' && ch <= 'f') digit = (unsigned int)(ch - 'a') + 10u;
            else return false;
            value = value * 16u + digit;
        }
        nonce[i] = (unsigned char)value;
        nonzero |= value;
    }
    return nonzero != 0;
}

static int exit_from_wait(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/* Ownership starts only at fork(), never from observing a numeric identifier.
 * WNOWAIT preserves the actual leader's kernel PID hold, including as a zombie.
 * Any unexpected wait/ownership error irreversibly revokes signal authority. */
static bool observe_leader(struct supervisor *s) {
    if (!s->owned || s->reaping_started) return false;
    siginfo_t info;
    memset(&info, 0, sizeof(info));
    int result = -1;
    for (int attempt = 0; attempt < 64; ++attempt) {
        result = waitid(P_PID, (id_t)s->child, &info, WEXITED | WNOHANG | WNOWAIT);
        if (result >= 0 || errno != EINTR) break;
    }
    if (result < 0 || (info.si_pid != 0 && info.si_pid != s->child)) {
        s->owned = false; s->wait_failed = true;
        note(s, OWNERSHIP_LOST, F_OWNERSHIP | F_SHUTDOWN);
        return false;
    }
    if (info.si_pid == s->child) {
        int code = -1;
        if (info.si_code == CLD_EXITED) code = info.si_status;
        else if (info.si_code == CLD_KILLED || info.si_code == CLD_DUMPED) code = 128 + info.si_status;
        if (code < 0 || code > 255 || ((s->flags & F_EXIT) && code != s->child_exit)) {
            s->owned = false; s->wait_failed = true;
            note(s, OWNERSHIP_LOST, F_OWNERSHIP | F_SHUTDOWN);
            return false;
        }
        s->flags |= F_EXIT;
        s->child_exit = code;
        s->leader_normal = info.si_code == CLD_EXITED;
    }
    return true;
}

/* The ONLY real signaling site. Before R, the closed exec gate means the one
 * held child cannot have exec'd a provider or created provider descendants.
 * After R, the same unreaped fork-child PID pins its established process group.
 * No liveness probe or signal, including on error, is allowed after any reap. */
static void signal_owned(struct supervisor *s, int number) {
    if (s->signals_finished || s->reaping_started || !s->owned) return;
    if (!observe_leader(s)) return;
    if (!s->group && s->granted) {
        s->owned = false; s->wait_failed = true;
        note(s, OWNERSHIP_LOST, F_OWNERSHIP | F_SHUTDOWN);
        return;
    }
    pid_t destination = s->group ? -s->child : s->child;
    if (kill(destination, number) != 0 && errno != ESRCH) {
        note(s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN);
    }
}

static void begin_stop(struct supervisor *s, uint64_t now) {
    if (s->stopping) return;
    s->stopping = true;
    /* Irreversible retirement: no more gate grants or application writes. */
    close_fd(&s->gate);
    close_input(s);
    s->input_size = s->input_offset = 0;
    if (s->input_accepted > s->input_written) s->flags |= F_INPUT_INCOMPLETE;
    uint64_t grace = ((s->flags & F_EXIT) && s->leader_normal) ? 0u : TERM_GRACE_MS;
    if (!add_ms(now, grace, &s->kill_at) || !add_ms(now, CLEANUP_MS, &s->cleanup_deadline)) {
        clock_error(s);
    }
    signal_owned(s, SIGTERM);
}

static void finish_signals(struct supervisor *s) {
    if (s->signals_finished) return;
    close_fd(&s->gate);
    signal_owned(s, SIGKILL);
    s->signals_finished = true; /* irreversible BEFORE ANY waitpid */
}

static bool check_time(struct supervisor *s, uint64_t *now) {
    if (s->clock_failed || !now_ms(now)) { clock_error(s); return false; }
    if (!s->stopping && (*now >= s->generation_deadline || (s->armed && *now >= s->epoch_deadline))) {
        note(s, TIMEOUT, F_TIMEOUT);
        begin_stop(s, *now);
    }
    return !s->clock_failed;
}

static void stop_for_cause(struct supervisor *s, uint64_t now) {
    if (!s->stopping && s->cause != COMPLETED) begin_stop(s, now);
}

static void reaped(struct supervisor *s, pid_t pid, int status) {
    if (pid != s->child) return;
    int code = exit_from_wait(status);
    if (code < 0 || code > 255 || ((s->flags & F_EXIT) && code != s->child_exit)) {
        note(s, OWNERSHIP_LOST, F_OWNERSHIP | F_SHUTDOWN);
        s->wait_failed = true;
    } else {
        s->child_exit = code; s->flags |= F_EXIT;
        s->leader_normal = WIFEXITED(status);
    }
    s->flags |= F_REAPED;
    s->owned = false;
}

static void reap_after_last_signal(struct supervisor *s) {
    if (!s->signals_finished) return;
    s->reaping_started = true;
    /* Linux reparenting is synchronous with parent exit. ECHILD after actual
     * leader reaping acknowledges our kernel child set, not a /proc census. */
    for (int i = 0; i < 64; ++i) {
        int status = 0;
        pid_t pid = waitpid(-1, &status, WNOHANG);
        if (pid > 0) { reaped(s, pid, status); continue; }
        if (pid == 0) return;
        if (errno == EINTR) continue;
        if (errno == ECHILD && (s->flags & F_REAPED) && !s->wait_failed &&
            !(s->flags & (F_OWNERSHIP | F_SHUTDOWN))) s->flags |= F_QUIESCENT;
        else { note(s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN); s->wait_failed = true; }
        return;
    }
}

static bool cleanup_turn(struct supervisor *s, uint64_t *now) {
    if (!s->stopping) return false;
    if (*now >= s->kill_at) finish_signals(s);
    if (s->signals_finished) reap_after_last_signal(s);
    /* A late ECHILD/pipe observation cannot qualify an in-budget cleanup.
     * Sample AFTER the work, before the success predicate, and keep actual
     * EXIT/REAPED facts even when the overall proof missed its deadline. */
    if (!check_time(s, now)) return false;
    if (*now >= s->cleanup_deadline) {
        note(s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN);
        /* Time can cross both deadlines after the earlier sample. Finish
         * signals before the one remaining bounded reap pass in that case;
         * no second pass and no signal after any earlier reap are permitted. */
        if (!s->signals_finished) {
            finish_signals(s);
            reap_after_last_signal(s);
        }
        if (s->out >= 0 || s->err >= 0 || s->ready >= 0) note(s, IO_FAILURE, F_IO);
        return true;
    }
    return (s->flags & F_QUIESCENT) && s->out < 0 && s->err < 0 && s->ready < 0;
}

/* Clock failure cannot manufacture a new cleanup budget. One bounded pass is
 * all that remains after immediate last signals; never return transport zero. */
static int clock_abort(struct supervisor *s) {
    clock_error(s);
    if (!s->stopping) {
        s->stopping = true;
        close_fd(&s->gate); close_input(s);
        s->input_size = s->input_offset = 0;
        signal_owned(s, SIGTERM);
    }
    finish_signals(s);
    if (s->forked) reap_after_last_signal(s);
    close_fd(&s->out); close_fd(&s->err); close_fd(&s->ready); close_fd(&s->gate);
    return 74;
}

static void queue_bound(struct supervisor *s) {
    /* No writes occur until fork has succeeded, or the no-child failure path
     * is irrevocable. Immediate ARM after BOUND receipt is therefore legal. */
    memcpy(s->control_output, "KTCLIV2\n", 8);
    s->control_output[8] = BOUND;
    put32(s->control_output + 9, 40);
    memcpy(s->control_output + 13, s->nonce, 16);
    put64(s->control_output + 29, s->generation_ms);
    put64(s->control_output + 37, s->input_limit);
    put64(s->control_output + 45, s->output_limit);
    s->control_size = 53; s->control_offset = 0;
    s->control_output_tag = BOUND;
}

static void queue_ack(struct supervisor *s, unsigned char tag, uint64_t sequence,
                      uint64_t value_a, uint64_t value_b) {
    if (s->control_size != 0 || !s->ordinary_pending || !s->bound_flushed) {
        note(s, PROTOCOL_FAILURE, F_PROTOCOL);
        return;
    }
    s->control_output[0] = ACK;
    put32(s->control_output + 1, 32);
    put32(s->control_output + 5, tag);
    put32(s->control_output + 9, 0);
    put64(s->control_output + 13, sequence);
    put64(s->control_output + 21, value_a);
    put64(s->control_output + 29, value_b);
    s->control_size = 37; s->control_offset = 0;
    s->control_output_tag = ACK; s->ack_tag = tag;
    s->ack_input_bytes = tag == DATA ? value_a : 0;
}

static void queue_output(struct supervisor *s, unsigned char tag, const unsigned char *bytes, size_t size) {
    if (s->data_size != 0 || size == 0 || size > DATA_MAX) {
        note(s, PROTOCOL_FAILURE, F_PROTOCOL);
        return;
    }
    s->data_output[0] = tag;
    put32(s->data_output + 1, (uint32_t)size);
    memcpy(s->data_output + 5, bytes, size);
    s->data_size = size + 5; s->data_offset = 0;
}

static void pump_wire(struct supervisor *s) {
    if (s->wire_broken) return;
    if (s->wire_active == WIRE_NONE) {
        if (s->control_size) s->wire_active = WIRE_CONTROL;
        else if (s->data_size) s->wire_active = WIRE_DATA;
        else return;
    }
    bool control = s->wire_active == WIRE_CONTROL;
    size_t *offset = control ? &s->control_offset : &s->data_offset;
    size_t *size = control ? &s->control_size : &s->data_size;
    unsigned char *bytes = control ? s->control_output : s->data_output;
    size_t remaining = *size - *offset;
    if (remaining > DATA_MAX) remaining = DATA_MAX;
    ssize_t count = write(STDOUT_FILENO, bytes + *offset, remaining);
    if (count > 0) {
        *offset += (size_t)count;
        if (*offset == *size) {
            if (control && s->control_output_tag == BOUND) s->bound_flushed = true;
            if (control && s->control_output_tag == ACK) {
                /* Credit and input-ACK accounting change at complete frame
                 * write, never queue admission or partial stdout delivery. */
                ++s->acks_flushed;
                s->input_ack_flushed += s->ack_input_bytes;
                s->ordinary_pending = false;
                s->ack_tag = 0; s->ack_input_bytes = 0;
            }
            *offset = *size = 0;
            s->wire_active = WIRE_NONE;
        }
    } else if (count == 0 || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) {
        s->wire_broken = true;
        note(s, PARENT_LOST, F_PARENT | F_IO);
    }
}

static bool valid_control_header(unsigned char tag, uint32_t size) {
    if (tag == ARM) return size == 24;
    if (tag == DATA) return size >= 9 && size <= CONTROL_PAYLOAD_MAX;
    if (tag == DISARM) return size == 16;
    if (tag == CLOSE_STDIN) return size == 8;
    if (tag == CANCEL) return size == 0;
    return false;
}

static void accept_control(struct supervisor *s) {
    uint64_t now;
    /* This sample is per complete record, not per batch: expiration wins
     * over a just-arrived DISARM/CANCEL and cannot be erased by either. */
    if (!check_time(s, &now)) return;
    /* A sticky fatal cause (for example the previous poll failure) must
     * retire BEFORE any ordinary acceptance, not only after its dispatch.
     * CANCEL below remains classifiable while already retiring. */
    stop_for_cause(s, now);
    unsigned char tag = s->control_tag;
    if (tag == CANCEL) {
        s->control_cancelled = true;
        note(s, CANCELLED, F_CANCELLED);
        stop_for_cause(s, now);
        return;
    }
    if (s->stopping) return; /* bounded discard; never accept/ACK anew */
    if (!s->forked || !s->bound_flushed || s->ordinary_pending) {
        note(s, PROTOCOL_FAILURE, F_PROTOCOL);
        begin_stop(s, now);
        return;
    }
    uint64_t sequence = get64(s->control_payload);
    if (sequence != s->requests_accepted + 1u) {
        note(s, PROTOCOL_FAILURE, F_PROTOCOL);
        begin_stop(s, now);
        return;
    }
    if (s->requests_accepted >= REQUEST_MAX) {
        note(s, CONTROL_LIMIT, F_CONTROL);
        begin_stop(s, now);
        return;
    }
    uint64_t value = tag == ARM || tag == DISARM ? get64(s->control_payload + 8) : 0;
    uint64_t budget = tag == ARM ? get64(s->control_payload + 16) : 0;
    size_t data_size = tag == DATA ? s->payload_needed - 8 : 0;
    if (tag == ARM && s->last_epoch >= EPOCH_MAX && value == s->last_epoch + 1u) {
        note(s, CONTROL_LIMIT, F_CONTROL);
        begin_stop(s, now);
        return;
    }
    if ((tag == ARM && (s->armed || value != s->last_epoch + 1u || budget == 0 || budget > 3600000u)) ||
        (tag == DATA && (!s->armed || s->input < 0)) ||
        (tag == DISARM && (!s->armed || value != s->last_epoch || s->input_size != 0)) ||
        (tag == CLOSE_STDIN && (s->input < 0 || s->input_size != 0))) {
        note(s, PROTOCOL_FAILURE, F_PROTOCOL);
        begin_stop(s, now);
        return;
    }
    if (tag == DATA && (uint64_t)data_size > s->input_limit - s->input_accepted) {
        note(s, INPUT_LIMIT, F_INPUT);
        begin_stop(s, now);
        return;
    }
    uint64_t epoch_deadline = 0;
    if (tag == ARM && !add_ms(now, budget, &epoch_deadline)) { clock_error(s); return; }
    ++s->requests_accepted;
    s->ordinary_pending = true;
    if (tag == ARM) {
        s->armed = true; s->last_epoch = value;
        s->epoch_deadline = epoch_deadline < s->generation_deadline ? epoch_deadline : s->generation_deadline;
        queue_ack(s, ARM, sequence, value, 0);
    } else if (tag == DATA) {
        memcpy(s->input_bytes, s->control_payload + 8, data_size);
        s->input_size = data_size; s->input_offset = 0; s->input_sequence = sequence;
        s->input_accepted += (uint64_t)data_size;
    } else if (tag == DISARM) {
        s->armed = false;
        queue_ack(s, DISARM, sequence, value, 0);
    } else if (tag == CLOSE_STDIN) {
        close_input(s);
        queue_ack(s, CLOSE_STDIN, sequence, 0, 0);
    }
    stop_for_cause(s, now);
}

static void control_eof(struct supervisor *s) {
    s->control_closed = true;
    if (!s->control_cancelled) note(s, PARENT_LOST, F_PARENT);
    if (s->header_size != 0 || s->payload_size != 0) note(s, PROTOCOL_FAILURE, F_PROTOCOL);
}

static void read_control(struct supervisor *s) {
    size_t work = 0, records = 0;
    while (!s->control_closed && !s->parser_broken && !s->clock_failed &&
           work < CONTROL_TURN_BYTES && records < CONTROL_TURN_RECORDS) {
        unsigned char *destination;
        size_t needed;
        if (s->header_size < 5) {
            destination = s->control_header + s->header_size;
            needed = 5 - s->header_size;
        } else {
            destination = s->control_payload + s->payload_size;
            needed = s->payload_needed - s->payload_size;
        }
        if (needed > DATA_MAX) needed = DATA_MAX;
        if (needed > CONTROL_TURN_BYTES - work) needed = CONTROL_TURN_BYTES - work;
        ssize_t count = read(STDIN_FILENO, destination, needed);
        if (count > 0) {
            work += (size_t)count;
            if (s->header_size < 5) {
                s->header_size += (size_t)count;
                if (s->header_size < 5) continue;
                s->control_tag = s->control_header[0];
                uint32_t size = get32(s->control_header + 1);
                if (!valid_control_header(s->control_tag, size)) {
                    uint64_t now;
                    if (!check_time(s, &now)) return;
                    s->parser_broken = true;
                    note(s, PROTOCOL_FAILURE, F_PROTOCOL);
                    stop_for_cause(s, now);
                    return;
                }
                s->payload_needed = size;
            } else s->payload_size += (size_t)count;
            if (s->header_size == 5 && s->payload_size == s->payload_needed) {
                accept_control(s);
                s->header_size = s->payload_size = s->payload_needed = 0;
                ++records;
            }
        } else if (count == 0) { control_eof(s); return; }
        else if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return;
        else { s->control_closed = true; note(s, IO_FAILURE, F_IO); return; }
    }
}

static void write_input(struct supervisor *s) {
    if (s->stopping || s->input < 0 || s->input_size == 0) return;
    uint64_t now;
    if (!check_time(s, &now) || s->stopping) return;
    if (!s->armed) { note(s, PROTOCOL_FAILURE, F_PROTOCOL); begin_stop(s, now); return; }
    size_t remaining = s->input_size - s->input_offset;
    ssize_t count = write(s->input, s->input_bytes + s->input_offset, remaining);
    if (count > 0) {
        s->input_offset += (size_t)count;
        s->input_written += (uint64_t)count;
        if (s->input_offset == s->input_size) {
            uint64_t bytes = (uint64_t)s->input_size;
            s->input_size = s->input_offset = 0;
            queue_ack(s, DATA, s->input_sequence, bytes, s->input_written);
        }
    } else if (count == 0 || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) {
        note(s, IO_FAILURE, F_IO);
    }
    stop_for_cause(s, now);
}

static void fail_child(int fd, unsigned char marker) {
    unsigned char record[5]; record[0] = marker;
    put32(record + 1, (uint32_t)errno);
    size_t offset = 0;
    while (offset < sizeof(record)) {
        ssize_t count = write(fd, record + offset, sizeof(record) - offset);
        if (count > 0) offset += (size_t)count;
        else if (count < 0 && errno == EINTR) continue;
        else break;
    }
    _exit(126);
}

static void child_exec(int input[2], int out[2], int err[2], int ready[2], int gate[2], char **command) {
    close_fd(&input[1]); close_fd(&out[0]); close_fd(&err[0]); close_fd(&ready[0]); close_fd(&gate[1]);
    struct sigaction action;
    memset(&action, 0, sizeof(action)); action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    const int signals[] = { SIGCHLD, SIGPIPE, SIGTERM, SIGINT, SIGHUP };
    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i) {
        if (sigaction(signals[i], &action, NULL) != 0) fail_child(ready[1], 'S');
    }
    sigset_t empty; sigemptyset(&empty);
    if (sigprocmask(SIG_SETMASK, &empty, NULL) != 0) fail_child(ready[1], 'S');
    if (dup2(input[0], STDIN_FILENO) < 0 || dup2(out[1], STDOUT_FILENO) < 0 ||
        dup2(err[1], STDERR_FILENO) < 0) fail_child(ready[1], 'S');
    close_fd(&input[0]); close_fd(&out[1]); close_fd(&err[1]);
    if (setsid() != getpid()) fail_child(ready[1], 'S');
    unsigned char value = 'R'; ssize_t count;
    do { count = write(ready[1], &value, 1); } while (count < 0 && errno == EINTR);
    if (count != 1) { errno = EIO; fail_child(ready[1], 'S'); }
    do { count = read(gate[0], &value, 1); } while (count < 0 && errno == EINTR);
    if (count != 1 || value != 'G') { errno = EIO; fail_child(ready[1], 'S'); }
    close_fd(&gate[0]);
    execv(command[0], command); /* exact argv; no shell or PATH fallback */
    fail_child(ready[1], 'E');
}

static bool launch(struct supervisor *s, char **command) {
    int input[2] = {-1, -1}, out[2] = {-1, -1}, err[2] = {-1, -1};
    int ready[2] = {-1, -1}, gate[2] = {-1, -1};
    bool prepared = make_pipe(input) && make_pipe(out) && make_pipe(err) && make_pipe(ready) && make_pipe(gate);
    if (prepared) prepared = set_fd_flags(input[1], true) && set_fd_flags(out[0], true) &&
        set_fd_flags(err[0], true) && set_fd_flags(ready[0], true) && set_fd_flags(gate[1], true);
    if (prepared) {
        s->child = fork();
        if (s->child == 0) child_exec(input, out, err, ready, gate, command);
        if (s->child > 0) {
            s->forked = s->owned = true;
            close_fd(&input[0]); close_fd(&out[1]); close_fd(&err[1]); close_fd(&ready[1]); close_fd(&gate[0]);
            s->input = input[1]; s->out = out[0]; s->err = err[0]; s->ready = ready[0]; s->gate = gate[1];
            return true;
        }
    }
    for (int i = 0; i < 2; ++i) {
        close_fd(&input[i]); close_fd(&out[i]); close_fd(&err[i]); close_fd(&ready[i]); close_fd(&gate[i]);
    }
    note(s, SETUP_FAILURE, 0);
    return false;
}

static void read_ready(struct supervisor *s) {
    if (s->ready < 0) return;
    unsigned char bytes[8];
    ssize_t count = read(s->ready, bytes, sizeof(bytes));
    if (count > 0) {
        if (s->ready_size + (size_t)count > sizeof(s->ready_bytes)) {
            note(s, PROTOCOL_FAILURE, F_PROTOCOL); close_fd(&s->ready); return;
        }
        memcpy(s->ready_bytes + s->ready_size, bytes, (size_t)count);
        s->ready_size += (size_t)count;
        if (s->ready_bytes[0] == 'R') {
            if (!s->ready_seen) { s->ready_seen = true; s->group = true; s->flags |= F_GROUP; }
            if (s->ready_size > 1 && s->ready_bytes[1] != 'E' && s->ready_bytes[1] != 'S') {
                note(s, PROTOCOL_FAILURE, F_PROTOCOL);
            }
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
    if (!s->ready_seen || s->ready_size != 1 || s->granted || s->stopping ||
        s->cause != COMPLETED || s->gate < 0) return;
    uint64_t now;
    if (!check_time(s, &now) || s->stopping) return;
    unsigned char value = 'G';
    ssize_t count = write(s->gate, &value, 1);
    if (count == 1) { s->granted = true; close_fd(&s->gate); }
    else if (count == 0 || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) {
        note(s, SETUP_FAILURE, 0); close_fd(&s->gate);
    }
    stop_for_cause(s, now);
}

static void read_output(struct supervisor *s, int *fd, unsigned char tag, bool *eof) {
    if (*fd < 0 || s->data_size != 0 || s->wire_broken) return;
    unsigned char bytes[DATA_MAX];
    ssize_t count = read(*fd, bytes, sizeof(bytes));
    if (count > 0) {
        /* First overflow closes both ends. Observed is bounded by cap+8192,
         * not a fabricated total of all bytes the child tried to produce. */
        s->output_observed += (uint64_t)count;
        uint64_t available = s->output_limit - s->output_captured;
        size_t retained = (size_t)count;
        if ((uint64_t)retained > available) retained = (size_t)available;
        if (retained) {
            queue_output(s, tag, bytes, retained);
            s->output_captured += (uint64_t)retained;
        }
        if ((size_t)count > retained) {
            note(s, OUTPUT_LIMIT, F_OUTPUT);
            close_fd(&s->out); close_fd(&s->err);
        }
    } else if (count == 0) {
        *eof = true; close_fd(fd);
        if (s->out_eof && s->err_eof) s->flags |= F_OUTPUT_EOF;
    } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        close_fd(fd); note(s, IO_FAILURE, F_IO);
    }
}

static bool configure_signals(void) {
    struct sigaction action; memset(&action, 0, sizeof(action)); sigemptyset(&action.sa_mask);
    action.sa_handler = SIG_DFL; /* undo inherited SIG_IGN / SA_NOCLDWAIT */
    if (sigaction(SIGCHLD, &action, NULL) != 0) return false;
    action.sa_handler = SIG_IGN;
    if (sigaction(SIGPIPE, &action, NULL) != 0) return false;
    action.sa_handler = on_signal;
    if (sigaction(SIGTERM, &action, NULL) != 0 || sigaction(SIGINT, &action, NULL) != 0 ||
        sigaction(SIGHUP, &action, NULL) != 0) return false;
    sigset_t empty; sigemptyset(&empty);
    return sigprocmask(SIG_SETMASK, &empty, NULL) == 0;
}

static int shorter_delay(int delay, uint64_t now, uint64_t deadline) {
    if (now >= deadline) return 0;
    uint64_t remaining = deadline - now;
    return remaining < (uint64_t)delay ? (int)remaining : delay;
}

static void poll_once(struct supervisor *s, uint64_t now) {
    struct pollfd fds[7]; nfds_t count = 0;
    if (!s->control_closed && !s->parser_broken) fds[count++] = (struct pollfd){ STDIN_FILENO, POLLIN, 0 };
    if (!s->wire_broken && (s->control_size || s->data_size)) {
        fds[count++] = (struct pollfd){ STDOUT_FILENO, POLLOUT, 0 };
    }
    if (s->ready >= 0) fds[count++] = (struct pollfd){ s->ready, POLLIN, 0 };
    if (!s->stopping && s->input >= 0 && s->input_size) fds[count++] = (struct pollfd){ s->input, POLLOUT, 0 };
    if (!s->stopping && s->ready_seen && !s->granted && s->gate >= 0) {
        fds[count++] = (struct pollfd){ s->gate, POLLOUT, 0 };
    }
    if (s->data_size == 0 && !s->wire_broken) {
        if (s->out >= 0) fds[count++] = (struct pollfd){ s->out, POLLIN, 0 };
        if (s->err >= 0) fds[count++] = (struct pollfd){ s->err, POLLIN, 0 };
    }
    int delay = POLL_MS;
    if (s->stopping) {
        delay = shorter_delay(delay, now, s->cleanup_deadline);
        if (!s->signals_finished) delay = shorter_delay(delay, now, s->kill_at);
    } else {
        delay = shorter_delay(delay, now, s->generation_deadline);
        if (s->armed) delay = shorter_delay(delay, now, s->epoch_deadline);
    }
    if (poll(fds, count, delay) < 0 && errno != EINTR) note(s, IO_FAILURE, F_IO);
}

static bool read_preamble(struct supervisor *s) {
    unsigned char expected[24], received[24];
    memcpy(expected, "KTCCTL2\n", 8); memcpy(expected + 8, s->nonce, 16);
    size_t size = 0;
    while (size < sizeof(received)) {
        uint64_t now;
        if (!now_ms(&now) || now >= s->generation_deadline || interrupted) return false;
        ssize_t count = read(STDIN_FILENO, received + size, sizeof(received) - size);
        if (count > 0) {
            size += (size_t)count;
            if (memcmp(received, expected, size) != 0) return false;
        } else if (count == 0) return false;
        else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) return false;
        if (size < sizeof(received)) {
            struct pollfd fd = { STDIN_FILENO, POLLIN, 0 };
            int delay = shorter_delay(POLL_MS, now, s->generation_deadline);
            if (poll(&fd, 1, delay) < 0 && errno != EINTR) return false;
        }
    }
    uint64_t now;
    return now_ms(&now) && now < s->generation_deadline && !interrupted;
}

static void prepare_final(struct supervisor *s) {
    if (s->input_accepted > s->input_written) s->flags |= F_INPUT_INCOMPLETE;
    else s->flags &= ~(uint32_t)F_INPUT_INCOMPLETE;
    if (!s->forked) {
        s->flags |= F_NO_CHILD | F_QUIESCENT | F_STDIN_CLOSED | F_OUTPUT_EOF;
        if (s->cause == COMPLETED) note(s, SETUP_FAILURE, 0);
        return;
    }
    /* CLOEXEC EOF is not exec evidence by itself: it also follows pre-exec
     * death. Only the full gate, error-free EOF, and a normal non-126 exit can
     * confirm exec. No live event makes this claim. */
    if (s->exec_eof && s->ready_seen && s->granted && !s->exec_failed && s->ready_size == 1 &&
        (s->flags & F_EXIT) && s->leader_normal && s->child_exit != 126) s->flags |= F_EXEC;
    if (s->cause == COMPLETED && s->input_accepted > s->input_written) note(s, IO_FAILURE, F_IO);
    if (s->cause == COMPLETED && !(s->flags & F_EXEC)) note(s, SETUP_FAILURE, 0);
    if (s->cause == COMPLETED && (s->flags != F_COMPLETED || s->output_observed != s->output_captured ||
        s->input_accepted != s->input_ack_flushed || s->requests_accepted != s->acks_flushed)) {
        note(s, SHUTDOWN_UNVERIFIED, F_SHUTDOWN);
        s->flags &= ~(uint32_t)F_QUIESCENT;
    }
}

static void queue_final(struct supervisor *s) {
    prepare_final(s); /* pending eligible ACKs have ALREADY fully flushed */
    s->control_output[0] = FINAL; put32(s->control_output + 1, 80);
    unsigned char *record = s->control_output + 5;
    put32(record, s->cause); put32(record + 4, s->flags);
    put32(record + 8, (uint32_t)s->child_exit); put32(record + 12, 0);
    put64(record + 16, s->input_accepted); put64(record + 24, s->input_written);
    put64(record + 32, s->input_ack_flushed); put64(record + 40, s->output_observed);
    put64(record + 48, s->output_captured); put64(record + 56, s->requests_accepted);
    put64(record + 64, s->acks_flushed); put64(record + 72, s->last_epoch);
    s->control_size = 85; s->control_offset = 0; s->control_output_tag = FINAL;
}

static int emit_terminal(struct supervisor *s) {
    /* No signaling, reaping, new child reads, or ordinary dispatch below.
     * Existing partial frames and eligible ACKs share this SAME flush budget
     * with FINAL; its counters are snapshotted only once they fully flush. */
    uint64_t start, deadline;
    if (!now_ms(&start) || !add_ms(start, FLUSH_MS, &deadline)) return 74;
    bool queued = false;
    for (int iterations = 0; iterations < 1024; ++iterations) {
        uint64_t now;
        if (s->wire_broken || !now_ms(&now) || now >= deadline) return 74;
        if (!queued) {
            read_control(s);
            if (s->clock_failed) return 74;
            if (interrupted) note(s, CANCELLED, F_CANCELLED);
        }
        pump_wire(s);
        /* Full delivery observed at/after expiry (or with a failed clock)
         * cannot earn helper exit zero, even if the FINAL bytes escaped. */
        if (s->wire_broken || !now_ms(&now) || now >= deadline) return 74;
        if (!s->control_size && !s->data_size) {
            if (queued) return 0;
            queue_final(s); queued = true;
        }
        struct pollfd fds[2]; nfds_t count = 0;
        fds[count++] = (struct pollfd){ STDOUT_FILENO, POLLOUT, 0 };
        if (!queued && !s->control_closed && !s->parser_broken) {
            fds[count++] = (struct pollfd){ STDIN_FILENO, POLLIN, 0 };
        }
        int delay = shorter_delay(5, now, deadline);
        if (poll(fds, count, delay) < 0 && errno != EINTR) return 74;
    }
    return 74;
}

int main(int argc, char **argv) {
    struct supervisor s; memset(&s, 0, sizeof(s));
    s.child_exit = -1; s.input = s.out = s.err = s.ready = s.gate = -1;
    if (argc < 8 || argc > 4103 || strcmp(argv[1], "--ktc-owned-live-v2") != 0 ||
        strcmp(argv[6], "--") != 0 || argv[7][0] != '/' ||
        !parse_bound(argv[2], 3600000, &s.generation_ms) ||
        !parse_bound(argv[3], 16777216, &s.input_limit) ||
        !parse_bound(argv[4], 16777216, &s.output_limit) || !parse_nonce(argv[5], s.nonce)) return 64;
    size_t argv_bytes = 0;
    for (int i = 7; i < argc; ++i) {
        size_t size = strlen(argv[i]);
        if (size > 65536 || argv_bytes > 1048576 - size - 1) return 64;
        argv_bytes += size + 1;
    }
    if (!now_ms(&s.start) || !add_ms(s.start, s.generation_ms, &s.generation_deadline) ||
        !configure_signals() || !set_fd_flags(STDIN_FILENO, true) || !set_fd_flags(STDOUT_FILENO, true)) return 70;
    /* Invalid/truncated/timed-out association never forks and cannot claim a
     * valid BOUND/FINAL. The JVM conservatively quarantines that transport. */
    if (!read_preamble(&s)) return 74;
    queue_bound(&s);
    /* BOUND is only queued here, NOT flushed before the successful fork.
     * CANCEL can still prevent that fork; premature ordinary controls fail. */
    read_control(&s);
    uint64_t now;
    if (!check_time(&s, &now)) return clock_abort(&s);
    if (interrupted) note(&s, CANCELLED, F_CANCELLED);
    stop_for_cause(&s, now);
    if (s.cause == COMPLETED && prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0) note(&s, SETUP_FAILURE, 0);
    if (!check_time(&s, &now)) return clock_abort(&s);
    stop_for_cause(&s, now);
    if (s.cause != COMPLETED || !launch(&s, argv + 7)) {
        s.stopping = true; close_input(&s);
        return emit_terminal(&s);
    }
    bool alternate = false;
    for (;;) {
        if (!check_time(&s, &now)) return clock_abort(&s);
        read_control(&s);
        if (s.clock_failed) return clock_abort(&s);
        read_ready(&s);
        if (interrupted) note(&s, CANCELLED, F_CANCELLED);
        if (!s.signals_finished) (void)observe_leader(&s);
        if (!check_time(&s, &now)) return clock_abort(&s);
        if (!s.stopping && (s.flags & F_EXIT) && s.input_accepted > s.input_written) {
            note(&s, IO_FAILURE, F_IO | F_INPUT_INCOMPLETE);
        }
        if (!s.stopping && (s.cause != COMPLETED || (s.flags & F_EXIT))) begin_stop(&s, now);
        if (s.clock_failed) return clock_abort(&s);
        grant_exec(&s);
        if (s.clock_failed) return clock_abort(&s);
        write_input(&s); /* independent of helper stdout/data queue availability */
        if (s.clock_failed) return clock_abort(&s);
        pump_wire(&s);
        if (alternate) {
            read_output(&s, &s.err, CHILD_ERR, &s.err_eof);
            read_output(&s, &s.out, CHILD_OUT, &s.out_eof);
        } else {
            read_output(&s, &s.out, CHILD_OUT, &s.out_eof);
            read_output(&s, &s.err, CHILD_ERR, &s.err_eof);
        }
        alternate = !alternate;
        if (!check_time(&s, &now)) return clock_abort(&s);
        stop_for_cause(&s, now);
        if (s.clock_failed) return clock_abort(&s);
        bool cleanup_done = cleanup_turn(&s, &now);
        if (s.clock_failed) return clock_abort(&s);
        if (cleanup_done) break;
        poll_once(&s, now);
    }
    /* Closing unread/error/overflow pipes cannot manufacture OUTPUT_EOF. */
    close_fd(&s.out); close_fd(&s.err); close_fd(&s.ready); close_fd(&s.gate); close_input(&s);
    return emit_terminal(&s);
}
