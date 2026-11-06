// TEST-ONLY addon wrapper. Never distributed as the production native backend.
// These inject API-result conditions, not evidence of a real kernel OOM event.
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
static int fault_fstatx(int fd, struct stat* st, filesec_t security) {
  const char* mode = std::getenv("KTC_DARWIN_FILESEC_FAULT");
  if (!mode) { errno = EINVAL; return -1; }
  if (std::strcmp(mode, "incomplete") == 0) return fstat(fd, st);
  if (std::strcmp(mode, "api-failure") == 0) { errno = ENOMEM; return -1; }
  const int result = fstatx_np(fd, st, security);
  if (result != 0) return result;
  if (std::strcmp(mode, "owner-missing") == 0) return filesec_unset_property(security, FILESEC_OWNER);
  if (std::strcmp(mode, "group-missing") == 0) return filesec_unset_property(security, FILESEC_GROUP);
  if (std::strcmp(mode, "mode-missing") == 0) return filesec_unset_property(security, FILESEC_MODE);
  if (std::strcmp(mode, "owner-mismatch") == 0) {
    const uid_t other = st->st_uid ^ 1;
    return filesec_set_property(security, FILESEC_OWNER, &other);
  }
  errno = EINVAL; return -1;
}
#define KTC_TEST_FILESEC_FAULTS 1
#define fstatx_np fault_fstatx
#include "../darwin-private-state.cc"
