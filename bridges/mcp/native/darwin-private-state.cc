// Experimental, target-built Darwin backend. No Linux approximation or shell ACL parser.
// See PRIMARY-SOURCES.md for the Apple API conventions and the qualification boundary.
#if !defined(__APPLE__) || !defined(__MACH__)
#error This backend must be built with the selected macOS SDK on Darwin.
#endif
#ifndef KTC_SOURCE_SHA256
#error Use the explicit target-native build script to bind the source receipt.
#endif
#include <node_api.h>
#include <sys/acl.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <sys/sysctl.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <initializer_list>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr size_t kMaxPath = 16384;
constexpr size_t kMaxComponents = 256;
constexpr size_t kMaxConfig = 1048576;
constexpr size_t kNoParent = static_cast<size_t>(-1);
const napi_type_tag kStoreTag = {0x4b54434441525731ULL, 0x5052495641544531ULL};

struct Failure { const char* code; };
[[noreturn]] void fail(const char* code) { throw Failure{code}; }
void js(napi_status status) { if (status != napi_ok) fail("darwin_native_binding_error"); }

struct Fd {
  int value = -1;
  Fd() = default;
  explicit Fd(int fd) : value(fd) {}
  ~Fd() { if (value >= 0) (void)::close(value); }
  Fd(const Fd&) = delete;
  Fd& operator=(const Fd&) = delete;
  Fd(Fd&& other) noexcept : value(std::exchange(other.value, -1)) {}
  Fd& operator=(Fd&& other) noexcept {
    if (this != &other) {
      if (value >= 0) (void)::close(value);
      value = std::exchange(other.value, -1);
    }
    return *this;
  }
  void finish() {
    const int fd = std::exchange(value, -1);
    if (fd >= 0 && ::close(fd) != 0) fail("darwin_native_close_uncertain");
  }
};
struct FileSec {
  filesec_t value = filesec_init();
  FileSec() { if (!value) fail("darwin_acl_api_failure"); }
  ~FileSec() { filesec_free(value); }
};
struct Acl {
  acl_t value = nullptr;
  ~Acl() { if (value) (void)acl_free(value); }
};
bool same(const struct stat& a, const struct stat& b) {
  return a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
    (a.st_mode & S_IFMT) == (b.st_mode & S_IFMT);
}
uid_t owner() {
  // No root, set-ID or changing-credential interpretation is supported.
  if (getuid() == 0 || getuid() != geteuid() || getgid() != getegid()) fail("darwin_identity_unqualified");
  // Check THIS process, not a sysctl subprocess or uname under translation.
  // Apple's documented ENOENT convention means native on a kernel without
  // CONFIG_ROSETTA; all other errors/unknown values/short results reject.
  int translated = -1;
  size_t size = sizeof(translated);
  errno = 0;
  const int result = sysctlbyname("sysctl.proc_translated", &translated, &size, nullptr, 0);
  if (result == 0) {
    if (size != sizeof(translated) || translated != 0) fail("darwin_native_architecture_unqualified");
  } else if (result != -1 || errno != ENOENT) fail("darwin_native_architecture_unqualified");
  return geteuid();
}
template<class T> void completedScalar(filesec_t security, filesec_property_t property, T expected) {
  int present = 0;
  T actual{};
  if (filesec_query_property(security, property, &present) != 0 || !present ||
      filesec_get_property(security, property, &actual) != 0 || actual != expected)
    fail("darwin_acl_api_failure");
}
void knownFlags(void* source, void* target, std::initializer_list<acl_flag_t> permitted) {
  acl_flagset_t from = nullptr, to = nullptr;
  if (acl_get_flagset_np(source, &from) != 0 || !from ||
      acl_get_flagset_np(target, &to) != 0 || !to || acl_clear_flags_np(to) != 0)
    fail("darwin_acl_api_failure");
  for (const auto flag : permitted) {
    const int value = acl_get_flag_np(from, flag);
    if (value != 0 && value != 1) fail("darwin_acl_api_failure");
    if (value && acl_add_flag_np(to, flag) != 0) fail("darwin_acl_api_failure");
  }
}
std::vector<char> externalAcl(acl_t acl) {
  const ssize_t size = acl_size(acl);
  if (size <= 0 || size > 16384) fail("darwin_acl_api_failure");
  std::vector<char> bytes(static_cast<size_t>(size));
  if (acl_copy_ext(bytes.data(), acl, size) != size) fail("darwin_acl_api_failure");
  return bytes;
}

struct stat inspect(int fd, bool directory, bool privateObject) {
  FileSec security;
  struct stat st{};
  // This is the supported descriptor-bound operation used by acl_get_fd_np.
  // Libc statx1 can return 0 when growing the ACL buffer failed with ENOMEM.
  // These scalar properties are populated only AFTER that retrieval loop, and
  // are allocation-free in filesec_set_property. Require all three on this NEW
  // filesec before interpreting absent ACL as empty; return code alone is unsafe.
  if (fstatx_np(fd, &st, security.value) != 0) fail("darwin_acl_api_failure");
  completedScalar(security.value, FILESEC_OWNER, st.st_uid);
  completedScalar(security.value, FILESEC_GROUP, st.st_gid);
  completedScalar(security.value, FILESEC_MODE, st.st_mode);
  if (directory ? !S_ISDIR(st.st_mode) : !S_ISREG(st.st_mode)) fail("unsafe_state_path");
  const uid_t uid = owner();
  if (privateObject) {
    if (st.st_uid != uid || (st.st_mode & 0077) != 0 ||
        (st.st_mode & (S_ISUID | S_ISGID)) != 0) fail("state_permissions");
    if (directory && (st.st_mode & 0700) != 0700) fail("state_permissions");
  } else if ((st.st_uid != 0 && st.st_uid != uid) || (st.st_mode & 0022) != 0) {
    // A foreign owner can chmod later, even when its current mode is read-only.
    // Sticky world-writable ancestors are deliberately not treated as immutable.
    fail("darwin_unsafe_ancestor");
  }
  if (!directory && st.st_nlink != 1) fail("darwin_file_identity_unqualified");
  struct statfs fs{};
  if (fstatfs(fd, &fs) != 0 || std::strcmp(fs.f_fstypename, "apfs") != 0 ||
      (fs.f_flags & MNT_LOCAL) == 0 || (fs.f_flags & MNT_UNKNOWNPERMISSIONS) != 0 ||
      (fs.f_owner != 0 && fs.f_owner != uid)) fail("darwin_filesystem_unqualified");
  int present = 0;
  if (filesec_query_property(security.value, FILESEC_ACL, &present) != 0)
    fail("darwin_acl_api_failure");
  if (!present) return st;
  Acl acl;
  if (filesec_get_property(security.value, FILESEC_ACL, &acl.value) != 0 || !acl.value)
    fail("darwin_acl_api_failure");
  // fstatx must return an actual ACL, never a mutation-only remove-ACL sentinel.
  if (static_cast<void*>(acl.value) == _FILESEC_REMOVE_ACL) {
    acl.value = nullptr;
    fail("darwin_acl_api_failure");
  }
  if (acl_valid(acl.value) != 0) fail("darwin_acl_api_failure");
  Acl normalized;
  normalized.value = acl_dup(acl.value);
  if (!normalized.value) fail("darwin_acl_api_failure");
  // acl_valid checks only magic; flag setters do not validate unknown bits.
  // Rebuild documented flags on a COPY, then compare bounded public external
  // representations. This detects unknown flags without private struct access,
  // arbitrary enum-value queries, ACL text parsing, or mutating filesystem ACLs.
  // The tentative ACL_FLAG_DEFER_INHERIT is intentionally not admitted.
  knownFlags(acl.value, normalized.value, {ACL_FLAG_NO_INHERIT});
  constexpr acl_permset_mask_t permissions = ACL_READ_DATA | ACL_WRITE_DATA | ACL_EXECUTE |
    ACL_DELETE | ACL_APPEND_DATA | ACL_DELETE_CHILD | ACL_READ_ATTRIBUTES | ACL_WRITE_ATTRIBUTES |
    ACL_READ_EXTATTRIBUTES | ACL_WRITE_EXTATTRIBUTES | ACL_READ_SECURITY | ACL_WRITE_SECURITY |
    ACL_CHANGE_OWNER | ACL_SYNCHRONIZE;
  for (int index = 0; index <= ACL_MAX_ENTRIES; ++index) {
    acl_entry_t entry = nullptr;
    errno = 0;
    const int result = acl_get_entry(acl.value, index == 0 ? ACL_FIRST_ENTRY : ACL_NEXT_ENTRY, &entry);
    // Apple Libc: success=0, exhaustion=-1/EINVAL. This is NOT Linux's 1/0/-1.
    // The ACL is a private freshly decoded kernel snapshot, never caller-supplied.
    if (result == -1 && errno == EINVAL) {
      if (externalAcl(acl.value) != externalAcl(normalized.value)) fail("darwin_acl_flags_unqualified");
      return st;
    }
    if (result != 0 || !entry || index == ACL_MAX_ENTRIES) fail("darwin_acl_api_failure");
    acl_tag_t tag = ACL_UNDEFINED_TAG;
    if (acl_get_tag_type(entry, &tag) != 0) fail("darwin_acl_api_failure");
    // Reject ALL positive grants, including inherited/inherit-only/owner grants.
    // DENY entries cannot grant access; no UUID/group-membership guess is needed.
    if (tag != ACL_EXTENDED_DENY) fail("darwin_acl_allow_unqualified");
    acl_permset_mask_t rights = 0;
    if (acl_get_permset_mask_np(entry, &rights) != 0) fail("darwin_acl_api_failure");
    if ((rights & ~permissions) != 0) fail("darwin_acl_permissions_unqualified");
    acl_entry_t copy = nullptr;
    if (acl_get_entry(normalized.value, index == 0 ? ACL_FIRST_ENTRY : ACL_NEXT_ENTRY, &copy) != 0 || !copy)
      fail("darwin_acl_api_failure");
    knownFlags(entry, copy, {ACL_ENTRY_INHERITED, ACL_ENTRY_FILE_INHERIT, ACL_ENTRY_DIRECTORY_INHERIT,
      ACL_ENTRY_LIMIT_INHERIT, ACL_ENTRY_ONLY_INHERIT});
  }
  fail("darwin_acl_api_failure");
}

std::vector<std::string> components(const std::string& path) {
  if (path.empty() || path[0] != '/' || path.size() > kMaxPath || path.find('\0') != std::string::npos)
    fail("unsafe_state_path");
  std::vector<std::string> result;
  size_t pos = 1;
  while (pos <= path.size()) {
    const size_t end = path.find('/', pos);
    const std::string part = path.substr(pos, end == std::string::npos ? end : end - pos);
    if (part == "." || part == "..") fail("unsafe_state_path");
    if (!part.empty()) result.push_back(part);
    if (result.size() > kMaxComponents) fail("unsafe_state_path");
    if (end == std::string::npos) break;
    pos = end + 1;
  }
  return result;
}
std::string absolute(const std::vector<std::string>& parts) {
  std::string path;
  for (const auto& part : parts) { path += '/'; path += part; }
  return path.empty() ? "/" : path;
}
void identityAt(int parent, const std::string& name, int fd) {
  struct stat named{}, held{};
  if (fstatat(parent, name.c_str(), &named, AT_SYMLINK_NOFOLLOW) != 0 ||
      fstat(fd, &held) != 0 || !same(named, held)) fail("darwin_namespace_changed");
}
struct Node {
  Fd fd;
  size_t parent;
  std::string name;
  bool privateObject;
};
struct Chain {
  std::vector<Node> nodes;
  size_t leaf = 0;
  std::string path;
  uid_t uid = owner();

  Chain() {
    Fd root(::open("/", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (root.value < 0) fail("darwin_unsafe_ancestor");
    (void)inspect(root.value, true, false);
    nodes.push_back(Node{std::move(root), kNoParent, "", false});
  }
  size_t child(size_t parent, const std::string& name, bool privateObject, bool create, bool allowDeviceChange) {
    const int pfd = nodes[parent].fd.value;
    Fd fd(::openat(pfd, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (fd.value < 0 && errno == ENOENT && create) {
      // Existing parent authority has already been proved. No chmod/adoption repair.
      if (mkdirat(pfd, name.c_str(), 0700) != 0 && errno != EEXIST) fail("darwin_state_creation_failed");
      fd = Fd(::openat(pfd, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    }
    if (fd.value < 0) fail("unsafe_state_path");
    const auto st = inspect(fd.value, true, privateObject);
    struct stat pst{};
    if (fstat(pfd, &pst) != 0 || (!allowDeviceChange && st.st_dev != pst.st_dev))
      fail("darwin_mount_transition_unqualified");
    identityAt(pfd, name, fd.value);
    nodes.push_back(Node{std::move(fd), parent, name, privateObject});
    return nodes.size() - 1;
  }
  void backingFor(size_t visible, const std::string& name) {
    // Modern macOS firmlinks are not S_IFLNK. Verify BOTH protected parent chains
    // and exact reached inode, rather than infer safety from O_NOFOLLOW alone.
    size_t p = child(0, "System", false, false, false);
    p = child(p, "Volumes", false, false, false);
    p = child(p, "Data", false, false, true);
    p = child(p, name, false, false, false);
    struct stat a{}, b{};
    if (fstat(nodes[visible].fd.value, &a) != 0 || fstat(nodes[p].fd.value, &b) != 0 || !same(a, b))
      fail("darwin_firmlink_identity_unqualified");
  }
  void walk(std::vector<std::string> parts, bool create, bool privateLeaf) {
    if (parts.empty()) fail("unsafe_state_path");
    // Only these fixed, protected root aliases are recognized. No realpath(),
    // arbitrary symlink expansion, shell quoting, or PATH lookup is involved.
    if (parts.front() == "var" || parts.front() == "tmp") {
      struct stat st{};
      const std::string alias = parts.front();
      if (fstatat(nodes[0].fd.value, alias.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0)
        fail("unsafe_state_path");
      if (S_ISLNK(st.st_mode)) {
        if (privateLeaf && parts.size() == 1) fail("unsafe_state_path");
        if (st.st_uid != 0 && st.st_uid != uid) fail("darwin_unsafe_ancestor");
        char link[64];
        const ssize_t count = readlinkat(nodes[0].fd.value, alias.c_str(), link, sizeof(link));
        if (count <= 0 || static_cast<size_t>(count) >= sizeof(link)) fail("unsafe_state_path");
        const std::string target(link, static_cast<size_t>(count));
        if (target != "private/" + alias && target != "/private/" + alias) fail("unsafe_state_path");
        parts.insert(parts.begin(), "private");
      }
    }
    size_t p = 0;
    for (size_t i = 0; i < parts.size(); ++i) {
      const bool firmlink = i == 0 && (parts[i] == "Users" || parts[i] == "private");
      const bool dataMount = i == 2 && parts[0] == "System" && parts[1] == "Volumes" && parts[i] == "Data";
      p = child(p, parts[i], privateLeaf && i + 1 == parts.size(), create, firmlink || dataMount);
      if (firmlink) backingFor(p, parts[i]);
    }
    leaf = p;
    path = absolute(parts);
  }
  void verify() const {
    if (uid != owner()) fail("darwin_identity_unqualified");
    for (const auto& node : nodes) {
      (void)inspect(node.fd.value, true, node.privateObject);
      if (node.parent != kNoParent) identityAt(nodes[node.parent].fd.value, node.name, node.fd.value);
    }
  }
};

Fd privateFile(const Chain& chain, const std::string& name, bool create) {
  const int parent = chain.nodes[chain.leaf].fd.value;
  if (!create) {
    struct stat named{};
    if (fstatat(parent, name.c_str(), &named, AT_SYMLINK_NOFOLLOW) != 0 ||
        !S_ISREG(named.st_mode) || named.st_nlink != 1) fail("unsafe_state_path");
  }
  const int flags = (create ? O_RDWR : O_RDONLY) | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK;
  Fd fd;
  if (create) {
    fd = Fd(openat(parent, name.c_str(), flags | O_CREAT | O_EXCL, 0600));
    if (fd.value < 0 && errno == EEXIST) fd = Fd(openat(parent, name.c_str(), flags));
  } else fd = Fd(openat(parent, name.c_str(), flags));
  if (fd.value < 0) fail("unsafe_state_path");
  const auto st = inspect(fd.value, false, true);
  struct stat parentStat{};
  if (fstat(parent, &parentStat) != 0 || st.st_dev != parentStat.st_dev)
    fail("darwin_mount_transition_unqualified");
  identityAt(parent, name, fd.value);
  return fd;
}
bool allowedMember(const std::string& name) {
  for (const char* base : {"inbox.sqlite", "codex.sqlite"}) {
    for (const char* suffix : {"", "-wal", "-shm", "-journal"}) {
      if (name == std::string(base) + suffix) return true;
    }
  }
  return false;
}
struct Member { std::string name; Fd fd; };
std::vector<Member> inventory(const Chain& chain, bool admission) {
  std::vector<Member> retained;
  const int parent = chain.nodes[chain.leaf].fd.value;
  struct stat parentStat{};
  if (fstat(parent, &parentStat) != 0) fail("unsafe_state_path");
  Fd scan(openat(parent, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  if (scan.value < 0) fail("unsafe_state_path");
  DIR* raw = fdopendir(scan.value);
  if (!raw) fail("unsafe_state_path");
  scan.value = -1;
  const auto closeDir = [](DIR* dir) { (void)closedir(dir); };
  std::unique_ptr<DIR, decltype(closeDir)> dir(raw, closeDir);
  size_t count = 0;
  for (;;) {
    errno = 0;
    const dirent* entry = readdir(dir.get());
    if (!entry) { if (errno != 0) fail("unsafe_state_path"); break; }
    const std::string name(entry->d_name);
    if (name == "." || name == "..") continue;
    if (++count > 8 || !allowedMember(name)) fail("darwin_unproven_state_directory");
    struct stat st{};
    if (admission) {
      Fd fd = privateFile(chain, name, false);
      if (fstat(fd.value, &st) != 0) fail("unsafe_state_path");
      retained.push_back(Member{name, std::move(fd)});
    } else {
      // NEVER open/close an extra descriptor for a live DB/WAL/SHM inode:
      // POSIX close can release every process fcntl lock on that inode.
      if (fstatat(parent, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0 ||
          !S_ISREG(st.st_mode) || st.st_dev != parentStat.st_dev || st.st_uid != owner() || (st.st_mode & 0077) != 0 ||
          (st.st_mode & (S_ISUID | S_ISGID)) != 0 || st.st_nlink != 1)
        fail("darwin_file_identity_unqualified");
      // Initial members had descriptor-bound ACL proof. New members are created
      // by pinned SQLite in the immutable-to-other-UID, deny-only private root;
      // they cannot inherit an ALLOW ACL. This is not a post-write ACL repair.
    }
    // A legacy hot rollback journal can contain a super-journal pathname outside
    // this directory. This no-ATTACH host does not import/repair that recovery case.
    if (name.size() >= 8 && name.compare(name.size() - 8, 8, "-journal") == 0 && st.st_size != 0)
      fail("darwin_rollback_recovery_unqualified");
  }
  return retained;
}
using NamespaceId = std::pair<dev_t, ino_t>;
struct Registry { std::mutex mutex; std::set<NamespaceId> active; };
Registry& registry() { static Registry* value = new Registry(); return *value; }
struct Reservation {
  NamespaceId key;
  explicit Reservation(NamespaceId value, const char* busy) : key(value) {
    auto& all = registry(); std::lock_guard<std::mutex> lock(all.mutex);
    if (!all.active.insert(key).second) fail(busy);
  }
  ~Reservation() { auto& all = registry(); std::lock_guard<std::mutex> lock(all.mutex); all.active.erase(key); }
};
struct InactiveGuard {
  std::unique_lock<std::mutex> lock{registry().mutex};
  int fd;
  bool held = false;
  explicit InactiveGuard(const Chain& chain) : fd(chain.nodes[chain.leaf].fd.value) {
    struct stat root{};
    if (fstat(fd, &root) != 0) fail("unsafe_state_path");
    if (registry().active.count(NamespaceId{root.st_dev, root.st_ino}) != 0)
      fail("darwin_live_database_namespace");
    if (flock(fd, LOCK_SH | LOCK_NB) != 0) fail("darwin_live_database_namespace");
    held = true;
  }
  ~InactiveGuard() { if (held) (void)flock(fd, LOCK_UN); }
  void release() { if (held) { (void)flock(fd, LOCK_UN); held = false; } lock.unlock(); }
};
struct Store {
  // Declared FIRST so destruction releases the reservation LAST, after all FDs.
  std::unique_ptr<Reservation> reservation;
  Chain chain;
  Fd database;
  std::vector<Member> admitted;
  std::string name;
  bool closed = false;
  bool closing = false;
  Store(const std::string& path, const std::string& basename) : name(basename) {
    if (name != "inbox.sqlite" && name != "codex.sqlite") fail("unsafe_state_path");
    chain.walk(components(path), true, true);
    const char* busy = name == "inbox.sqlite" ? "consumer_controller_busy" : "codex_controller_busy";
    const int root = chain.nodes[chain.leaf].fd.value;
    struct stat rootStat{};
    if (fstat(root, &rootStat) != 0) fail("unsafe_state_path");
    reservation = std::make_unique<Reservation>(NamespaceId{rootStat.st_dev, rootStat.st_ino}, busy);
    // Independent directory lock also covers separate addon images/processes.
    // It never touches SQLite's regular-file fcntl locks. Qualification must
    // verify Darwin flock-on-directory/close semantics rather than assume Linux.
    if (flock(root, LOCK_EX | LOCK_NB) != 0) {
      if (errno == EWOULDBLOCK || errno == EAGAIN) fail(busy);
      fail("darwin_directory_lock_unqualified");
    }
    // Every extant sidecar is validated BEFORE sqlite can recover or write it.
    admitted = inventory(chain, true);
    database = privateFile(chain, name, true);
  }
  void verify() const {
    if (closed || closing) fail("darwin_closed_state_capability");
    chain.verify();
    (void)inspect(database.value, false, true);
    identityAt(chain.nodes[chain.leaf].fd.value, name, database.value);
    (void)inventory(chain, false);
  }
};

std::vector<napi_value> arguments(napi_env env, napi_callback_info info, size_t wanted) {
  size_t argc = wanted + 1;
  std::vector<napi_value> args(argc);
  js(napi_get_cb_info(env, info, &argc, args.data(), nullptr, nullptr));
  if (argc != wanted) fail("darwin_native_invalid_argument");
  args.resize(wanted);
  return args;
}
std::string stringArg(napi_env env, napi_value value) {
  napi_valuetype type;
  js(napi_typeof(env, value, &type));
  if (type != napi_string) fail("darwin_native_invalid_argument");
  size_t count = 0;
  js(napi_get_value_string_utf16(env, value, nullptr, 0, &count));
  if (count > kMaxPath) fail("unsafe_state_path");
  std::vector<char16_t> wide(count + 1);
  js(napi_get_value_string_utf16(env, value, wide.data(), wide.size(), &count));
  for (size_t i = 0; i < count; ++i) {
    const auto c = wide[i];
    if (c >= 0xd800 && c <= 0xdbff) {
      if (++i >= count || wide[i] < 0xdc00 || wide[i] > 0xdfff) fail("unsafe_state_path");
    } else if (c >= 0xdc00 && c <= 0xdfff) fail("unsafe_state_path");
  }
  js(napi_get_value_string_utf8(env, value, nullptr, 0, &count));
  if (count > kMaxPath) fail("unsafe_state_path");
  std::vector<char> bytes(count + 1);
  js(napi_get_value_string_utf8(env, value, bytes.data(), bytes.size(), &count));
  return std::string(bytes.data(), count);
}
napi_value undefined(napi_env env) { napi_value result; js(napi_get_undefined(env, &result)); return result; }
template<class Fn> napi_value boundary(napi_env env, Fn&& function) {
  try { return function(); }
  catch (const Failure& error) { (void)napi_throw_error(env, error.code, error.code); }
  catch (...) { (void)napi_throw_error(env, "darwin_native_failure", "darwin_native_failure"); }
  return nullptr;
}
Store* storeArg(napi_env env, napi_value value) {
  bool tagged = false;
  js(napi_check_object_type_tag(env, value, &kStoreTag, &tagged));
  if (!tagged) fail("darwin_native_invalid_argument");
  void* data = nullptr;
  js(napi_unwrap(env, value, &data));
  if (!data) fail("darwin_closed_state_capability");
  return static_cast<Store*>(data);
}
napi_value openStore(napi_env env, napi_callback_info info) {
  return boundary(env, [&] {
    const auto args = arguments(env, info, 2);
    auto store = std::make_unique<Store>(stringArg(env, args[0]), stringArg(env, args[1]));
    napi_value result, path;
    js(napi_create_object(env, &result));
    const std::string full = store->chain.path + "/" + store->name;
    js(napi_create_string_utf8(env, full.c_str(), full.size(), &path));
    js(napi_set_named_property(env, result, "databasePath", path));
    js(napi_type_tag_object(env, result, &kStoreTag));
    js(napi_wrap(env, result, store.get(), [](napi_env, void* data, void*) {
      auto* held = static_cast<Store*>(data);
      // GC/environment teardown order is not SQLite-close confirmation. An
      // abandoned OPEN capability deliberately keeps its FDs/namespace lock for
      // process lifetime (including worker teardown). Only explicit closeStore
      // after confirmed DatabaseSync.close may release them. Never unlock in a
      // GC finalizer merely because the JavaScript wrapper became unreachable.
      if (held->closed) delete held;
    }, nullptr, nullptr));
    (void)store.release();
    return result;
  });
}
napi_value verifyStore(napi_env env, napi_callback_info info) {
  return boundary(env, [&] {
    storeArg(env, arguments(env, info, 1)[0])->verify();
    return undefined(env);
  });
}
napi_value closeStore(napi_env env, napi_callback_info info) {
  return boundary(env, [&] {
    Store* store = storeArg(env, arguments(env, info, 1)[0]);
    if (store->closed) return undefined(env);
    if (store->closing) fail("darwin_native_close_uncertain");
    store->closing = true;
    for (auto& member : store->admitted) member.fd.finish();
    store->admitted.clear();
    store->database.finish();
    for (auto& node : store->chain.nodes) node.fd.finish();
    store->chain.nodes.clear();
    store->reservation.reset();
    store->closed = true;
    return undefined(env);
  });
}
napi_value verifyPath(napi_env env, napi_callback_info info) {
  return boundary(env, [&] {
    const auto args = arguments(env, info, 2);
    bool directory = false;
    js(napi_get_value_bool(env, args[1], &directory));
    auto parts = components(stringArg(env, args[0]));
    Chain chain;
    if (directory) chain.walk(std::move(parts), false, true);
    else {
      if (parts.empty()) fail("unsafe_state_path");
      const std::string leaf = parts.back(); parts.pop_back();
      if (parts.empty()) { chain.leaf = 0; chain.path = "/"; }
      else chain.walk(std::move(parts), false, false);
      InactiveGuard inactive(chain);
      Fd fd = privateFile(chain, leaf, false);
    }
    chain.verify();
    return undefined(env);
  });
}
napi_value readConfig(napi_env env, napi_callback_info info) {
  return boundary(env, [&] {
    const auto args = arguments(env, info, 1);
    auto parts = components(stringArg(env, args[0]));
    if (parts.empty()) fail("unsafe_state_path");
    const std::string leaf = parts.back(); parts.pop_back();
    Chain chain;
    if (parts.empty()) { chain.leaf = 0; chain.path = "/"; }
    else chain.walk(std::move(parts), false, false);
    InactiveGuard inactive(chain);
    Fd fd = privateFile(chain, leaf, false);
    struct stat before{};
    if (fstat(fd.value, &before) != 0 || before.st_size < 0 ||
        static_cast<uint64_t>(before.st_size) > kMaxConfig) fail("unsafe_host_configuration");
    std::vector<char> bytes(kMaxConfig + 1);
    size_t total = 0;
    unsigned interrupted = 0;
    for (;;) {
      const ssize_t count = pread(fd.value, bytes.data() + total, bytes.size() - total, static_cast<off_t>(total));
      if (count < 0) { if (errno == EINTR && ++interrupted <= 16) continue; fail("unsafe_host_configuration"); }
      if (count == 0) break;
      total += static_cast<size_t>(count);
      if (total > kMaxConfig) fail("unsafe_host_configuration");
    }
    struct stat after{};
    if (fstat(fd.value, &after) != 0 || !same(before, after) || before.st_size != after.st_size ||
        total != static_cast<uint64_t>(before.st_size) ||
        before.st_mtimespec.tv_sec != after.st_mtimespec.tv_sec ||
        before.st_mtimespec.tv_nsec != after.st_mtimespec.tv_nsec ||
        before.st_ctimespec.tv_sec != after.st_ctimespec.tv_sec ||
        before.st_ctimespec.tv_nsec != after.st_ctimespec.tv_nsec) fail("darwin_namespace_changed");
    chain.verify();
    (void)inspect(fd.value, false, true);
    identityAt(chain.nodes[chain.leaf].fd.value, leaf, fd.value);
    // Close before unlocking: another thread must not acquire this namespace
    // and begin SQLite locking while this independent descriptor is still live.
    fd.finish();
    inactive.release();
    napi_value result;
    js(napi_create_buffer_copy(env, total, bytes.data(), nullptr, &result));
    return result;
  });
}
napi_value init(napi_env env, napi_value exports) {
  return boundary(env, [&] {
    (void)owner();  // Direct require and explicit build-time load obey the same runtime gate.
    napi_property_descriptor properties[] = {
      {"openStore", nullptr, openStore, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"verifyStore", nullptr, verifyStore, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"closeStore", nullptr, closeStore, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"verifyPath", nullptr, verifyPath, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"readConfig", nullptr, readConfig, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    js(napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties));
    napi_value abi;
    js(napi_create_uint32(env, 1, &abi));
    js(napi_set_named_property(env, exports, "abi", abi));
    napi_value source;
    js(napi_create_string_utf8(env, KTC_SOURCE_SHA256, NAPI_AUTO_LENGTH, &source));
    js(napi_set_named_property(env, exports, "sourceSha256", source));
#ifdef KTC_TEST_FILESEC_FAULTS
    napi_value faults;
    js(napi_get_boolean(env, true, &faults));
    js(napi_set_named_property(env, exports, "testFilesecFaults", faults));
#endif
    return exports;
  });
}
}  // namespace
NAPI_MODULE(NODE_GYP_MODULE_NAME, init)
