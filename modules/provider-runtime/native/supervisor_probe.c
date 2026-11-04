/* Tiny, public synthetic child for the native supervisor tests. No JVM, shell,
 * provider, network, or persistent daemon. Every waiting probe self-expires. */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

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

static void delay(unsigned int millis) {
    struct timespec duration = { (time_t)(millis / 1000), (long)(millis % 1000) * 1000000L };
    while (nanosleep(&duration, &duration) < 0 && errno == EINTR) { }
}

static int mark(const char *path, const char *text) {
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (fd < 0) return -1;
    int result = write_all(fd, text, strlen(text));
    if (close(fd) < 0) result = -1;
    return result;
}

static void resistant(void) {
    struct sigaction action; memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask); action.sa_handler = SIG_IGN;
    if (sigaction(SIGTERM, &action, NULL) != 0 || sigaction(SIGINT, &action, NULL) != 0 ||
        sigaction(SIGHUP, &action, NULL) != 0) _exit(90);
}

static int late_writer(const char *marker, const char *ready, int signal_fd) {
    resistant();
    char pid[48]; (void)snprintf(pid, sizeof(pid), "%ld", (long)getpid());
    if (mark(ready, pid) != 0) return 91;
    if (signal_fd >= 0) { if (write_all(signal_fd, "R", 1) != 0) return 92; (void)close(signal_fd); }
    if (write_all(STDOUT_FILENO, "ready\n", 6) != 0) return 93;
    delay(900);
    if (mark(marker, "late") != 0) return 94;
    delay(1200); /* self-expiry bounds even an intentionally failed test */
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) return 64;
    if (strcmp(argv[1], "argv") == 0) {
        for (int i = 2; i < argc; ++i) {
            size_t size = strlen(argv[i]);
            uint32_t value = (uint32_t)size; unsigned char header[4];
            for (int j = 3; j >= 0; --j) { header[j] = (unsigned char)value; value >>= 8; }
            if (write_all(STDOUT_FILENO, header, sizeof(header)) != 0 ||
                write_all(STDOUT_FILENO, argv[i], size) != 0) return 95;
        }
        if (write_all(STDERR_FILENO, "probe-stderr\n", 13) != 0) return 96;
        return 23;
    }
    if (strcmp(argv[1], "exit") == 0 && argc == 3) {
        (void)write_all(STDOUT_FILENO, "public-output\n", 14);
        return atoi(argv[2]);
    }
    if (strcmp(argv[1], "signal") == 0) {
        (void)write_all(STDOUT_FILENO, "signal-started\n", 15);
        (void)raise(SIGKILL); return 97;
    }
    if (strcmp(argv[1], "resist") == 0 && argc == 4) return late_writer(argv[2], argv[3], -1);
    if (strcmp(argv[1], "orphan") == 0 && argc == 4) {
        int ready[2]; if (pipe(ready) != 0) return 98;
        pid_t child = fork();
        if (child < 0) return 99;
        if (child == 0) { (void)close(ready[0]); _exit(late_writer(argv[2], argv[3], ready[1])); }
        (void)close(ready[1]);
        char value = 0; ssize_t count;
        do { count = read(ready[0], &value, 1); } while (count < 0 && errno == EINTR);
        (void)close(ready[0]);
        if (count != 1 || value != 'R') return 100;
        (void)write_all(STDOUT_FILENO, "leader-exit\n", 12);
        return 0; /* deliberately leave the synchronized, TERM-resistant orphan */
    }
    if (strcmp(argv[1], "flood") == 0) {
        unsigned char bytes[4096]; memset(bytes, 'x', sizeof(bytes));
        for (int i = 0; i < 512; ++i) {
            if (write_all(i % 2 ? STDOUT_FILENO : STDERR_FILENO, bytes, sizeof(bytes)) != 0) return 101;
        }
        return 0;
    }
    if (strcmp(argv[1], "unrelated") == 0 && argc == 3) {
        (void)write_all(STDOUT_FILENO, "ready\n", 6);
        delay(500);
        return mark(argv[2], "unrelated-survived") == 0 ? 0 : 102;
    }
    return 64;
}
