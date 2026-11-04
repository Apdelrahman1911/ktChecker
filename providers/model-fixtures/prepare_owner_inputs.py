#!/usr/bin/env python3
"""Copy explicitly selected owner fixture inputs into a fresh private offline input set.

This is producer input preparation, NOT a live-worktree capture or authorization API.
No downloads, ambient Gradle-home lookup, hardlinks, subprocesses or project execution.
The returned manifest is data. Only the explicitly invoked trusted fixture driver may
register its copies and issue exact execution/import capabilities.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat

MAX_BYTES = 2 * 1024 * 1024 * 1024
MAX_FILES = 20000
GRADLE_ARCHIVE_SHA256 = "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"
# Only Gradle's launcher helpers. Containment comes from the exact provider-runtime
# native bundle captured by the trusted driver at START, never PATH setsid/kill.
HELPERS = ("/bin/sh", "/usr/bin/uname", "/usr/bin/xargs", "/usr/bin/sed", "/usr/bin/tr")


def absolute_no_alias_path(value):
    """Selections are literal paths, not implicit permission to dereference aliases."""
    raw = os.fspath(value)
    if any(part in {".", ".."} for part in raw.split("/")):
        raise ValueError("Selected paths cannot contain dot or parent segments")
    return Path(raw).absolute()


def open_parent(path, create=False):
    """Anchor each existing/created parent without following directory aliases."""
    fd = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:-1]:
            if create:
                try:
                    os.mkdir(part, mode=0o700, dir_fd=fd)
                except FileExistsError:
                    pass
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        return fd
    except BaseException:
        os.close(fd)
        raise


def selected_directory(value):
    path = absolute_no_alias_path(value)
    parent = open_parent(path)
    try:
        directory = os.open(path.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        os.close(directory)
    finally:
        os.close(parent)
    return path


def read_copy(source, destination=None):
    """Anchored no-follow copy of a selected regular producer file, with drift checks."""
    source = absolute_no_alias_path(source)
    fd = open_parent(source)
    try:
        leaf = os.open(source.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        try:
            before = os.fstat(leaf)
            if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_BYTES:
                raise ValueError("Input is not a bounded regular file")
            digest = hashlib.sha256()
            count = 0
            if destination is not None:
                writer = destination.open("xb")
            else:
                writer = None
            try:
                while block := os.read(leaf, 65536):
                    count += len(block)
                    if count > before.st_size:
                        raise ValueError("Producer input grew during copy")
                    digest.update(block)
                    if writer is not None:
                        writer.write(block)
            finally:
                if writer is not None:
                    writer.close()
            after = os.fstat(leaf)
            named = os.stat(source.name, dir_fd=fd, follow_symlinks=False)
            if count != before.st_size or any(
                getattr(before, field) != getattr(after, field) or getattr(before, field) != getattr(named, field)
                for field in ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
            ):
                raise ValueError("Producer input changed during copy")
            executable = bool(before.st_mode & 0o111)
            if destination is not None:
                destination.chmod(0o500 if executable else 0o400)
            return "sha256:" + digest.hexdigest(), count, executable
        finally:
            os.close(leaf)
    finally:
        os.close(fd)


def validate_omitted_seed_lock(path):
    """Only actual regular lock files are bookkeeping; aliases/special files fail closed."""
    path = absolute_no_alias_path(path)
    parent = open_parent(path)
    try:
        if not stat.S_ISREG(os.stat(path.name, dir_fd=parent, follow_symlinks=False).st_mode):
            raise ValueError("Only regular cache lock files may be omitted")
    finally:
        os.close(parent)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-home", required=True, type=Path)
    parser.add_argument("--gradle-archive", required=True, type=Path,
                        help="Actual vendor Gradle 9.6.1 bin ZIP; checked against the independent wrapper SHA-256 pin")
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--gradle-seed", required=True, type=Path,
                        help="Explicit dedicated, preseeded offline home; not an ambient mutable home")
    parser.add_argument("--output", required=True, type=Path, help="Must not already exist")
    args = parser.parse_args(argv)
    selected = {"gradle": selected_directory(args.gradle_home), "jdk": selected_directory(args.java_home),
                "seed": selected_directory(args.gradle_seed)}
    output = absolute_no_alias_path(args.output)
    if os.path.lexists(output) or any(output.is_relative_to(root) or root.is_relative_to(output) for root in selected.values()):
        raise ValueError("Fresh independent private output directory is required")
    if not all(root.is_dir() for root in selected.values()):
        raise ValueError("Explicit runtime and seed directories are required")
    if not (selected["gradle"] / "lib/gradle-gradle-cli-main-9.6.1.jar").is_file():
        raise ValueError("This fixture adapter requires the actual Gradle 9.6.1 distribution")
    parent = open_parent(output, create=True)
    try:
        os.mkdir(output.name, mode=0o700, dir_fd=parent)
        root_fd = os.open(output.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        try:
            os.fchmod(root_fd, 0o700)
            output_identity = os.fstat(root_fd)
            if output_identity.st_uid != os.geteuid():
                raise ValueError("Prepared output must be an owner-only directory")
        finally:
            os.close(root_fd)
    finally:
        os.close(parent)
    archive = output / "gradle-9.6.1-bin.zip"
    archive_digest, archive_size, _ = read_copy(args.gradle_archive, archive)
    if archive_digest != "sha256:" + GRADLE_ARCHIVE_SHA256:
        raise ValueError("Selected Gradle archive differs from the independent Gradle 9.6.1 vendor pin")
    copies = {kind: output / kind for kind in selected}
    records = []
    directory_records = []
    omitted = []
    total = archive_size
    for kind, source in selected.items():
        copies[kind].mkdir(mode=0o700)
        for directory, directories, files in os.walk(source, followlinks=False):
            directories.sort()
            files.sort()
            relative_directory = Path(directory).relative_to(source)
            if relative_directory.parts:
                if kind == "seed" and relative_directory.parts[0] not in {"caches", "wrapper"}:
                    raise ValueError("Seed init scripts/properties/other external configuration are forbidden")
                (copies[kind] / relative_directory).mkdir(mode=0o700)
                directory_records.append({"kind": kind, "relativePath": relative_directory.as_posix()})
                if len(records) + len(directory_records) > MAX_FILES:
                    raise ValueError("Owner input inventory bound exceeded; no fixture execution was attempted")
            for name in directories:
                if (Path(directory) / name).is_symlink():
                    raise ValueError("Directory aliases are not a qualified input vocabulary")
            for name in files:
                file = Path(directory) / name
                relative = file.relative_to(source).as_posix()
                if kind == "seed" and relative.split("/", 1)[0] not in {"caches", "wrapper"}:
                    raise ValueError("Seed init scripts/properties/other external configuration are forbidden")
                if kind == "seed" and name.endswith((".lock", ".lck")):
                    validate_omitted_seed_lock(file)
                    omitted.append({"kind": kind, "path": relative, "reason": "ephemeral cache lock, not producer input"})
                    continue
                digest, size, executable = read_copy(file, copies[kind] / relative)
                total += size
                if total > MAX_BYTES or len(records) + len(directory_records) >= MAX_FILES:
                    raise ValueError("Owner input inventory bound exceeded; no fixture execution was attempted")
                records.append({"kind": kind, "relativePath": relative, "digest": digest, "size": size, "executable": executable})
    helpers = []
    for helper in HELPERS:
        actual = Path(helper).resolve(strict=True)
        digest, size, _ = read_copy(actual)
        helpers.append({"coordinate": "fixture-system-helper:" + Path(helper).name,
                        "path": str(actual), "digest": digest, "size": size})
    manifest = {
        "schemaVersion": 1, "gradleArchive": str(archive), "gradleHome": str(copies["gradle"]), "javaHome": str(copies["jdk"]),
        "gradleSeedHome": str(copies["seed"]),
        "files": sorted(records, key=lambda item: (item["kind"], item["relativePath"])),
        "directories": sorted(directory_records, key=lambda item: (item["kind"], item["relativePath"])),
        "helperArtifacts": helpers, "omittedInputEntries": omitted,
    }
    destination = output / "owner-inputs.json"
    destination.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    destination.chmod(0o400)
    if output.lstat().st_ino != output_identity.st_ino or output.lstat().st_dev != output_identity.st_dev or output.is_symlink():
        raise ValueError("Prepared output root changed during copy")
    print(json.dumps({"state": "prepared_not_executed", "manifest": str(destination),
                      "files": len(records), "bytes": total, "omissions": omitted}))


if __name__ == "__main__":
    main()
