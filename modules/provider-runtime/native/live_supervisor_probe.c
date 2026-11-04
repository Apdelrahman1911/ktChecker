/* Public, tiny synthetic child for preview-2 tests. No compiler, JVM, shell,
 * network, application job schedule, or daemon. Every waiting child expires
 * on its own SIGALRM; test cleanup never needs to kill an owned helper. */
#define _GNU_SOURCE 1
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

static int arm_expiry(void) {
    struct sigaction action; memset(&action, 0, sizeof(action));
    action.sa_handler = SIG_DFL;
    sigset_t unblocked;
    if (sigemptyset(&action.sa_mask) != 0 || sigemptyset(&unblocked) != 0 ||
        sigaddset(&unblocked, SIGALRM) != 0 || sigaction(SIGALRM, &action, NULL) != 0 ||
        sigprocmask(SIG_UNBLOCK, &unblocked, NULL) != 0) return -1;
    (void)alarm(12);
    return 0;
}

static int write_all(int fd, const void *data, size_t size) {
    const unsigned char *bytes = data;
    while (size) {
        ssize_t count = write(fd, bytes, size);
        if (count > 0) { bytes += count; size -= (size_t)count; }
        else if (count < 0 && errno == EINTR) continue;
        else return -1;
    }
    return 0;
}

/* 1 complete, 0 EOF before any byte, -1 partial/error. */
static int read_exact(int fd, unsigned char *bytes, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        ssize_t count = read(fd, bytes + offset, size - offset);
        if (count > 0) offset += (size_t)count;
        else if (count == 0) return offset == 0 ? 0 : -1;
        else if (errno != EINTR) return -1;
    }
    return 1;
}

static void delay(unsigned int millis) {
    struct timespec duration = { (time_t)(millis / 1000), (long)(millis % 1000) * 1000000L };
    while (nanosleep(&duration, &duration) < 0 && errno == EINTR) { }
}

static int mark(const char *path, const char *text) {
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    int result = write_all(fd, text, strlen(text));
    if (close(fd) < 0) result = -1;
    return result;
}

static int mark_pid(const char *path) {
    char text[48];
    int count = snprintf(text, sizeof(text), "%ld", (long)getpid());
    return count > 0 && (size_t)count < sizeof(text) ? mark(path, text) : -1;
}

static void resistant(void) {
    struct sigaction action; memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask); action.sa_handler = SIG_IGN;
    if (sigaction(SIGTERM, &action, NULL) != 0 || sigaction(SIGINT, &action, NULL) != 0 ||
        sigaction(SIGHUP, &action, NULL) != 0) _exit(90);
    /* Leave arm_expiry's explicit default/unblocked SIGALRM unchanged. */
}

static int late_writer(const char *marker, const char *ready, int signal_fd) {
    if (arm_expiry() != 0) return 118;
    resistant();
    if (mark_pid(ready) != 0) return 91;
    if (signal_fd >= 0) {
        if (write_all(signal_fd, "R", 1) != 0) return 92;
        (void)close(signal_fd);
    }
    if (write_all(STDOUT_FILENO, "ready\n", 6) != 0) return 93;
    delay(900);
    if (mark(marker, "late") != 0) return 94;
    delay(1500);
    return 0;
}

static int duplex(void) {
    for (;;) {
        unsigned char header[2], payload[2048];
        int state = read_exact(STDIN_FILENO, header, sizeof(header));
        if (state == 0) return 23; /* actual EOF, not a control-pipe close */
        if (state < 0) return 95;
        size_t size = (size_t)header[0] * 256u + header[1];
        if (size == 0 || size > sizeof(payload) || read_exact(STDIN_FILENO, payload, size) != 1) return 96;
        for (size_t i = 0; i < size / 2; ++i) {
            unsigned char swap = payload[i]; payload[i] = payload[size - i - 1]; payload[size - i - 1] = swap;
        }
        if (write_all(STDOUT_FILENO, header, sizeof(header)) != 0 ||
            write_all(STDOUT_FILENO, payload, size) != 0) return 97;
        /* Remain alive. The next request is selected by the caller only after
         * it observes this reply, not by a hardcoded finite child schedule. */
    }
}

static int echo(void) {
    unsigned char bytes[8192];
    for (;;) {
        ssize_t count = read(STDIN_FILENO, bytes, sizeof(bytes));
        if (count > 0) {
            if (write_all(STDOUT_FILENO, bytes, (size_t)count) != 0) return 98;
        } else if (count == 0) return 0;
        else if (errno != EINTR) return 99;
    }
}

static int pipe_capacity(void) {
    int capacity = fcntl(STDIN_FILENO, F_GETPIPE_SZ);
    if (capacity < 4096 || capacity > 1048576) return -1;
    char text[48];
    int size = snprintf(text, sizeof(text), "capacity=%d\n", capacity);
    if (size <= 0 || (size_t)size >= sizeof(text) || write_all(STDOUT_FILENO, text, (size_t)size) != 0) return -1;
    return capacity;
}

int main(int argc, char **argv) {
    if (argc < 2) return 64;
    if (arm_expiry() != 0) return 118;
    if (strcmp(argv[1], "duplex") == 0 && argc == 2) return duplex();
    if (strcmp(argv[1], "echo") == 0 && argc == 2) return echo();
    if (strcmp(argv[1], "argv") == 0) {
        for (int i = 2; i < argc; ++i) {
            size_t size = strlen(argv[i]); uint32_t value = (uint32_t)size;
            unsigned char header[4];
            for (int j = 3; j >= 0; --j) { header[j] = (unsigned char)value; value >>= 8; }
            if (write_all(STDOUT_FILENO, header, sizeof(header)) != 0 ||
                write_all(STDOUT_FILENO, argv[i], size) != 0) return 100;
        }
        if (write_all(STDERR_FILENO, "probe-stderr\n", 13) != 0) return 101;
        return 23;
    }
    if (strcmp(argv[1], "exit") == 0 && argc == 3) {
        char *end = NULL; errno = 0;
        long code = strtol(argv[2], &end, 10);
        if (errno || end == argv[2] || *end || code < 0 || code > 255) return 64;
        (void)write_all(STDOUT_FILENO, "public-output\n", 14);
        return (int)code;
    }
    if (strcmp(argv[1], "signal") == 0 && argc == 2) {
        (void)write_all(STDOUT_FILENO, "signal-started\n", 15);
        (void)raise(SIGKILL); return 102;
    }
    if (strcmp(argv[1], "started") == 0 && argc == 3) return mark(argv[2], "started") == 0 ? 0 : 103;
    if (strcmp(argv[1], "hold") == 0 && argc == 2) {
        resistant();
        for (;;) (void)pause();
    }
    if (strcmp(argv[1], "resist") == 0 && argc == 4) return late_writer(argv[2], argv[3], -1);
    if (strcmp(argv[1], "backpressure") == 0 && argc == 4) {
        resistant();
        if (pipe_capacity() < 0 || mark_pid(argv[3]) != 0) return 104;
        /* Deliberately never read stdin, including during TERM grace. */
        delay(900);
        if (mark(argv[2], "late") != 0) return 105;
        delay(1500); return 0;
    }
    if (strcmp(argv[1], "early-exit") == 0 && argc == 2) {
        if (pipe_capacity() < 0) return 106;
        delay(800); return 7; /* normal leader exit with unread pipe data */
    }
    if ((strcmp(argv[1], "orphan") == 0 || strcmp(argv[1], "flood") == 0 ||
         strcmp(argv[1], "both-pressure") == 0) && argc == 4) {
        if (strcmp(argv[1], "both-pressure") == 0 && pipe_capacity() < 0) return 116;
        int ready[2]; if (pipe(ready) != 0) return 107;
        pid_t child = fork();
        if (child < 0) return 108;
        if (child == 0) { (void)close(ready[0]); _exit(late_writer(argv[2], argv[3], ready[1])); }
        (void)close(ready[1]);
        unsigned char value = 0;
        int state = read_exact(ready[0], &value, 1); (void)close(ready[0]);
        if (state != 1 || value != 'R') return 109;
        if (strcmp(argv[1], "orphan") == 0) {
            (void)write_all(STDOUT_FILENO, "leader-exit\n", 12);
            return 0;
        }
        resistant();
        if (strcmp(argv[1], "both-pressure") == 0) {
            unsigned char trigger;
            if (read_exact(STDIN_FILENO, &trigger, 1) != 1) return 117;
            /* Exactly one trigger byte; never read stdin after flood starts. */
        }
        unsigned char bytes[8192]; memset(bytes, 'x', sizeof(bytes));
        for (int i = 0; i < 1024; ++i) {
            if (write_all(i % 2 ? STDOUT_FILENO : STDERR_FILENO, bytes, sizeof(bytes)) != 0) return 110;
        }
        return 0;
    }
    if (strcmp(argv[1], "escape") == 0 && argc == 3) {
        /* Explicitly outside the cooperative group boundary. A self-expiring
         * escaped descendant forces missing quiescence; the test must not
         * turn helper exit or a numeric-PID kill into a cleanup claim. */
        int ready[2]; if (pipe(ready) != 0) return 111;
        pid_t child = fork();
        if (child < 0) return 112;
        if (child == 0) {
            (void)close(ready[0]);
            if (arm_expiry() != 0) _exit(118);
            resistant();
            if (setsid() != getpid() || mark_pid(argv[2]) != 0 || write_all(ready[1], "R", 1) != 0) _exit(113);
            (void)close(ready[1]);
            (void)close(STDIN_FILENO); (void)close(STDOUT_FILENO); (void)close(STDERR_FILENO);
            delay(3500); _exit(0);
        }
        (void)close(ready[1]);
        unsigned char value = 0;
        int state = read_exact(ready[0], &value, 1); (void)close(ready[0]);
        return state == 1 && value == 'R' ? 0 : 114;
    }
    if (strcmp(argv[1], "unrelated") == 0 && argc == 3) {
        (void)write_all(STDOUT_FILENO, "ready\n", 6);
        delay(500);
        return mark(argv[2], "unrelated-survived") == 0 ? 0 : 115;
    }
    return 64;
}
