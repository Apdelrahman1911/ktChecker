#!/usr/bin/env python3
"""Explicit owner action: prepare a NEW, link-free, read-only JDK image.

This does not execute Java or download anything unless --verify-runtime is explicitly
selected. External link targets need --external-root grants; these are lookup domains,
not an instruction to copy the whole external directory. Every followed link hop must
stay in the selected JDK or a declared domain. Only exact, reasoned, *dangling link*
exclusions are accepted. Existing files/directories cannot be excluded.

The private receipt is outside the image and records the selected paths, source link
topology, copied bytes and any omissions. Do not upload it as public CI evidence: even
system configuration paths/digests can be sensitive. Consumers must independently hash
and verify the resulting image; the receipt is not an execution grant or qualification.
Dereferencing links creates a different image, not a claim of original-JDK equivalence.

Capture requires trusted, quiescent installations. Before/after content, inventory and
identity checks detect ordinary drift, not hostile same-user restored writes. Read-only
mode bits do not establish hostile-user immutability, an OS sandbox, or loaded-memory
attestation. Preparation currently requires POSIX; Windows read-only attributes cannot
replace unimplemented private directory ACLs. A requested Java -version probe proves
startup only, never compiler compatibility.

Do not select the destination for analysis until preparation has returned and its receipt
exists. It is an exclusively reserved, private, incomplete output while copying.

Observed Ubuntu JDK21 example (explicitly review every grant and omission first):
  python3 scripts/prepare_jdk_image.py \
    --source /usr/lib/jvm/java-21-openjdk-amd64 --destination /NEW/private/jdk-image \
    --external-root /etc/java-21-openjdk --external-root /etc/ssl/certs/java \
    --external-root /usr/share/doc/openjdk-21-jre-headless \
    --external-root /usr/lib/jvm/openjdk-21 --external-root /usr/lib/x86_64-linux-gnu \
    --exclude-dangling 'lib/src.zip=Owner omits absent source archive; source browsing unavailable' \
    --exclude-dangling 'lib/libatk-wrapper.so=Owner omits absent ATK wrapper; accessibility integration unverified'
The final broad lookup domain only establishes the missing jni target in this observed
installation; it is not enumerated or copied wholesale. Other installations need their
own inspection. Adding --verify-runtime explicitly executes Java; it is not the default.
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
from typing import Mapping, Sequence


class PreparationError(Exception):
    """Public errors deliberately omit raw filesystem errors and selected private paths."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def fail(code: str, message: str) -> None:
    raise PreparationError("prepare_jdk_" + code, message)


@dataclasses.dataclass(frozen=True)
class Limits:
    max_entries: int = 20_000
    max_depth: int = 64
    max_name_bytes: int = 1024
    max_total_name_bytes: int = 4 * 1024 * 1024
    max_file_bytes: int = 512 * 1024 * 1024
    max_total_bytes: int = 1024 * 1024 * 1024
    max_link_hops: int = 64
    max_total_link_hops: int = 20_000
    max_seconds: float = 60.0
    max_receipt_bytes: int = 32 * 1024 * 1024

    def __post_init__(self) -> None:
        for field in dataclasses.fields(self):
            value = getattr(self, field.name)
            valid_type = (type(value) in (int, float)) if field.name == "max_seconds" else type(value) is int
            if not valid_type or value <= 0 or (field.name == "max_seconds" and not math.isfinite(value)):
                fail("limits_invalid", "Preparation limits must be positive finite numbers.")
        if not 0 < self.max_seconds <= 3600 or self.max_entries > 100_000 or self.max_depth > 256:
            fail("limits_invalid", "Preparation time/entry/depth limits exceed the supported ceiling.")
        if self.max_total_bytes > 4 * 1024**3 or self.max_file_bytes > 2 * 1024**3 or self.max_link_hops > 128:
            fail("limits_invalid", "Preparation byte/link limits exceed the supported ceiling.")
        if (self.max_name_bytes > 4096 or self.max_total_name_bytes > 16 * 1024**2
                or self.max_total_link_hops > 100_000 or self.max_receipt_bytes > 64 * 1024**2):
            fail("limits_invalid", "Preparation name/link/receipt limits exceed the supported ceiling.")


@dataclasses.dataclass(frozen=True)
class Root:
    label: str
    selected: Path
    canonical: Path


@dataclasses.dataclass
class Observation:
    stamp: tuple[int, ...]
    kind: str
    target: str | None = None
    children: tuple[str, ...] | None = None
    digest: str | None = None


@dataclasses.dataclass(frozen=True)
class PreparedImage:
    destination: Path
    receipt: Path
    image_digest: str
    receipt_digest: str
    runtime_status: str


class Absent(Exception):
    def __init__(self, path: Path):
        self.path = path


def encoded(value: str) -> bytes:
    try:
        return value.encode("utf-8", "strict")
    except UnicodeError:
        fail("path_invalid", "Selected paths or link targets contain malformed Unicode.")


def canonical_json(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def digest(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def stamp(attributes: os.stat_result) -> tuple[int, ...]:
    return (attributes.st_dev, attributes.st_ino, attributes.st_mode, attributes.st_size,
            attributes.st_mtime_ns, attributes.st_ctime_ns)


def kind(attributes: os.stat_result) -> str:
    if stat.S_ISLNK(attributes.st_mode):
        return "symlink"
    if getattr(attributes, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0):
        fail("nonregular", "Unknown filesystem reparse points are not supported.")
    if stat.S_ISDIR(attributes.st_mode):
        return "directory"
    if stat.S_ISREG(attributes.st_mode):
        return "file"
    fail("nonregular", "Only ordinary directories, files and explicitly resolved symbolic links are supported.")


def portable_component(name: str) -> None:
    if (name in ("", ".", "..") or name.endswith((" ", ".")) or any(c in name for c in '\\:*?"<>|')
            or any(ord(c) < 32 or ord(c) == 127 for c in name)):
        fail("path_invalid", "An input uses an ambiguous or nonportable path component.")
    encoded(name)


def absolute_selection(value: str | Path) -> Path:
    raw = os.fspath(value)
    encoded(raw)
    path = Path(raw)
    if any(part in (".", "..") for part in raw.replace("\\", "/").split("/")):
        fail("path_invalid", "Selected root/output paths must not contain dot or parent segments.")
    return path.absolute()


def selected_root(value: str | Path, label: str) -> Root:
    path = absolute_selection(value)
    try:
        if kind(path.lstat()) != "directory":
            fail("root_invalid", "Selected roots must be existing ordinary directories, not symbolic links.")
        canonical = path.resolve(strict=True)
    except OSError:
        fail("root_invalid", "A selected source or external root is unavailable.")
    return Root(label, path, canonical)


def overlaps(left: Path, right: Path) -> bool:
    return left.is_relative_to(right) or right.is_relative_to(left)


class Preparation:
    def __init__(self, source: Path, destination: Path, receipt: Path,
                 external_roots: Sequence[Path], exclusions: Mapping[str, str], limits: Limits):
        if os.name != "posix":
            fail("platform_unavailable", "Private prepared images currently require POSIX mode support; Windows private ACL handling is not implemented.")
        self.limits = limits
        self.started = time.monotonic()
        self.roots = [selected_root(source, "jdk")]
        if len(external_roots) > 32:
            fail("limit", "Too many external lookup domains were selected.")
        selected = sorted((selected_root(p, "external") for p in external_roots), key=lambda r: str(r.canonical))
        self.roots += [dataclasses.replace(root, label=f"external-{i + 1:02d}") for i, root in enumerate(selected)]
        for index, root in enumerate(self.roots):
            if any(overlaps(root.canonical, other.canonical) for other in self.roots[:index]):
                fail("root_overlap", "Duplicate or overlapping source/external domains are ambiguous.")
        self.exclusions = dict(exclusions)
        if len(self.exclusions) > limits.max_entries:
            fail("limit", "Too many exclusions were selected.")
        for relative, reason in self.exclusions.items():
            if not isinstance(relative, str) or not isinstance(reason, str):
                fail("exclusion_invalid", "Exclusions require an exact relative path and a reason.")
            self.relative(relative)
            if not reason.strip() or len(encoded(reason)) > 1024 or any(ord(c) < 32 or ord(c) == 127 for c in reason):
                fail("exclusion_invalid", "Every dangling-link exclusion needs a bounded, single-line reason.")
        self.destination, self.destination_alias = self.output_path(destination)
        self.receipt, self.receipt_alias = self.output_path(receipt)
        if overlaps(self.destination, self.receipt):
            fail("output_overlap", "The receipt must be outside and separate from the prepared image.")
        for output in (self.destination, self.receipt):
            if any(overlaps(output, root.canonical) for root in self.roots):
                fail("output_overlap", "Image and receipt must be outside every selected source/external domain.")
            if os.path.lexists(output):
                fail("output_exists", "Preparation only creates new image and receipt paths; it never overwrites.")
        self.observations: dict[Path, Observation] = {}
        self.missing: set[Path] = set()
        self.entries: list[dict] = []
        self.omissions: list[dict] = []
        self.used_exclusions: set[str] = set()
        self.entry_count = 0
        self.name_bytes = 0
        self.total_bytes = 0
        self.total_link_hops = 0
        self.source_name_bytes = 0
        self.destination_identity: tuple[int, int] | None = None
        self.receipt_identity: tuple[int, int] | None = None

    def checkpoint(self) -> None:
        if time.monotonic() - self.started > self.limits.max_seconds:
            fail("timeout", "Cooperative filesystem preparation exceeded its time budget.")

    def relative(self, value: str) -> None:
        parts = value.split("/")
        if len(parts) > self.limits.max_depth or len(encoded(value)) > self.limits.max_name_bytes:
            fail("limit", "A logical path exceeds the supported depth or UTF-8 name bound.")
        for part in parts:
            portable_component(part)

    def output_path(self, value: Path) -> tuple[Path, Path]:
        selected = absolute_selection(value)
        portable_component(selected.name)
        try:
            parent = selected.parent.resolve(strict=True)
            if kind(parent.lstat()) != "directory":
                fail("output_invalid", "Output parents must be existing directories.")
        except OSError:
            fail("output_invalid", "Output parents must already exist.")
        return parent / selected.name, selected

    def root_for(self, path: Path) -> Root:
        for root in self.roots:
            if path.is_relative_to(root.canonical):
                return root
        fail("external_unapproved", "A link hop leaves the selected JDK and explicitly approved external domains.")

    def ref(self, path: Path) -> dict:
        root = self.root_for(path)
        relative = path.relative_to(root.canonical).as_posix()
        return {"root": root.label, "path": "" if relative == "." else relative}

    def observe(self, path: Path) -> Observation:
        self.checkpoint()
        self.root_for(path)
        try:
            attributes = path.lstat()
        except FileNotFoundError:
            raise Absent(path) from None
        object_kind = kind(attributes)
        if object_kind != "symlink":
            if not attributes.st_mode & 0o444 or (object_kind == "directory" and not attributes.st_mode & 0o111):
                fail("unreadable", "A selected input does not grant readable/searchable access.")
        target = os.readlink(path) if object_kind == "symlink" else None
        current = Observation(stamp(attributes), object_kind, target)
        previous = self.observations.get(path)
        if previous:
            if previous.stamp != current.stamp or previous.target != target or previous.kind != object_kind:
                fail("changed", "A selected source object or link changed during preparation.")
            return previous
        if len(self.observations) >= self.limits.max_entries * 2:
            fail("limit", "The expanded source-object inventory exceeds its bound.")
        self.source_name_bytes += len(encoded(self.ref(path)["path"])) + (len(encoded(target)) if target is not None else 0)
        if self.source_name_bytes > self.limits.max_total_name_bytes * 2:
            fail("limit", "The selected source-name and link-target inventory exceeds its byte bound.")
        self.observations[path] = current
        return current

    def children(self, path: Path) -> tuple[str, ...]:
        observed = self.observe(path)
        if observed.kind != "directory":
            fail("changed", "A selected directory changed type.")
        names = []
        with os.scandir(path) as stream:
            for entry in stream:
                self.checkpoint()
                portable_component(entry.name)
                if len(names) >= self.limits.max_entries:
                    fail("limit", "A source directory exceeds the entry bound.")
                names.append(entry.name)
        ordered = tuple(sorted(names))
        if len({name.casefold() for name in ordered}) != len(ordered):
            fail("path_ambiguous", "A directory contains case-insensitive name collisions.")
        if observed.children is not None and observed.children != ordered:
            fail("changed", "A selected source directory inventory changed during preparation.")
        if observed.children is None:
            self.source_name_bytes += sum(len(encoded(name)) for name in ordered)
            if self.source_name_bytes > self.limits.max_total_name_bytes * 2:
                fail("limit", "The source-directory name inventory exceeds its byte bound.")
        observed.children = ordered
        self.observe(path)
        return ordered

    def link_target(self, path: Path, target: str) -> Path:
        if not target or len(encoded(target)) > self.limits.max_name_bytes or "\\" in target or target.startswith("//"):
            fail("link_ambiguous", "A symbolic-link target is unsupported or ambiguous.")
        absolute = target.startswith("/") if os.name != "nt" else os.path.isabs(target)
        parts = target.split("/")
        if absolute and os.name != "nt":
            parts = parts[1:]
        named = False
        for part in parts:
            if part in (".", ".."):
                # Leading ../ is well-defined from an already canonical parent. Embedded
                # name/../ is not normalized away: name might itself be a symbolic link.
                if absolute or named:
                    fail("link_ambiguous", "Dot/parent components after a named link-target component are unsupported.")
            else:
                portable_component(part)
                named = True
        candidate = Path(os.path.abspath(path.parent / target))
        self.root_for(candidate)  # Check this hop BEFORE resolving any of its components.
        return candidate

    def resolve(self, path: Path, seen: frozenset[Path] = frozenset(), hops: int = 0) -> tuple[Path, list[dict]]:
        self.checkpoint()
        root = self.root_for(path)
        current = root.canonical
        if self.observe(current).kind != "directory":
            fail("changed", "A selected root changed type.")
        parts = path.relative_to(current).parts
        for index, part in enumerate(parts):
            current /= part
            observed = self.observe(current)
            if observed.kind == "symlink":
                if current in seen:
                    fail("cycle", "A symbolic-link resolution cycle was detected.")
                self.total_link_hops += 1
                if hops >= self.limits.max_link_hops or self.total_link_hops > self.limits.max_total_link_hops:
                    fail("limit", "Symbolic-link expansion exceeds the configured hop bound.")
                target = self.link_target(current, observed.target or "")
                resolved, links = self.resolve(target, seen | {current}, hops + 1)
                record = {"source": self.ref(current), "target": observed.target, "resolved_target": self.ref(resolved)}
                remaining = parts[index + 1:]
                if remaining:
                    if self.observe(resolved).kind != "directory":
                        fail("link_ambiguous", "A link target used as a directory is not a directory.")
                    final, suffix = self.resolve(resolved.joinpath(*remaining), seen | {current}, hops + 1)
                    return final, [record] + links + suffix
                return resolved, [record] + links
            if index < len(parts) - 1 and observed.kind != "directory":
                fail("link_ambiguous", "A non-directory occurs inside a selected path.")
        return current, []

    def read_file(self, source: Path, target: Path | None = None) -> str:
        observed = self.observe(source)
        if observed.kind != "file":
            fail("changed", "A selected regular file changed type.")
        size = observed.stamp[3]
        if size < 0 or size > self.limits.max_file_bytes:
            fail("limit", "An input file exceeds the configured byte bound.")
        sha = hashlib.sha256()
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
        with os.fdopen(os.open(source, flags), "rb") as stream:
            if stamp(os.fstat(stream.fileno())) != observed.stamp:
                fail("changed", "A source file changed before its bytes were read.")
            output = None
            try:
                if target is not None:
                    output = os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
                                               | getattr(os, "O_BINARY", 0), 0o600), "wb")
                remaining = size
                while remaining:
                    self.checkpoint()
                    block = stream.read(min(64 * 1024, remaining))
                    if not block:
                        fail("changed", "A source file was truncated while being copied.")
                    remaining -= len(block)
                    sha.update(block)
                    if output:
                        output.write(block)
                if stream.read(1) or stamp(os.fstat(stream.fileno())) != observed.stamp:
                    fail("changed", "A source file changed while its bytes were read.")
                if output:
                    output.flush()
                    os.fsync(output.fileno())
            finally:
                if output:
                    output.close()
        self.observe(source)
        value = "sha256:" + sha.hexdigest()
        if observed.digest is not None and observed.digest != value:
            fail("changed", "Selected source bytes differ from an earlier read.")
        observed.digest = value
        return value

    def visit(self, source: Path, logical: str, active_directories: frozenset[Path]) -> None:
        self.checkpoint()
        if logical:
            self.relative(logical)
        self.entry_count += 1
        self.name_bytes += len(encoded(logical))
        if self.entry_count > self.limits.max_entries or self.name_bytes > self.limits.max_total_name_bytes:
            fail("limit", "Expanded logical paths exceed their entry/name byte budget.")
        original = self.observe(source)
        try:
            resolved, links = self.resolve(source)
        except Absent as absence:
            if original.kind != "symlink" or logical not in self.exclusions:
                fail("dangling", "A dangling link needs an exact owner-listed exclusion and reason.")
            self.missing.add(absence.path)
            self.used_exclusions.add(logical)
            self.omissions.append({"path": logical, "reason": self.exclusions[logical], "source": self.ref(source),
                                   "raw_target": original.target, "absent_component": self.ref(absence.path),
                                   "kind": "explicit_dangling_link_omission"})
            return
        if logical in self.exclusions:
            fail("exclusion_not_dangling", "An exclusion selected an existing file or directory, not a dangling link.")
        observed = self.observe(resolved)
        target = self.destination / logical
        record = {"path": logical, "kind": observed.kind, "source": self.ref(resolved), "links": links}
        if observed.kind == "directory":
            if resolved in active_directories:
                fail("cycle", "A directory-link expansion cycle was detected.")
            if logical:
                target.mkdir(mode=0o700)
            for name in self.children(resolved):
                self.visit(resolved / name, f"{logical}/{name}" if logical else name, active_directories | {resolved})
            os.chmod(target, 0o500)
            record["mode"] = 0o500
        elif observed.kind == "file":
            size = observed.stamp[3]
            if size < 0 or size > self.limits.max_file_bytes or size > self.limits.max_total_bytes - self.total_bytes:
                fail("limit", "Expanded copied bytes exceed their file or total budget.")
            self.total_bytes += size
            record.update(size=size, sha256=self.read_file(resolved, target))
            mode = 0o400 | (observed.stamp[2] & 0o111)
            os.chmod(target, mode)
            record["mode"] = mode
        else:
            fail("nonregular", "Resolved images may contain only ordinary files and directories.")
        self.entries.append(record)

    def verify_roots_and_outputs(self) -> None:
        self.checkpoint()
        for root in self.roots:
            if kind(root.selected.lstat()) != "directory" or root.selected.resolve(strict=True) != root.canonical:
                fail("changed", "A selected root alias was retargeted during preparation.")
        for alias, output in ((self.destination_alias, self.destination), (self.receipt_alias, self.receipt)):
            if alias.parent.resolve(strict=True) != output.parent:
                fail("changed", "An output-parent alias was retargeted during preparation.")
        if self.destination_identity is not None:
            current = self.destination.lstat()
            if kind(current) != "directory" or (current.st_dev, current.st_ino) != self.destination_identity:
                fail("changed", "The owned destination directory was replaced during preparation.")

    def verify_sources(self) -> None:
        self.verify_roots_and_outputs()
        for path, observed in list(self.observations.items()):
            self.checkpoint()
            self.observe(path)
            if observed.kind == "file" and observed.digest is not None:
                self.read_file(path)  # Independent byte read, never a path/mtime digest cache.
            elif observed.kind == "directory" and observed.children is not None:
                self.children(path)
        for path in self.missing:
            self.checkpoint()
            if os.path.lexists(path):
                fail("changed", "An excluded dangling target appeared during preparation.")
        # Earlier roots may have changed while later source bytes were being checked.
        for path in self.observations:
            self.observe(path)
        self.verify_roots_and_outputs()

    def verify_image(self) -> tuple[list[dict], str]:
        self.verify_roots_and_outputs()
        expected = {e["path"]: {key: e[key] for key in ("path", "kind", "mode", "size", "sha256") if key in e}
                    for e in self.entries}
        actual = []
        observations = []
        pending = [(self.destination, "")]
        while pending:
            self.checkpoint()
            path, logical = pending.pop()
            attributes = path.lstat()
            object_kind = kind(attributes)
            if object_kind not in ("file", "directory") or logical not in expected:
                fail("image_changed", "The prepared image has an unexpected entry or symbolic link.")
            record = {"path": logical, "kind": object_kind, "mode": stat.S_IMODE(attributes.st_mode)}
            if object_kind == "directory":
                children = []
                with os.scandir(path) as stream:
                    for entry in stream:
                        self.checkpoint()
                        relative = f"{logical}/{entry.name}" if logical else entry.name
                        if relative not in expected or len(pending) + len(actual) >= self.limits.max_entries:
                            fail("image_changed", "The prepared image inventory differs from copied entries.")
                        pending.append((Path(entry.path), relative))
                        children.append(entry.name)
                observations.append((path, stamp(attributes), tuple(sorted(children))))
            else:
                sha = hashlib.sha256()
                size = 0
                flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
                with os.fdopen(os.open(path, flags), "rb") as stream:
                    if stamp(os.fstat(stream.fileno())) != stamp(attributes):
                        fail("image_changed", "A prepared file was replaced before verification.")
                    while True:
                        self.checkpoint()
                        block = stream.read(64 * 1024)
                        if not block:
                            break
                        size += len(block)
                        if size > expected[logical].get("size", -1):
                            fail("image_changed", "A prepared file exceeds its expected copied size.")
                        sha.update(block)
                    if stamp(os.fstat(stream.fileno())) != stamp(attributes):
                        fail("image_changed", "A prepared file changed while being verified.")
                record.update(size=size, sha256="sha256:" + sha.hexdigest())
                observations.append((path, stamp(attributes), None))
            if stamp(path.lstat()) != stamp(attributes) or record != expected[logical]:
                fail("image_changed", "Prepared image bytes, entry types or mode bits changed.")
            actual.append(record)
        if len(actual) != len(expected):
            fail("image_changed", "A copied entry is missing from the prepared image.")
        for path, attributes, children in observations:
            self.checkpoint()
            if stamp(path.lstat()) != attributes:
                fail("image_changed", "An earlier prepared entry changed during final image verification.")
            if children is not None:
                with os.scandir(path) as stream:
                    current = []
                    for entry in stream:
                        self.checkpoint()
                        if len(current) >= len(children):
                            fail("image_changed", "An earlier prepared directory gained an entry.")
                        current.append(entry.name)
                if tuple(sorted(current)) != children:
                    fail("image_changed", "An earlier prepared directory changed its entry inventory.")
        ordered = sorted(actual, key=lambda entry: entry["path"])
        return ordered, digest(canonical_json({"schema": "ktchecker/prepared-jdk-content-1", "entries": ordered}))

    def validate_layout(self) -> dict:
        by_path = {entry["path"]: entry for entry in self.entries}
        release = by_path.get("release", {})
        if release.get("kind") != "file" or not 0 < release.get("size", 0) <= 65_536:
            fail("layout_invalid", "A bounded, nonempty JDK release descriptor is required.")
        values = {}
        try:
            text = (self.destination / "release").read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            fail("layout_invalid", "The JDK release descriptor is unreadable or malformed.")
        for line in text.splitlines():
            if not line or line.startswith("#"):
                continue
            match = re.fullmatch(r'([A-Z_][A-Z_0-9]*)="([^"\r\n]*)"', line)
            if not match or match[1] in values:
                fail("layout_invalid", "The JDK release descriptor is ambiguous or malformed.")
            values[match[1]] = match[2]
        if not re.fullmatch(r"[0-9][0-9A-Za-z.+_-]{0,79}", values.get("JAVA_VERSION", "")):
            fail("layout_invalid", "An explicit JDK JAVA_VERSION is required.")
        platform = values.get("OS_NAME")
        vm = {"Linux": "lib/server/libjvm.so", "Darwin": "lib/server/libjvm.dylib", "Windows": "bin/server/jvm.dll"}.get(platform)
        if vm is None or values.get("OS_ARCH") not in ("x86_64", "amd64", "aarch64"):
            fail("layout_invalid", "The selected JDK OS/architecture is not supported by this preparation schema.")
        java = "bin/java.exe" if platform == "Windows" else "bin/java"
        required = ("release", "lib/modules", "lib/jvm.cfg", "conf/security/java.security", vm, java)
        if any(by_path.get(path, {}).get("kind") != "file" or by_path[path].get("size", 0) <= 0 for path in required):
            fail("layout_invalid", "Required runtime modules, launcher, native VM and security configuration must be nonempty.")
        if platform != "Windows" and not by_path[java]["mode"] & 0o100:
            fail("layout_invalid", "The prepared Java launcher must retain owner-executable access.")
        return {key: values[key] for key in ("JAVA_VERSION", "OS_NAME", "OS_ARCH")} | {"java_path": java}

    def write_receipt(self, body: dict) -> str:
        self.checkpoint()
        data = canonical_json(body) + b"\n"
        if len(data) > self.limits.max_receipt_bytes:
            fail("limit", "The private provenance receipt exceeds its byte bound.")
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
        with os.fdopen(os.open(self.receipt, flags, 0o600), "wb") as output:
            identity = os.fstat(output.fileno())
            self.receipt_identity = (identity.st_dev, identity.st_ino)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        return digest(data)

    def run(self, verify_runtime: bool, probe_timeout: float, probe_output_bytes: int) -> PreparedImage:
        self.verify_roots_and_outputs()
        self.destination.mkdir(mode=0o700)  # Exclusive reservation, never replace/merge an existing directory.
        attributes = self.destination.lstat()
        self.destination_identity = (attributes.st_dev, attributes.st_ino)
        try:
            self.visit(self.roots[0].canonical, "", frozenset())
            if self.used_exclusions != set(self.exclusions):
                fail("exclusion_unused", "An owner-listed exclusion did not identify an encountered dangling logical link.")
            layout = self.validate_layout()
            self.verify_sources()
            image_entries, image_digest = self.verify_image()
            verification = {"status": "not_requested", "executed": False, "qualification": False}
            if verify_runtime:
                self.checkpoint()
                remaining = self.limits.max_seconds - (time.monotonic() - self.started)
                verification = probe_runtime(self.destination / layout["java_path"], min(probe_timeout, remaining),
                                             probe_output_bytes, layout["JAVA_VERSION"])
                self.verify_sources()
                final_entries, final_digest = self.verify_image()
                if final_entries != image_entries or final_digest != image_digest:
                    fail("image_changed", "The runtime probe modified its selected prepared image.")
            source_objects = []
            for path, observed in sorted(self.observations.items(), key=lambda item: (self.ref(item[0])["root"], self.ref(item[0])["path"])):
                record = self.ref(path) | {"kind": observed.kind, "mode": stat.S_IMODE(observed.stamp[2])}
                if observed.target is not None:
                    record["raw_target"] = observed.target
                if observed.children is not None:
                    record["children"] = observed.children
                if observed.digest is not None:
                    record.update(size=observed.stamp[3], sha256=observed.digest)
                source_objects.append(record)
            roots = [{"id": r.label, "selected_path": str(r.selected), "canonical_path": str(r.canonical),
                      "selection": "entire_source" if r.label == "jdk" else "explicit_reachable_target_lookup_domain"} for r in self.roots]
            source_manifest = {"roots": roots, "objects": source_objects, "omissions": self.omissions}
            body = {
                "schema": "ktchecker/prepared-jdk-receipt-1", "status": "prepared",
                "created_at": datetime.now(timezone.utc).isoformat(),
                "source": source_manifest, "source_manifest_digest": digest(canonical_json(source_manifest)),
                "image": {"path": str(self.destination), "content_schema": "ktchecker/prepared-jdk-content-1",
                          "digest": image_digest, "entries": image_entries, "copied_source_mapping": sorted(self.entries, key=lambda e: e["path"]),
                          "bytes": self.total_bytes, "entry_count": len(image_entries), "release": layout},
                "limits": dataclasses.asdict(self.limits), "runtime_probe": verification,
                "claims": {"derived_link_free_image": True, "original_entire_jdk_equivalence": False,
                           "all_source_logical_entries_represented": not self.omissions,
                           "omissions_count": len(self.omissions), "compiler_compatibility": "unverified",
                           "release_qualified": False, "source_and_image_rehashed": True,
                           "capture_assumption": "trusted_quiescent_installations_not_hostile_restored_writes",
                           "read_only_policy": "posix_owner_only_mode_bits",
                           "immutable_against_owner": False, "consumer_must_independently_verify": True},
                "privacy": "Private local provenance; contains selected system paths/configuration digests. Do not upload as public evidence.",
            }
            receipt_digest = self.write_receipt(body)
            self.verify_roots_and_outputs()
            return PreparedImage(self.destination, self.receipt, image_digest, receipt_digest, str(verification["status"]))
        except BaseException:
            self.cleanup()
            raise

    def cleanup(self) -> None:
        """Only remove paths this invocation exclusively created and still recognizes."""
        if self.receipt_identity is not None and os.path.lexists(self.receipt):
            attributes = self.receipt.lstat()
            if stat.S_ISREG(attributes.st_mode) and (attributes.st_dev, attributes.st_ino) == self.receipt_identity:
                self.receipt.unlink()
        if self.destination_identity is None or not os.path.lexists(self.destination):
            return
        attributes = self.destination.lstat()
        if not stat.S_ISDIR(attributes.st_mode) or stat.S_ISLNK(attributes.st_mode) or (attributes.st_dev, attributes.st_ino) != self.destination_identity:
            fail("cleanup_ownership_lost", "The output path was replaced; refusing to remove an unowned path.")
        # Walk without following links. rmtree's fd-based implementation adds protection
        # where provided by Python, but hostile same-user writers remain out of scope.
        for parent, directories, _ in os.walk(self.destination, followlinks=False):
            os.chmod(parent, 0o700)
            for name in directories:
                child = Path(parent) / name
                if not child.is_symlink():
                    os.chmod(child, 0o700)
        shutil.rmtree(self.destination)


def probe_runtime(java: Path, timeout: float, max_output_bytes: int, expected_version: str) -> dict:
    """Explicit, bounded startup smoke; never runs by default or retains raw child text."""
    if not 0 < timeout <= 60 or not 1 <= max_output_bytes <= 1024 * 1024:
        fail("probe_limits_invalid", "Runtime startup probes require explicit bounded time and output limits.")
    wait_features = ("waitid", "P_PID", "WEXITED", "WNOHANG", "WNOWAIT")
    if os.name != "posix" or not all(hasattr(os, name) for name in wait_features):
        return {"status": "unavailable", "executed": False, "reason": "unreaped_child_ownership_probe_host_required", "qualification": False}
    if signal.getsignal(signal.SIGCHLD) != signal.SIG_DFL:
        return {"status": "unavailable", "executed": False, "reason": "exclusive_child_wait_ownership_required", "qualification": False}
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="ktchecker-jdk-probe-") as temporary:
        work = Path(temporary)
        command = [str(java), "-Xmx64m", "-Dfile.encoding=UTF-8", f"-Djava.io.tmpdir={work}", "-version"]
        environment = {"PATH": os.defpath, "HOME": temporary, "TMPDIR": temporary, "TMP": temporary, "TEMP": temporary,
                       "JAVA_HOME": str(java.parent.parent), "LC_ALL": "C", "LANG": "C"}
        base = {"command": command, "cwd": temporary, "environment": environment, "timeout_seconds": timeout,
                "max_output_bytes": max_output_bytes, "expected_java_version": expected_version, "qualification": False,
                "scope": "Java startup/version only; not compiler compatibility, full semantics, or OS sandbox proof"}
        try:
            process = subprocess.Popen(command, cwd=work, env=environment, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        except OSError:
            return base | {"status": "failed", "executed": False, "reason": "runtime_start_failed"}
        buffers = {"stdout": bytearray(), "stderr": bytearray()}
        total = 0
        lock = threading.Lock()
        over_limit = threading.Event()
        read_failed = threading.Event()

        def drain(name: str, stream) -> None:
            nonlocal total
            try:
                with stream:
                    while block := stream.read(4096):
                        with lock:
                            prior = total
                            total += len(block)
                            buffers[name].extend(block[:max(0, max_output_bytes - prior)])
                            if total > max_output_bytes:
                                over_limit.set()
            except OSError:
                read_failed.set()

        threads = [threading.Thread(target=drain, args=(name, stream), daemon=True)
                   for name, stream in (("stdout", process.stdout), ("stderr", process.stderr))]
        for thread in threads:
            thread.start()
        reason = None
        owns_unreaped_leader = True
        try:
            while True:
                try:
                    # WNOWAIT observes exit without reaping. Keeping our child PID reserved
                    # prevents a recycled unrelated process group from receiving cleanup.
                    status = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                except (ChildProcessError, OSError):
                    owns_unreaped_leader = False
                    reason = "runtime_child_wait_ownership_lost"
                    break
                if status is not None:
                    break
                if over_limit.is_set():
                    reason = "runtime_output_limit"
                    break
                if time.monotonic() - started >= timeout:
                    reason = "runtime_timeout"
                    break
                time.sleep(min(0.025, timeout / 10))
        finally:
            # No poll()/wait()/waitpid() has reaped the leader yet. Do not signal a
            # guessed old PGID if another child reaper invalidated that ownership.
            if owns_unreaped_leader:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                except OSError:
                    reason = "runtime_process_cleanup_failed"
            for thread in threads:
                thread.join(timeout=1)
            if owns_unreaped_leader:
                try:
                    process.wait(timeout=2)  # Reap only after the LAST possible group signal.
                except subprocess.TimeoutExpired:
                    reason = "runtime_process_cleanup_failed"
        if any(thread.is_alive() for thread in threads):
            reason = "runtime_output_cleanup_failed"
        if over_limit.is_set():
            reason = "runtime_output_limit"
        if read_failed.is_set():
            reason = "runtime_output_read_failed"
        # Exit can be observed on the first poll after the deadline. Cleanup and
        # transport also count; a late successful exit is not an on-time probe.
        if reason is None and time.monotonic() - started >= timeout:
            reason = "runtime_timeout"
        with lock:
            captured = {name: bytes(value) for name, value in buffers.items()}
            observed_total = total
        text = (captured["stdout"] + b"\n" + captured["stderr"]).decode("utf-8", "replace")
        version = re.search(r'(?m)^(?:openjdk|java) version "([0-9][0-9A-Za-z.+_-]{0,79})"', text)
        if reason is None and (process.returncode != 0 or version is None):
            reason = "runtime_version_probe_failed"
        if reason is None and version[1] != expected_version:
            reason = "runtime_release_version_mismatch"
        return base | {"status": "passed" if reason is None else "failed", "executed": True, "reason": reason,
                       "exit_code": process.returncode, "java_version": version[1] if version else None,
                       "elapsed_millis": round((time.monotonic() - started) * 1000), "observed_output_bytes": observed_total,
                       "output_truncated": observed_total > max_output_bytes,
                       "captured_output": {name: {"bytes": len(value), "sha256": digest(value)} for name, value in captured.items()}}


def prepare_image(source: Path, destination: Path, *, external_roots: Sequence[Path] = (),
                  exclusions: Mapping[str, str] | None = None, receipt: Path | None = None,
                  limits: Limits = Limits(), verify_runtime: bool = False,
                  probe_timeout: float = 10, probe_output_bytes: int = 65_536) -> PreparedImage:
    if os.name != "posix":
        fail("platform_unavailable", "Private prepared images currently require POSIX mode support; Windows private ACL handling is not implemented.")
    receipt = receipt if receipt is not None else destination.with_name(destination.name + ".receipt.json")
    try:
        return Preparation(source, destination, receipt, external_roots, exclusions or {}, limits).run(
            verify_runtime, probe_timeout, probe_output_bytes)
    except PreparationError:
        raise
    except (OSError, Absent):
        fail("filesystem_failed", "A selected filesystem input or owned output could not be read, copied and verified.")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, type=Path, help="Explicit trusted JDK root; never inferred from JAVA_HOME")
    parser.add_argument("--destination", required=True, type=Path, help="NEW owner-selected image path; parent must exist")
    parser.add_argument("--receipt", type=Path, help="NEW private receipt path outside image (default: destination.receipt.json)")
    parser.add_argument("--external-root", action="append", default=[], type=Path, help="Explicit additional link-target lookup domain (repeatable)")
    parser.add_argument("--exclude-dangling", action="append", default=[], metavar="RELATIVE_PATH=REASON", help="Exact dangling logical link only; never a file/directory/glob")
    parser.add_argument("--verify-runtime", action="store_true", help="Explicitly execute prepared Java -version in a bounded isolated temporary working directory")
    parser.add_argument("--probe-timeout", type=float, default=10, help="Runtime probe timeout seconds (default 10; maximum 60)")
    parser.add_argument("--timeout", type=float, default=60, help="Cooperative total preparation deadline seconds (default 60; maximum 3600)")
    options = parser.parse_args(argv)
    try:
        exclusions = {}
        for item in options.exclude_dangling:
            relative, separator, reason = item.partition("=")
            if not separator or relative in exclusions:
                fail("exclusion_invalid", "Exclusions need distinct exact relative paths followed by =REASON.")
            exclusions[relative] = reason
        result = prepare_image(options.source, options.destination, receipt=options.receipt, external_roots=options.external_root,
                               exclusions=exclusions, limits=Limits(max_seconds=options.timeout), verify_runtime=options.verify_runtime,
                               probe_timeout=options.probe_timeout)
        print(json.dumps({"status": "prepared", "image": str(result.destination), "image_digest": result.image_digest,
                          "private_receipt": str(result.receipt), "receipt_digest": result.receipt_digest,
                          "runtime_probe": result.runtime_status, "compiler_compatibility": "unverified", "qualified": False}))
        return 0 if result.runtime_status in ("not_requested", "passed") else 2
    except PreparationError as error:
        print(json.dumps({"status": "failed", "code": error.code, "message": str(error)}), file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print(json.dumps({"status": "failed", "code": "prepare_jdk_interrupted"}), file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
