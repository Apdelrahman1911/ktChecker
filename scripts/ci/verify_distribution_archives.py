#!/usr/bin/env python3
"""Compare explicit LOCAL ZIP/USTAR archives with an observed staged installation.

No extraction, installation, chmod, input/source writes, subprocess or network.
Only an explicitly requested NEW JSON report may be written. This checks actual
bounded decoded bytes, names, regular-file identities and executable flags; a
checksum alone is not trust, a build-input binding, compiled-code/runtime proof,
a license assessment, signing, publication or release qualification.

The initial supported I/O interface scope is POSIX descriptor-relative no-follow
I/O. An unavailable platform/format is an explicit NON-SUCCESS, not an assertion
that an archive is corrupt. Before/after inode/stat/path and whole-inventory
rechecks detect observed drift; they are not an atomic snapshot, lock, sandbox,
hard I/O deadline or guarantee against a writer changing and restoring state.

Supported archives are single-volume non-ZIP64 ZIP (stored/raw deflate, Unix
regular/directory modes) and uncompressed USTAR file/directory headers, with only
canonical GNU ././@LongLink filename records additionally allowed. One bounded,
NUL-terminated UTF-8 filename must immediately precede a matching USTAR member;
no alternate short/prefixed name is accepted. GNU ordinary headers, long-link,
PAX and sparse extensions remain unsupported, never silently normalized. The
TAR report format identifier remains "ustar" for its materialized member headers;
its explicit tar_name_profile describes this narrow accepted filename extension.
Filename metadata consumes entry/decoded-byte budgets, not file inventories.
Unrecognized ZIP metadata is explicitly unsupported.
Directory entries may omit implied nonempty parents; every staged directory with
no regular-file descendant must be explicit. No extra directories are accepted.
Paths are literal portable UTF-8/NFC names; depth includes an archive's prefix.
An optional expected prefix binds every archive's single top-level directory;
it does not infer archive filenames, a version, or historical build provenance.
UID/GID, timestamps and non-executable permission differences are not certified.
Report delivery is confirmed only by the CLI exit status and stdout, not by a
file certifying its own creation. A failed write may leave a NEW partial or
unconfirmed report; no existing report is replaced and no cleanup is attempted.
Successful output is read back and checked, but durable storage is not claimed.
"""

from __future__ import annotations

import argparse
from contextlib import ExitStack
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
import time
import unicodedata
import zlib


MIB = 1024 * 1024
CHUNK_BYTES = 64 * 1024
MAX_REPORT_BYTES = 32 * MIB
WINDOWS_DEVICE = re.compile(r"(?:con|prn|aux|nul|conin\$|conout\$|com[1-9¹²³]|lpt[1-9¹²³])\Z", re.I)
SCOPE = ("Observed staged installation versus explicit local archive bytes and "
         "regular-file/executable inventories only; no historical source/build "
         "binding, compiled-code or runtime proof, authenticity, extraction, "
         "installation, license/release qualification or publication.")


class VerificationError(Exception):
    """Fixed diagnostics; never expose an input name until it is validated."""

    def __init__(self, code: str, *, state: str = "failed", path: str | None = None):
        super().__init__(code)
        self.code, self.state, self.path = code, state, path

    def diagnostic(self, *, phase: str | None = None, archive_index: int | None = None) -> dict:
        value = {"code": self.code}
        if self.path is not None:
            value["path"] = self.path
        if phase is not None:
            value["phase"] = phase
        if archive_index is not None:
            value["archive_index"] = archive_index
        return value


@dataclass(frozen=True)
class Limits:
    max_archives: int = 4
    max_files: int = 4096
    max_entries: int = 8192
    max_depth: int = 32
    max_path_bytes: int = 1024
    max_component_bytes: int = 255
    max_file_bytes: int = 256 * MIB
    max_staged_bytes: int = 512 * MIB
    max_archive_bytes: int = 512 * MIB
    max_total_archive_bytes: int = 1024 * MIB
    max_decompressed_bytes: int = 512 * MIB
    max_total_decompressed_bytes: int = 1024 * MIB
    max_zip_central_bytes: int = 16 * MIB
    max_seconds: int = 120

    def validate(self) -> None:
        if type(self) is not Limits:
            raise VerificationError("invalid_limits")
        defaults = asdict(Limits())
        if any(type(value) is not int or value <= 0 or value > defaults[key]
               for key, value in asdict(self).items()):
            raise VerificationError("invalid_limits")


class Budget:
    def __init__(self, limits: Limits):
        limits.validate()
        self.limits = limits
        self.deadline = time.monotonic() + limits.max_seconds
        self.declared_decoded_bytes = self.actual_decoded_bytes = 0
        self.input_read_bytes = 0

    def check(self) -> None:
        if time.monotonic() >= self.deadline:
            raise VerificationError("time_limit", state="unavailable")

    def read(self, count: int) -> None:
        self.check()
        self.input_read_bytes += count

    def reserve_decoded(self, count: int) -> None:
        self.check()
        if count > self.limits.max_decompressed_bytes:
            raise VerificationError("archive_decompressed_bytes_limit")
        self.declared_decoded_bytes += count
        if self.declared_decoded_bytes > self.limits.max_total_decompressed_bytes:
            raise VerificationError("total_decompressed_bytes_limit")

    def decoded(self, count: int) -> None:
        self.check()
        self.actual_decoded_bytes += count
        if self.actual_decoded_bytes > self.limits.max_total_decompressed_bytes:
            raise VerificationError("actual_total_decompressed_bytes_limit")


def platform_available() -> bool:
    return (os.name == "posix" and hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY")
            and hasattr(os, "pread") and os.open in os.supports_dir_fd
            and os.stat in os.supports_dir_fd and os.stat in os.supports_follow_symlinks
            and os.scandir in os.supports_fd)


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_sha256(value: object, budget: Budget) -> str:
    budget.check()
    raw = json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    if len(raw) > MAX_REPORT_BYTES:
        raise VerificationError("inventory_serialization_limit")
    result = sha256(raw)
    budget.check()
    return result


def safe_relative(value: str, limits: Limits) -> str:
    try:
        encoded = value.encode("utf-8", errors="strict")
    except (AttributeError, UnicodeError):
        raise VerificationError("unsafe_member_name") from None
    parts = value.split("/")
    if not encoded or len(encoded) > limits.max_path_bytes or len(parts) > limits.max_depth \
            or unicodedata.normalize("NFC", value) != value \
            or any(character in value for character in '\\:"<>|?*') \
            or any(unicodedata.category(character) in {"Cc", "Cf", "Cs"} for character in value) \
            or any(part in {"", ".", ".."} or part.casefold() == ".git" or part.endswith((".", " "))
                   or WINDOWS_DEVICE.fullmatch(part.split(".", 1)[0].rstrip(" "))
                   or len(part.encode("utf-8")) > limits.max_component_bytes
                   for part in parts):
        raise VerificationError("unsafe_member_name")
    return value


def _host_path(value: Path) -> Path:
    try:
        path = Path(value)
        if ".." in path.parts:
            raise VerificationError("unsafe_input_path")
        path = path.absolute()
        if len(os.fsencode(path)) > 4096 or len(path.parts) > 128:
            raise VerificationError("input_path_limit")
        for part in path.parts[1:]:
            safe_relative(part, Limits())
        return path
    except VerificationError:
        raise
    except (TypeError, ValueError, UnicodeError):
        raise VerificationError("unsafe_input_path") from None


def _fingerprint(value: os.stat_result) -> tuple:
    return (value.st_dev, value.st_ino, value.st_mode, value.st_nlink, value.st_size,
            value.st_mtime_ns, value.st_ctime_ns)


def _binding_identity(value: os.stat_result) -> tuple:
    # Unrelated additions beneath a host ancestor such as /tmp are not staged
    # inventory drift. Its path must still bind the same no-follow inode/mode.
    return value.st_dev, value.st_ino, value.st_mode


def _safe_mode(mode: int, kind: str, *, untyped: bool = False) -> bool:
    expected = stat.S_IFDIR if kind == "directory" else stat.S_IFREG
    if mode < 0 or mode & ~(0o170000 | 0o7777) or stat.S_IFMT(mode) not in ({0, expected} if untyped else {expected}):
        raise VerificationError("archive_link_or_special_mode")
    permissions = stat.S_IMODE(mode)
    if permissions & 0o7022 or not permissions & 0o400 \
            or kind == "directory" and permissions & 0o500 != 0o500:
        raise VerificationError("unsafe_file_mode")
    return bool(permissions & 0o111)


def _regular(value: os.stat_result) -> None:
    if not stat.S_ISREG(value.st_mode) or value.st_nlink != 1:
        raise VerificationError("input_link_or_special_file")
    _safe_mode(value.st_mode, "file")


def _open_child(parent: int, name: str, directory: bool, budget: Budget, *, policy: bool) -> tuple[int, os.stat_result]:
    budget.check()
    before = os.stat(name, dir_fd=parent, follow_symlinks=False)
    if not (stat.S_ISDIR(before.st_mode) if directory else stat.S_ISREG(before.st_mode)):
        raise VerificationError("input_link_or_special_file")
    if policy:
        if directory:
            _safe_mode(before.st_mode, "directory")
        else:
            _regular(before)
    flags = os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
    flags |= os.O_DIRECTORY if directory else getattr(os, "O_NONBLOCK", 0)
    try:
        fd = os.open(name, flags, dir_fd=parent)
    except OSError:
        raise VerificationError("input_open_unavailable", state="unavailable") from None
    try:
        actual = os.fstat(fd)
        if _binding_identity(actual) != _binding_identity(before) \
                or policy and _fingerprint(actual) != _fingerprint(before):
            raise VerificationError("input_changed_during_open", state="stale")
        if not directory:
            _regular(actual)
        budget.check()
        return fd, actual
    except BaseException:
        os.close(fd)
        raise


class HeldPath:
    """Hold every host-path parent; never resolve through a symlink."""

    def __init__(self, path: Path, *, directory: bool, budget: Budget):
        self.path, self.directory, self.budget = _host_path(path), directory, budget
        self.fds: list[int] = []
        self.edges: list[tuple[int, str, os.stat_result]] = []
        try:
            self.budget.check()
            root = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0))
            self.fds.append(root)
            self.root_identity = _binding_identity(os.fstat(root))
            parts = self.path.parts[1:]
            for index, name in enumerate(parts):
                parent = self.fds[-1]
                final = index == len(parts) - 1
                fd, before = _open_child(parent, name, directory if final else True, budget, policy=final)
                self.edges.append((parent, name, before))
                self.fds.append(fd)
            self.fd = self.fds[-1]
            self.initial = os.fstat(self.fd)
            if not parts and not directory:
                raise VerificationError("input_not_regular_file")
            if directory:
                _safe_mode(self.initial.st_mode, "directory")
            else:
                _regular(self.initial)
            self.check_binding()
        except BaseException:
            self.close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_arguments):
        self.close()

    def close(self) -> None:
        for fd in reversed(self.fds):
            os.close(fd)
        self.fds.clear()

    def check_binding(self) -> None:
        self.budget.check()
        try:
            if _binding_identity(os.fstat(self.fds[0])) != self.root_identity:
                raise VerificationError("input_path_binding_drift", state="stale")
            for parent, name, expected in self.edges:
                actual = os.stat(name, dir_fd=parent, follow_symlinks=False)
                if _binding_identity(actual) != _binding_identity(expected):
                    raise VerificationError("input_path_binding_drift", state="stale")
            if _fingerprint(os.fstat(self.fd)) != _fingerprint(self.initial):
                raise VerificationError("input_stat_drift", state="stale")
            if self.edges:
                parent, name, _ = self.edges[-1]
                if _fingerprint(os.stat(name, dir_fd=parent, follow_symlinks=False)) != _fingerprint(self.initial):
                    raise VerificationError("input_leaf_binding_drift", state="stale")
        except OSError:
            raise VerificationError("input_binding_recheck_unavailable", state="stale") from None
        self.budget.check()

    def read_exact(self, offset: int, count: int) -> bytes:
        self.budget.check()
        if self.directory or offset < 0 or count < 0 or offset + count > self.initial.st_size:
            raise VerificationError("archive_truncated_or_invalid_extent")
        result = bytearray()
        while len(result) < count:
            self.budget.check()
            if _fingerprint(os.fstat(self.fd)) != _fingerprint(self.initial):
                raise VerificationError("archive_stat_drift", state="stale")
            raw = os.pread(self.fd, min(CHUNK_BYTES, count - len(result)), offset + len(result))
            self.budget.read(len(raw))
            if not raw:
                raise VerificationError("archive_short_read", state="stale")
            result.extend(raw)
            if _fingerprint(os.fstat(self.fd)) != _fingerprint(self.initial):
                raise VerificationError("archive_stat_drift", state="stale")
        return bytes(result)

    def digest(self) -> str:
        self.check_binding()
        result = hashlib.sha256()
        position = 0
        while position < self.initial.st_size:
            raw = self.read_exact(position, min(CHUNK_BYTES, self.initial.st_size - position))
            result.update(raw)
            position += len(raw)
        self.check_binding()
        return result.hexdigest()


class Namespace:
    def __init__(self, limits: Limits, *, archive: bool):
        self.limits, self.maximum = limits, limits.max_entries + int(archive)
        self.nodes: dict[str, tuple[str, str]] = {}
        self.explicit: set[str] = set()

    def add(self, path: str, kind: str) -> None:
        safe_relative(path, self.limits)
        if path in self.explicit:
            raise VerificationError("duplicate_member_name")
        self.explicit.add(path)
        parts = path.split("/")
        for index in range(1, len(parts) + 1):
            current = "/".join(parts[:index])
            current_kind = kind if index == len(parts) else "directory"
            key = current.casefold()
            old = self.nodes.get(key)
            if old is not None and old != (current, current_kind):
                raise VerificationError("member_case_or_file_directory_alias")
            self.nodes[key] = current, current_kind
            if len(self.nodes) > self.maximum:
                raise VerificationError("expanded_namespace_entry_limit")


@dataclass(frozen=True)
class Snapshot:
    files: tuple[dict, ...]
    directories: tuple[str, ...]
    empty_directories: tuple[str, ...]
    fingerprints: dict[str, tuple]
    total_bytes: int
    sha256: str


def _hash_staged(fd: int, initial: os.stat_result, budget: Budget) -> str:
    _regular(initial)
    if initial.st_size > budget.limits.max_file_bytes:
        raise VerificationError("staged_file_bytes_limit")
    result = hashlib.sha256()
    count = 0
    while True:
        budget.check()
        raw = os.read(fd, min(CHUNK_BYTES, initial.st_size - count + 1))
        budget.read(len(raw))
        if not raw:
            break
        count += len(raw)
        if count > initial.st_size:
            raise VerificationError("staged_file_size_drift", state="stale")
        result.update(raw)
    if count != initial.st_size or _fingerprint(os.fstat(fd)) != _fingerprint(initial):
        raise VerificationError("staged_file_stat_or_size_drift", state="stale")
    budget.check()
    return result.hexdigest()


def inventory(installation: HeldPath, budget: Budget) -> Snapshot:
    installation.check_binding()
    files: list[dict] = []
    directories: list[str] = []
    fingerprints: dict[str, tuple] = {}
    inodes: set[tuple[int, int]] = set()
    names = Namespace(budget.limits, archive=False)
    entries = total_bytes = 0

    def walk(fd: int, relative: str) -> None:
        nonlocal entries, total_bytes
        budget.check()
        before = os.fstat(fd)
        _safe_mode(before.st_mode, "directory")
        fingerprints[relative] = _fingerprint(before)
        children = []
        with os.scandir(fd) as scan:
            for child in scan:
                budget.check()
                entries += 1
                if entries > budget.limits.max_entries:
                    raise VerificationError("staged_entry_limit")
                path = child.name if not relative else relative + "/" + child.name
                safe_relative(path, budget.limits)
                children.append((child.name, path))
        if _fingerprint(os.fstat(fd)) != _fingerprint(before):
            raise VerificationError("staged_directory_listing_drift", state="stale")
        for name, path in sorted(children):
            budget.check()
            item = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if stat.S_ISDIR(item.st_mode):
                names.add(path, "directory")
                directories.append(path)
                child_fd, actual = _open_child(fd, name, True, budget, policy=True)
                try:
                    if _fingerprint(actual) != _fingerprint(item):
                        raise VerificationError("staged_directory_changed_before_open", state="stale")
                    walk(child_fd, path)
                    if _fingerprint(os.stat(name, dir_fd=fd, follow_symlinks=False)) != _fingerprint(actual):
                        raise VerificationError("staged_directory_binding_drift", state="stale")
                finally:
                    os.close(child_fd)
            else:
                _regular(item)
                names.add(path, "file")
                if len(files) >= budget.limits.max_files:
                    raise VerificationError("staged_file_count_limit")
                total_bytes += item.st_size
                if total_bytes > budget.limits.max_staged_bytes:
                    raise VerificationError("staged_total_bytes_limit")
                child_fd, actual = _open_child(fd, name, False, budget, policy=True)
                try:
                    if _fingerprint(actual) != _fingerprint(item):
                        raise VerificationError("staged_file_changed_before_open", state="stale")
                    identity = actual.st_dev, actual.st_ino
                    if identity in inodes:
                        raise VerificationError("staged_aliased_file_identity")
                    inodes.add(identity)
                    digest = _hash_staged(child_fd, actual, budget)
                    if _fingerprint(os.stat(name, dir_fd=fd, follow_symlinks=False)) != _fingerprint(actual):
                        raise VerificationError("staged_file_binding_drift", state="stale")
                    fingerprints[path] = _fingerprint(actual)
                    files.append({"path": path, "bytes": actual.st_size, "executable": bool(actual.st_mode & 0o111),
                                  "sha256": digest})
                finally:
                    os.close(child_fd)
        if _fingerprint(os.fstat(fd)) != _fingerprint(before):
            raise VerificationError("staged_directory_walk_drift", state="stale")

    walk(installation.fd, "")
    if not files:
        raise VerificationError("staged_no_regular_files")
    files.sort(key=lambda row: row["path"])
    directories.sort()
    nonempty = set()
    for row in files:
        parts = row["path"].split("/")
        nonempty.update("/".join(parts[:index]) for index in range(1, len(parts)))
    empty = sorted(set(directories) - nonempty)
    public = {"files": files, "directories": directories, "empty_directories": empty}
    installation.check_binding()
    return Snapshot(tuple(files), tuple(directories), tuple(empty), fingerprints, total_bytes,
                    canonical_sha256(public, budget))


@dataclass(frozen=True)
class Member:
    name: str
    kind: str
    size: int
    executable: bool
    data_offset: int
    data_bytes: int
    method: int = 0
    crc32: int | None = None


def _member_name(raw: bytes, *, directory: bool, utf8: bool, limits: Limits) -> str:
    try:
        name = raw.decode("utf-8" if utf8 else "ascii", errors="strict")
    except UnicodeError:
        raise VerificationError("unsupported_member_name_encoding", state="unsupported") from None
    if directory:
        name = name[:-1] if name.endswith("/") else name
    elif name.endswith("/"):
        raise VerificationError("file_name_has_directory_suffix")
    return safe_relative(name, limits)


def _zip_extras(raw: bytes, *, central: bool) -> None:
    position = 0
    seen = set()
    while position < len(raw):
        if len(raw) - position < 4:
            raise VerificationError("zip_malformed_extra_field")
        tag, size = struct.unpack_from("<HH", raw, position)
        position += 4
        if tag in seen or position + size > len(raw):
            raise VerificationError("zip_malformed_or_duplicate_extra_field")
        seen.add(tag)
        value = raw[position:position + size]
        position += size
        if tag == 0x0001:
            raise VerificationError("unsupported_zip64", state="unsupported")
        if tag == 0x5455:
            if not value or value[0] & ~7:
                raise VerificationError("zip_malformed_timestamp_extra")
            expected = 1 + 4 * (value[0] & 7).bit_count()
            # Central extended timestamps may retain flags for local-only times.
            if len(value) != expected and not (central and value[0] & 1 and len(value) == 5):
                raise VerificationError("zip_malformed_timestamp_extra")
        elif tag == 0x000A:
            if len(value) != 32 or value[:4] != b"\0" * 4 or value[4:8] != b"\x01\x00\x18\x00":
                raise VerificationError("zip_malformed_ntfs_timestamp_extra")
        else:
            # Unicode-path overrides, ownership/link extensions and unknown
            # metadata are not a second authority for the literal member name.
            raise VerificationError("unsupported_zip_extra_field", state="unsupported")


def zip_members(archive: HeldPath, budget: Budget) -> list[Member]:
    size = archive.initial.st_size
    if size < 22:
        raise VerificationError("zip_missing_end_record")
    tail_start = max(0, size - (22 + 65535))
    tail = archive.read_exact(tail_start, size - tail_start)
    candidates = []
    position = 0
    while True:
        budget.check()
        position = tail.find(b"PK\x05\x06", position)
        if position < 0:
            break
        if position + 22 <= len(tail):
            end = struct.unpack_from("<4s4H2LH", tail, position)
            if position + 22 + end[-1] == len(tail):
                candidates.append((tail_start + position, end))
        position += 1
    if len(candidates) != 1:
        raise VerificationError("zip_missing_or_ambiguous_end_record")
    end_offset, end = candidates[0]
    _, disk, central_disk, disk_count, count, central_size, central_offset, _ = end
    if disk or central_disk or disk_count != count:
        raise VerificationError("unsupported_zip_multivolume", state="unsupported")
    if count == 0xFFFF or central_size == 0xFFFFFFFF or central_offset == 0xFFFFFFFF:
        raise VerificationError("unsupported_zip64", state="unsupported")
    if not count or count > budget.limits.max_entries:
        raise VerificationError("zip_member_count_limit_or_empty")
    if central_size > budget.limits.max_zip_central_bytes:
        raise VerificationError("zip_central_directory_bytes_limit")
    if central_offset + central_size != end_offset:
        if end_offset >= 20 and archive.read_exact(end_offset - 20, 4) == b"PK\x06\x07":
            raise VerificationError("unsupported_zip64", state="unsupported")
        raise VerificationError("zip_unaccounted_central_or_end_bytes")
    position = central_offset
    rows = []
    total = regular_count = 0
    for _ in range(count):
        budget.check()
        if position + 46 > end_offset:
            raise VerificationError("zip_central_count_mismatch")
        header = struct.unpack("<4s6H3L5H2L", archive.read_exact(position, 46))
        (signature, made, needed, flags, method, mod_time, mod_date, crc, compressed, expanded,
         name_size, extra_size, comment_size, member_disk, internal, external, local_offset) = header
        if signature != b"PK\x01\x02":
            raise VerificationError("zip_central_count_mismatch")
        if member_disk:
            raise VerificationError("unsupported_zip_multivolume", state="unsupported")
        if expanded == 0xFFFFFFFF or compressed == 0xFFFFFFFF or local_offset == 0xFFFFFFFF or needed >= 45:
            raise VerificationError("unsupported_zip64_or_version", state="unsupported")
        if needed not in {10, 20} or flags & ~0x0808 or method not in {0, 8}:
            raise VerificationError("unsupported_zip_flags_compression_or_version", state="unsupported")
        if made >> 8 != 3 or external & 0xFFFF & ~0x0037 or internal & ~1:
            raise VerificationError("unsupported_zip_mode_or_attributes", state="unsupported")
        mode = external >> 16
        directory = stat.S_ISDIR(mode)
        kind = "directory" if directory else "file"
        executable = _safe_mode(mode, kind)
        if not directory:
            regular_count += 1
            if regular_count > budget.limits.max_files:
                raise VerificationError("archive_regular_file_count_limit")
        if bool(external & 0x10) != directory:
            raise VerificationError("zip_directory_attribute_mismatch")
        if expanded > budget.limits.max_file_bytes:
            raise VerificationError("archive_member_bytes_limit")
        if directory and expanded:
            raise VerificationError("archive_directory_has_data")
        total += expanded
        if total > budget.limits.max_decompressed_bytes:
            raise VerificationError("archive_decompressed_bytes_limit")
        end_position = position + 46 + name_size + extra_size + comment_size
        if not name_size or end_position > end_offset:
            raise VerificationError("zip_central_extent_mismatch")
        raw_name = archive.read_exact(position + 46, name_size)
        name = _member_name(raw_name, directory=directory, utf8=bool(flags & 0x0800), limits=budget.limits)
        if directory != raw_name.endswith(b"/"):
            raise VerificationError("zip_directory_name_type_mismatch")
        _zip_extras(archive.read_exact(position + 46 + name_size, extra_size), central=True)
        rows.append((name, kind, executable, header, raw_name))
        position = end_position
    if position != central_offset + central_size:
        raise VerificationError("zip_central_count_mismatch")
    budget.reserve_decoded(total)
    members = []
    extents = []
    for name, kind, executable, header, raw_name in rows:
        budget.check()
        (_, _, needed, flags, method, mod_time, mod_date, crc, compressed, expanded,
         _, _, _, _, _, _, offset) = header
        if offset + 30 > central_offset:
            raise VerificationError("zip_local_extent_mismatch")
        local = struct.unpack("<4s5H3L2H", archive.read_exact(offset, 30))
        signature, local_needed, local_flags, local_method, local_time, local_date, local_crc, local_compressed, local_expanded, name_size, extra_size = local
        if signature != b"PK\x03\x04" or (local_needed, local_flags, local_method, local_time, local_date) != (needed, flags, method, mod_time, mod_date):
            raise VerificationError("zip_local_central_header_mismatch")
        data_offset = offset + 30 + name_size + extra_size
        if data_offset + compressed > central_offset or name_size != len(raw_name):
            raise VerificationError("zip_local_extent_mismatch")
        if archive.read_exact(offset + 30, name_size) != raw_name:
            raise VerificationError("zip_local_central_name_mismatch")
        _zip_extras(archive.read_exact(offset + 30 + name_size, extra_size), central=False)
        extent_end = data_offset + compressed
        if flags & 8:
            if any(actual not in {0, expected} for actual, expected in
                   ((local_crc, crc), (local_compressed, compressed), (local_expanded, expanded))):
                raise VerificationError("zip_local_central_size_crc_mismatch")
            if extent_end + 12 > central_offset:
                raise VerificationError("zip_truncated_data_descriptor")
            first = archive.read_exact(extent_end, 12)
            expected_descriptor = (crc, compressed, expanded)
            lengths = []
            if struct.unpack("<3L", first) == expected_descriptor:
                lengths.append(12)
            if first[:4] == b"PK\x07\x08" and extent_end + 16 <= central_offset \
                    and struct.unpack("<3L", archive.read_exact(extent_end + 4, 12)) == expected_descriptor:
                lengths.append(16)
            if len(lengths) > 1:
                raise VerificationError("unsupported_zip_ambiguous_descriptor", state="unsupported")
            if not lengths:
                raise VerificationError("zip_data_descriptor_mismatch")
            extent_end += lengths[0]
        elif (local_crc, local_compressed, local_expanded) != (crc, compressed, expanded):
            raise VerificationError("zip_local_central_size_crc_mismatch")
        members.append(Member(name, kind, expanded, executable, data_offset, compressed, method, crc))
        extents.append((offset, extent_end))
    cursor = 0
    for start, end in sorted(extents):
        if start != cursor or end <= start:
            raise VerificationError("zip_overlapping_or_unaccounted_local_bytes")
        cursor = end
    if cursor != central_offset:
        raise VerificationError("zip_unaccounted_local_bytes")
    return members


def _tar_number(raw: bytes) -> int:
    if raw and raw[0] & 0x80:
        raise VerificationError("unsupported_tar_numeric_encoding", state="unsupported")
    value = raw.strip(b"\0 ")
    if not value or any(character not in b"01234567" for character in value):
        if not value:
            return 0
        raise VerificationError("tar_invalid_octal_number")
    return int(value, 8)


def _tar_text(raw: bytes) -> bytes:
    value, separator, padding = raw.partition(b"\0")
    if separator and any(padding):
        raise VerificationError("tar_nonzero_name_padding")
    return value


def tar_members(archive: HeldPath, budget: Budget) -> list[Member]:
    size = archive.initial.st_size
    if size < 1024 or size % 512:
        raise VerificationError("tar_truncated_or_unaligned")
    position = 0
    members = []
    total = regular_count = entries = 0
    pending_name = None
    while position < size:
        budget.check()
        raw = archive.read_exact(position, 512)
        if raw == b"\0" * 512:
            if pending_name is not None:
                raise VerificationError("tar_dangling_long_name")
            if position + 1024 > size or archive.read_exact(position + 512, 512) != b"\0" * 512:
                raise VerificationError("tar_missing_second_end_block")
            position += 1024
            while position < size:
                extra = archive.read_exact(position, min(CHUNK_BYTES, size - position))
                if any(extra):
                    raise VerificationError("tar_payload_after_terminator")
                position += len(extra)
            if not members:
                raise VerificationError("tar_empty_archive")
            budget.reserve_decoded(total)
            return members
        # Count physical extension headers too; they never become extra files.
        if entries >= budget.limits.max_entries:
            raise VerificationError("tar_member_count_limit")
        entries += 1
        flag = raw[156:157]
        long_header = flag == b"L" and raw[257:265] == b"ustar  \0"
        if (raw[257:263] != b"ustar\0" or raw[263:265] != b"00") and not long_header:
            raise VerificationError("unsupported_tar_format", state="unsupported")
        expected_checksum = _tar_number(raw[148:156])
        if sum(raw[:148]) + 8 * 32 + sum(raw[156:]) != expected_checksum:
            raise VerificationError("tar_header_checksum_mismatch")
        if flag in {b"x", b"g", b"K", b"S"} or flag == b"L" and not long_header:
            raise VerificationError("unsupported_tar_extension_or_sparse", state="unsupported")
        if flag not in {b"0", b"\0", b"5"} and not long_header:
            raise VerificationError("tar_link_or_special_member")
        if long_header and pending_name is not None:
            raise VerificationError("tar_repeated_long_name")
        kind = "directory" if flag == b"5" else "file"
        if kind == "file" and not long_header:
            regular_count += 1
            if regular_count > budget.limits.max_files:
                raise VerificationError("archive_regular_file_count_limit")
        mode = _tar_number(raw[100:108])
        executable = _safe_mode(mode, kind, untyped=True)
        _tar_number(raw[108:116])
        _tar_number(raw[116:124])
        _tar_number(raw[136:148])
        if _tar_text(raw[157:257]) or _tar_number(raw[329:337]) or _tar_number(raw[337:345]):
            raise VerificationError("tar_link_or_device_metadata")
        _tar_text(raw[265:297])
        _tar_text(raw[297:329])
        if any(raw[500:512]):
            raise VerificationError("tar_nonzero_header_padding")
        expanded = _tar_number(raw[124:136])
        if expanded > budget.limits.max_file_bytes:
            raise VerificationError("archive_member_bytes_limit")
        # At most one optional directory slash plus one required terminating NUL.
        if long_header and not 2 <= expanded <= budget.limits.max_path_bytes + 2:
            raise VerificationError("tar_long_name_bytes_limit")
        if kind == "directory" and expanded:
            raise VerificationError("archive_directory_has_data")
        total += expanded
        if total > budget.limits.max_decompressed_bytes:
            raise VerificationError("archive_decompressed_bytes_limit")
        raw_name = _tar_text(raw[:100])
        if long_header:
            # No GNU sparse/atime/offset fields or USTAR prefix interpretation.
            if raw_name != b"././@LongLink" or any(raw[345:500]):
                raise VerificationError("tar_invalid_gnu_long_name_header")
        else:
            prefix = _tar_text(raw[345:500])
            if pending_name is not None:
                # Do not accept a conflicting alternate short/prefixed name.
                # The 100-byte wire prefix may split a UTF-8 codepoint; only the
                # complete long name is decoded, strictly, by _member_name.
                if prefix or raw_name != pending_name[:100]:
                    raise VerificationError("tar_long_name_header_mismatch")
                raw_name, pending_name = pending_name, None
            elif prefix:
                raw_name = prefix + b"/" + raw_name
            name = _member_name(raw_name, directory=kind == "directory", utf8=True, limits=budget.limits)
        data_offset = position + 512
        padding = (-expanded) % 512
        position = data_offset + expanded + padding
        if position > size:
            raise VerificationError("tar_member_extent_truncated")
        if padding and any(archive.read_exact(data_offset + expanded, padding)):
            raise VerificationError("tar_nonzero_member_padding")
        if long_header:
            payload = archive.read_exact(data_offset, expanded)
            budget.decoded(expanded)
            if not payload.endswith(b"\0") or b"\0" in payload[:-1]:
                raise VerificationError("tar_invalid_long_name_payload")
            pending_name = payload[:-1]
        else:
            members.append(Member(name, kind, expanded, executable, data_offset, expanded))
    if pending_name is not None:
        raise VerificationError("tar_dangling_long_name")
    raise VerificationError("tar_missing_terminator")


def validate_members(members: list[Member], snapshot: Snapshot, budget: Budget) -> tuple[str, set[str]]:
    namespace = Namespace(budget.limits, archive=True)
    prefix = None
    expected = {row["path"]: row for row in snapshot.files}
    observed = set()
    explicit_directories = set()
    for member in members:
        budget.check()
        namespace.add(member.name, member.kind)
        parts = member.name.split("/")
        if prefix is None:
            prefix = parts[0]
        if parts[0] != prefix:
            raise VerificationError("archive_multiple_prefixes")
        relative = "/".join(parts[1:])
        if member.kind == "directory":
            if relative and relative not in snapshot.directories:
                raise VerificationError("archive_extra_directory", path=relative)
            explicit_directories.add(relative)
            continue
        if not relative:
            raise VerificationError("archive_regular_file_at_prefix")
        if relative not in expected:
            raise VerificationError("archive_extra_regular_file", path=relative)
        row = expected[relative]
        if member.size != row["bytes"]:
            raise VerificationError("archive_file_size_mismatch", path=relative)
        if member.executable != row["executable"]:
            raise VerificationError("archive_executable_mismatch", path=relative)
        observed.add(relative)
    missing = expected.keys() - observed
    if missing:
        raise VerificationError("archive_missing_regular_file", path=sorted(missing)[0])
    missing_directories = set(snapshot.empty_directories) - explicit_directories
    if missing_directories:
        raise VerificationError("archive_missing_explicit_empty_directory", path=sorted(missing_directories)[0])
    if prefix is None:
        raise VerificationError("archive_empty")
    return prefix, explicit_directories


def hash_member(archive: HeldPath, member: Member, budget: Budget) -> tuple[str, int]:
    result = hashlib.sha256()
    count = crc = 0

    def accept(raw: bytes) -> None:
        nonlocal count, crc
        budget.decoded(len(raw))
        count += len(raw)
        if count > member.size or count > budget.limits.max_file_bytes:
            raise VerificationError("archive_actual_member_bytes_mismatch_or_limit")
        result.update(raw)
        crc = zlib.crc32(raw, crc)

    position = 0
    if member.method == 0:
        if member.data_bytes != member.size:
            raise VerificationError("zip_stored_size_mismatch")
        while position < member.data_bytes:
            raw = archive.read_exact(member.data_offset + position, min(CHUNK_BYTES, member.data_bytes - position))
            position += len(raw)
            accept(raw)
    else:
        decompressor = zlib.decompressobj(-15)
        pending = b""
        try:
            while position < member.data_bytes or pending:
                budget.check()
                if not pending:
                    pending = archive.read_exact(member.data_offset + position,
                                                 min(CHUNK_BYTES, member.data_bytes - position))
                    position += len(pending)
                previous = pending
                raw = decompressor.decompress(pending, min(CHUNK_BYTES, member.size - count + 1))
                pending = decompressor.unconsumed_tail
                accept(raw)
                if decompressor.eof:
                    if decompressor.unused_data or pending or position != member.data_bytes:
                        raise VerificationError("zip_trailing_compressed_data")
                    break
                if not raw and pending == previous:
                    raise VerificationError("zip_invalid_compressed_stream")
            # No unbounded flush(): expose any remaining output through the same
            # declared-size-plus-one cap and require the real deflate EOF.
            if not decompressor.eof:
                accept(decompressor.decompress(b"", min(CHUNK_BYTES, member.size - count + 1)))
            if not decompressor.eof or decompressor.unused_data or decompressor.unconsumed_tail:
                raise VerificationError("zip_truncated_compressed_stream")
        except zlib.error:
            raise VerificationError("zip_invalid_compressed_stream") from None
    if count != member.size:
        raise VerificationError("archive_actual_member_size_mismatch")
    if member.crc32 is not None and crc != member.crc32:
        raise VerificationError("zip_crc_mismatch")
    budget.check()
    return result.hexdigest(), count


def _archive_format(path: Path) -> str:
    name = path.name.lower()
    if name.endswith(".zip"):
        return "zip32"
    if name.endswith(".tar"):
        return "ustar"
    if name.endswith((".tar.gz", ".tgz", ".tar.bz2", ".tar.xz")):
        raise VerificationError("unsupported_tar_compression", state="unsupported")
    raise VerificationError("unsupported_archive_format", state="unsupported")


def verify_archive(archive: HeldPath, snapshot: Snapshot, budget: Budget) -> dict:
    archive.check_binding()
    compressed_sha = archive.digest()
    format_name = _archive_format(archive.path)
    if format_name == "ustar":
        magic = archive.read_exact(0, min(6, archive.initial.st_size))
        if magic.startswith((b"\x1f\x8b", b"BZh", b"\xfd7zXZ\0", b"\x28\xb5\x2f\xfd")):
            raise VerificationError("unsupported_tar_compression", state="unsupported")
    members = zip_members(archive, budget) if format_name == "zip32" else tar_members(archive, budget)
    prefix, explicit_directories = validate_members(members, snapshot, budget)
    expected = {row["path"]: row for row in snapshot.files}
    observed = []
    for member in members:
        budget.check()
        digest, count = hash_member(archive, member, budget)
        if member.kind == "directory":
            if count:
                raise VerificationError("archive_directory_has_data")
            continue
        relative = member.name[len(prefix) + 1:]
        if digest != expected[relative]["sha256"]:
            raise VerificationError("archive_file_sha256_mismatch", path=relative)
        observed.append({"path": relative, "bytes": count, "executable": member.executable, "sha256": digest})
    observed.sort(key=lambda row: row["path"])
    if observed != list(snapshot.files):
        raise VerificationError("archive_actual_inventory_mismatch")
    if archive.digest() != compressed_sha:
        raise VerificationError("archive_content_drift", state="stale")
    archive.check_binding()
    return {"format": format_name, "sha256": compressed_sha, "bytes": archive.initial.st_size,
            **({"tar_name_profile": "ustar_with_optional_gnu_longname_v1"} if format_name == "ustar" else {}),
            "prefix": prefix, "member_count": len(members), "regular_file_count": len(observed),
            "decoded_file_bytes": sum(row["bytes"] for row in observed),
            "actual_file_inventory_sha256": canonical_sha256(observed, budget),
            "matched_staged_inventory_sha256": snapshot.sha256,
            "explicit_directory_count": len(explicit_directories),
            "explicit_directories_sha256": canonical_sha256(sorted(explicit_directories), budget),
            "empty_directories_policy": "all_staged_directories_without_regular_descendants_are_explicit",
            "input_recheck": "unchanged", "state": "checked_against_initial_staged_inventory"}


def verify(installation_path: Path, archive_paths: list[Path], *, limits: Limits | None = None,
           expected_prefix: str | None = None) -> dict:
    limits = limits if limits is not None else Limits()
    result = {"schema_version": 1, "evidence_kind": "local_distribution_archive_integrity", "scope": SCOPE,
              "recorded_at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "state": "failed",
              "platform_scope": "POSIX descriptor-relative no-follow reads; no Windows qualification",
              "limits": asdict(limits) if type(limits) is Limits else None,
              "chunk_bytes": CHUNK_BYTES, "max_report_bytes": MAX_REPORT_BYTES,
              "installation": None, "archives": [], "expected_prefix": None,
              "errors": [], "input_recheck": "not_completed",
              "atomic_snapshot_or_hostile_writer_containment": False, "hard_deadline_enforced": False,
              "checksum_alone_is_trust": False, "compiled_code_or_runtime_proof": False,
              "historical_build_source_binding": False, "license_review": "not_assessed", "release_qualified": False,
              "extraction": "not_performed", "installation_performed": False,
              "signing": "not_performed", "publication": "not_performed", "raw_contents_emitted": False}
    budget = None
    phase = "arguments"
    archive_index = None
    try:
        if type(limits) is not Limits:
            raise VerificationError("invalid_limits")
        budget = Budget(limits)
        if expected_prefix is not None:
            if type(expected_prefix) is not str or "/" in expected_prefix:
                raise VerificationError("invalid_expected_archive_prefix")
            try:
                safe_relative(expected_prefix, limits)
            except VerificationError:
                raise VerificationError("invalid_expected_archive_prefix") from None
            result["expected_prefix"] = expected_prefix
        if not platform_available():
            raise VerificationError("platform_no_follow_unavailable", state="unavailable")
        if not isinstance(archive_paths, (list, tuple)) or not 0 < len(archive_paths) <= limits.max_archives:
            raise VerificationError("archive_argument_count_limit_or_empty")
        installation_path = _host_path(installation_path)
        paths = [_host_path(path) for path in archive_paths]
        if len(set(paths)) != len(paths):
            raise VerificationError("duplicate_archive_argument")
        with ExitStack() as stack:
            phase = "installation"
            installation = stack.enter_context(HeldPath(installation_path, directory=True, budget=budget))
            initial = inventory(installation, budget)
            result["installation"] = {"files": list(initial.files), "directories": list(initial.directories),
                                      "empty_directories": list(initial.empty_directories),
                                      "file_count": len(initial.files), "entry_count": len(initial.files) + len(initial.directories),
                                      "file_bytes": initial.total_bytes, "inventory_sha256": initial.sha256,
                                      "identity_scope": "Observed staged bytes now, not historical compiled build inputs."}
            phase = "archives"
            held = []
            identities = set()
            total_archive_bytes = 0
            for archive_index, path in enumerate(paths, 1):
                archive = stack.enter_context(HeldPath(path, directory=False, budget=budget))
                if archive.initial.st_size > limits.max_archive_bytes:
                    raise VerificationError("archive_compressed_bytes_limit")
                total_archive_bytes += archive.initial.st_size
                if total_archive_bytes > limits.max_total_archive_bytes:
                    raise VerificationError("total_archive_compressed_bytes_limit")
                identity = archive.initial.st_dev, archive.initial.st_ino
                if identity in identities:
                    raise VerificationError("aliased_archive_identity")
                identities.add(identity)
                held.append(archive)
            for archive_index, archive in enumerate(held, 1):
                row = {"archive_index": archive_index, "name": archive.path.name, "state": "not_completed"}
                result["archives"].append(row)
                row.update(verify_archive(archive, initial, budget))
                if expected_prefix is not None and row["prefix"] != expected_prefix:
                    raise VerificationError("archive_prefix_mismatch")
            phase, archive_index = "final_input_recheck", None
            final = inventory(installation, budget)
            if final != initial:
                raise VerificationError("staged_inventory_drift", state="stale")
            for archive, row in zip(held, result["archives"]):
                if archive.digest() != row["sha256"]:
                    raise VerificationError("archive_final_content_drift", state="stale")
                archive.check_binding()
            result["input_recheck"] = "unchanged"
            for row in result["archives"]:
                row["state"] = "verified"
            result["state"] = "verified"
    except VerificationError as error:
        result["state"] = error.state
        result["errors"].append(error.diagnostic(phase=phase, archive_index=archive_index))
    except OSError:
        result["state"] = "unavailable"
        result["errors"].append({"code": "verification_io_unavailable", "phase": phase})
    except (TypeError, ValueError, struct.error):
        result["state"] = "failed"
        result["errors"].append({"code": "verification_invalid_input", "phase": phase})
    if budget is not None:
        result["input_read_bytes"] = budget.input_read_bytes
        result["declared_decoded_bytes"] = budget.declared_decoded_bytes
        result["actual_decoded_bytes"] = budget.actual_decoded_bytes
    return result


def write_new_report(output: Path, result: dict, installation: Path, archives: list[Path], *, deadline: float | None = None) -> None:
    """Exclusive new report only; no parent creation, replacement or chmod."""
    if not platform_available():
        raise VerificationError("report_no_follow_unavailable", state="unavailable")
    output, installation = _host_path(output), _host_path(installation)
    archives = [_host_path(path) for path in archives]
    if output == installation or output.is_relative_to(installation) or output in archives:
        raise VerificationError("report_would_write_an_input")
    safe_relative(output.name, Limits())
    raw = (json.dumps(result, sort_keys=True, ensure_ascii=True, separators=(",", ":")) + "\n").encode("ascii")
    if len(raw) > MAX_REPORT_BYTES:
        raise VerificationError("report_bytes_limit")
    budget = Budget(Limits())
    if deadline is not None:
        budget.deadline = min(budget.deadline, deadline)
    budget.check()
    with HeldPath(output.parent, directory=True, budget=budget) as parent:
        parent.check_binding()
        try:
            fd = os.open(output.name, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
                         | getattr(os, "O_CLOEXEC", 0), 0o600, dir_fd=parent.fd)
        except FileExistsError:
            raise VerificationError("report_already_exists") from None
        try:
            created = os.fstat(fd)
            _regular(created)
            position = 0
            while position < len(raw):
                budget.check()
                count = os.write(fd, raw[position:position + CHUNK_BYTES])
                if count <= 0:
                    raise VerificationError("report_short_write", state="unavailable")
                position += count
            budget.check()
            actual = os.fstat(fd)
            _regular(actual)
            if actual.st_size != len(raw) or _binding_identity(actual) != _binding_identity(created) \
                    or _fingerprint(os.stat(output.name, dir_fd=parent.fd, follow_symlinks=False)) != _fingerprint(actual):
                raise VerificationError("report_output_binding_drift", state="stale")
            digest = hashlib.sha256()
            position = 0
            while position < len(raw):
                budget.check()
                observed = os.pread(fd, min(CHUNK_BYTES, len(raw) - position), position)
                budget.read(len(observed))
                if not observed:
                    raise VerificationError("report_readback_short_read", state="unavailable")
                digest.update(observed)
                position += len(observed)
            if digest.hexdigest() != sha256(raw) \
                    or _fingerprint(os.fstat(fd)) != _fingerprint(actual) \
                    or _fingerprint(os.stat(output.name, dir_fd=parent.fd, follow_symlinks=False)) != _fingerprint(actual):
                raise VerificationError("report_output_content_or_stat_drift", state="stale")
            # Creating the new report legitimately changes this parent directory's
            # timestamps. All pre-existing ancestor/path bindings must still match.
            for directory, name, expected in parent.edges:
                if _binding_identity(os.stat(name, dir_fd=directory, follow_symlinks=False)) != _binding_identity(expected):
                    raise VerificationError("report_parent_binding_drift", state="stale")
            budget.check()
        finally:
            os.close(fd)


class _Parser(argparse.ArgumentParser):
    def error(self, _message):
        self.exit(2, "Distribution archive verification: invalid_arguments\n")


def main(argv: list[str] | None = None) -> int:
    parser = _Parser(description=__doc__)
    parser.add_argument("--installation", type=Path, required=True, help="Explicit staged expected tree, read-only")
    parser.add_argument("--archive", type=Path, action="append", required=True, help="Explicit local .zip or uncompressed .tar (repeat up to four)")
    parser.add_argument("--expected-prefix", help="Require this exact safe single top-level directory in every archive")
    parser.add_argument("--output", type=Path, required=True, help="NEW report in an existing no-follow parent, never an input")
    args = parser.parse_args(argv)
    deadline = time.monotonic() + Limits().max_seconds
    result = verify(args.installation, args.archive, expected_prefix=args.expected_prefix)
    result["report_delivery_scope"] = ("Input verification only; CLI exit status and stdout output_written confirm "
                                       "delivery. Failures may leave a NEW partial or unconfirmed report; "
                                       "durable storage is not certified.")
    try:
        write_new_report(args.output, result, args.installation, args.archive, deadline=deadline)
        result["output_written"] = True
    except VerificationError as error:
        result["output_written"] = False
        if result["state"] == "verified":
            result["state"] = error.state
        result["errors"].append(error.diagnostic(phase="new_report"))
    except (OSError, ValueError, TypeError):
        result["output_written"] = False
        if result["state"] == "verified":
            result["state"] = "unavailable"
        result["errors"].append({"code": "report_output_unavailable", "phase": "new_report"})
    print(json.dumps(result, sort_keys=True, ensure_ascii=True, separators=(",", ":")))
    return 0 if result["state"] == "verified" and result["output_written"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
