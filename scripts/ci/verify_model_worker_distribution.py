#!/usr/bin/env python3
"""Read-only comparison of a built model-worker JAR and its isolated installation.

No extraction, subprocess, network, JVM, provider or model execution. Only an
explicit NEW report outside the installation may be written. Inputs must have
link-free paths and independent regular files. Before/after stat checks detect
observed drift; they are not an atomic snapshot or hostile-writer sandbox.
Class-file headers are checked, not JVM bytecode validity or historical build
provenance. Use the actual Gradle task output recorded in the dependency manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import struct
import zipfile
import zlib


APPLICATION_TASK = ":provider-compiler:compilerModelApplicationWorkerJar"
WORKER_FILENAME = "ktchecker-compiler-model-application-preview-1.jar"
WORKER_RELATIVE = "providers/compiler-model/" + WORKER_FILENAME
MODEL_BRIDGE_CLASSES = (
    "dev/ktchecker/workerprotocol/ApplicationWire",
    "dev/ktchecker/workerprotocol/CompilerModelWire",
    "dev/ktchecker/compilerworker/live/CompilerModelApplicationMain",
    "dev/ktchecker/compilerworker/live/PinnedKotlinModelCompilerAdapter",
)
MAIN_CLASS = "dev.ktchecker.compilerworker.live.CompilerModelApplicationMain"
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_WORKER_ENTRIES = 512
MAX_ORDINARY_JAR_BYTES = 128 * 1024 * 1024
MAX_ORDINARY_BYTES = 512 * 1024 * 1024
MAX_ORDINARY_ENTRIES = 250000
MAX_TREE_ENTRIES = 4096
MAX_ORDINARY_JARS = 256
SCOPE = ("Observed installed/task-output byte equality, reviewed class families and "
         "Java21 headers, manifest and ordinary-JAR isolation only; no historical "
         "source/build provenance, JVM verification, execution, license selection, "
         "signing, publication, release qualification or hostile-writer atomicity.")


class WorkerVerificationError(ValueError):
    """Fixed diagnostics; never include untrusted names, content or host paths."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def require(condition: bool, code: str) -> None:
    if not condition:
        raise WorkerVerificationError(code)


def fingerprint(info: os.stat_result) -> tuple:
    return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
            info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def physical_identity(observed: tuple) -> tuple[int, int]:
    device, inode = observed[:2]
    require(type(device) is int and device >= 0 and type(inode) is int and inode > 0,
            "physical_identity_unavailable")
    return device, inode


def checked_path(path: Path, *, directory: bool = False) -> tuple[Path, os.stat_result]:
    require(".." not in path.parts, "unsafe_input_path")
    path = path.absolute()
    require(len(os.fsencode(path)) <= 8192 and len(path.parts) <= 128, "input_path_limit")
    for part in reversed((path, *path.parents)):
        info = part.lstat()
        require(not stat.S_ISLNK(info.st_mode) and not
                (getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)),
                "linked_input_path")
        if part != path or directory:
            require(stat.S_ISDIR(info.st_mode), "input_directory_required")
        else:
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, "independent_regular_file_required")
    return path, info


def read_file(path: Path, limit: int) -> tuple[bytes, tuple]:
    path, initial = checked_path(path)
    require(0 < initial.st_size <= limit, "file_byte_limit")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    with os.fdopen(os.open(path, flags), "rb") as handle:
        require(fingerprint(os.fstat(handle.fileno())) == fingerprint(initial), "observed_input_drift")
        raw = handle.read(limit + 1)
        require(len(raw) == initial.st_size and len(raw) <= limit, "file_byte_limit_or_drift")
        require(fingerprint(os.fstat(handle.fileno())) == fingerprint(initial), "observed_input_drift")
    require(fingerprint(checked_path(path)[1]) == fingerprint(initial), "observed_input_drift")
    return raw, fingerprint(initial)


def open_zip(raw: bytes, *, worker: bool) -> zipfile.ZipFile:
    # Bound central-directory allocation before the standard parser constructs
    # ZipInfo objects. Current distributions need only single-volume ZIP32 JARs.
    offset = raw.rfind(b"PK\x05\x06", max(0, len(raw) - 65557))
    require(offset >= 0 and len(raw) - offset >= 22, "zip_end_record_required")
    _, disk, central_disk, disk_count, count, size, start, comment = struct.unpack_from("<4s4H2IH", raw, offset)
    require(disk == central_disk == 0 and disk_count == count and count != 65535 and
            start != 0xffffffff and size != 0xffffffff and start + size == offset and
            offset + 22 + comment == len(raw), "unsupported_zip_layout")
    require(0 < count <= (MAX_WORKER_ENTRIES if worker else MAX_ORDINARY_ENTRIES) and
            size <= (256 * 1024 if worker else 16 * 1024 * 1024), "zip_inventory_limit")
    require(not worker or (comment == 0 and raw.startswith(b"PK\x03\x04")), "unsupported_worker_zip_layout")
    archive = zipfile.ZipFile(io.BytesIO(raw))
    if len(archive.infolist()) != count:
        archive.close()
        raise WorkerVerificationError("zip_entry_count_mismatch")
    return archive


def class_allowed(name: str, families: tuple[str, ...] = MODEL_BRIDGE_CLASSES) -> bool:
    return any(name == family + ".class" or
               (name.startswith(family + "$") and name.endswith(".class") and
                "/" not in name[len(family):] and "\\" not in name[len(family):])
               for family in families)


def worker_archive(raw: bytes) -> dict:
    require(0 < len(raw) <= MAX_ARCHIVE_BYTES, "worker_byte_limit")
    directories = {"META-INF/"}
    for family in MODEL_BRIDGE_CLASSES:
        parts = family.split("/")
        directories.update("/".join(parts[:index]) + "/" for index in range(1, len(parts)))
    with open_zip(raw, worker=True) as archive:
        entries = archive.infolist()
        require(sum(entry.file_size for entry in entries) <= MAX_ARCHIVE_BYTES, "expanded_worker_byte_limit")
        names, classes, manifest = set(), [], None
        for entry in entries:
            name = entry.orig_filename
            require(name == entry.filename and re.fullmatch(r"[A-Za-z0-9_$/.-]+", name) is not None and
                    not name.startswith("/") and all(part not in ("", ".", "..")
                    for part in name.removesuffix("/").split("/")), "unsafe_worker_entry")
            require(name not in names, "duplicate_worker_entry")
            names.add(name)
            require(entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) and
                    not entry.flag_bits & (1 | 64), "unsupported_worker_entry_encoding")
            kind = stat.S_IFMT(entry.external_attr >> 16)
            require(kind in (0, stat.S_IFDIR if entry.is_dir() else stat.S_IFREG), "nonregular_worker_entry")
            if entry.is_dir():
                require(name in directories and entry.file_size == 0, "unreviewed_worker_directory")
            else:
                require(name == "META-INF/MANIFEST.MF" or class_allowed(name), "unreviewed_worker_file")
            # Read all bounded bytes, not just headers: ZIP CRC/stream errors
            # anywhere in a class or manifest must not become a packaging pass.
            data = archive.read(entry)
            require(len(data) == entry.file_size, "worker_entry_length_mismatch")
            if name == "META-INF/MANIFEST.MF":
                require(len(data) <= 8192, "worker_manifest_byte_limit")
                manifest = data.decode("utf-8", errors="strict")
            elif not entry.is_dir():
                require(len(data) >= 8 and data[:6] == b"\xca\xfe\xba\xbe\x00\x00" and
                        struct.unpack(">H", data[6:8])[0] == 65, "java21_class_header_required")
                classes.append(name)
        require(all(family + ".class" in classes for family in MODEL_BRIDGE_CLASSES), "required_worker_family_missing")
        require(manifest is not None, "worker_manifest_missing")
        require("\r" not in manifest.replace("\r\n", ""), "malformed_worker_manifest")
        lines = manifest.replace("\r\n", "\n").replace("\n ", "")
        require(lines.endswith("\n\n") and "\n\n" not in lines[:-2], "worker_manifest_sections_forbidden")
        headers = {}
        for line in lines[:-2].split("\n"):
            require(re.fullmatch(r"[A-Za-z0-9-]+: [^\x00-\x1f\x7f]+", line) is not None, "malformed_worker_manifest")
            key, value = line.split(": ", 1)
            key = key.lower()
            require(key not in headers, "duplicate_worker_manifest_header")
            headers[key] = value
        require(headers == {"manifest-version": "1.0", "main-class": MAIN_CLASS}, "unexpected_worker_manifest_headers")
    return {"class_entries": sorted(classes), "entry_count": len(entries), "main_class": MAIN_CLASS,
            "class_file_major_version": 65, "class_validation": "headers_only_not_jvm_verification"}


def ordinary_inventory(installation: Path) -> dict[Path, tuple]:
    inventory = {}
    pending = [installation / "lib", installation / "providers/compiler"]
    while pending:
        directory, info = checked_path(pending.pop(), directory=True)
        inventory[directory] = fingerprint(info)
        with os.scandir(directory) as entries:
            for entry in entries:
                require(len(inventory) < MAX_TREE_ENTRIES, "ordinary_tree_entry_limit")
                path = Path(entry.path)
                if entry.is_dir(follow_symlinks=False):
                    _, info = checked_path(path, directory=True)
                    pending.append(path)
                else:
                    _, info = checked_path(path)
                inventory[path] = fingerprint(info)
    return inventory


def isolated_directory(installation: Path) -> tuple:
    directory, info = checked_path(installation / "providers/compiler-model", directory=True)
    count = 0
    with os.scandir(directory) as entries:
        for entry in entries:
            count += 1
            require(entry.name == WORKER_FILENAME and count == 1, "isolated_worker_directory_not_exact")
    require(count == 1, "installed_worker_missing")
    return fingerprint(info)


def verify(installation: Path, built_worker: Path) -> dict:
    try:
        installation, _ = checked_path(installation, directory=True)
        built_worker, _ = checked_path(built_worker)
        require(not built_worker.is_relative_to(installation), "built_worker_must_be_separate")
        require(built_worker.name == WORKER_FILENAME, "reviewed_worker_filename_required")
        isolated = isolated_directory(installation)
        installed = installation / WORKER_RELATIVE
        built, built_stat = read_file(built_worker, MAX_ARCHIVE_BYTES)
        packaged, installed_stat = read_file(installed, MAX_ARCHIVE_BYTES)
        # Link-free path spellings and st_nlink==1 alone do not distinguish
        # case-insensitive/bind aliases of the very same file.
        require(physical_identity(built_stat) != physical_identity(installed_stat), "built_worker_must_be_independent")
        require(built == packaged, "installed_worker_bytes_differ")
        contents = worker_archive(packaged)
        ordinary = ordinary_inventory(installation)
        jar_count, total_bytes = 0, 0
        for path in sorted(ordinary):
            if stat.S_ISDIR(ordinary[path][2]) or path.suffix.lower() != ".jar":
                continue
            jar_count += 1
            total_bytes += ordinary[path][4]
            require(jar_count <= MAX_ORDINARY_JARS and total_bytes <= MAX_ORDINARY_BYTES, "ordinary_jar_inventory_limit")
            require(path.name.casefold() != WORKER_FILENAME.casefold(), "worker_in_ordinary_scan")
            raw, observed = read_file(path, MAX_ORDINARY_JAR_BYTES)
            require(observed == ordinary[path], "observed_input_drift")
            require(raw != packaged, "worker_in_ordinary_scan")
            with open_zip(raw, worker=False) as archive:
                for entry in archive.infolist():
                    # Also catch renamed and multi-release copies. The shared
                    # wire families legitimately occur in the engine libraries.
                    require(not any(re.search(re.escape(family) + r"(?:\$[^/\\]*)?\.class$", entry.orig_filename)
                                    for family in MODEL_BRIDGE_CLASSES[2:]), "worker_class_in_ordinary_scan")
        require(jar_count > 0, "ordinary_jars_missing")
        require(ordinary_inventory(installation) == ordinary and isolated_directory(installation) == isolated and
                fingerprint(checked_path(built_worker)[1]) == built_stat and
                fingerprint(checked_path(installed)[1]) == installed_stat, "observed_input_drift")
    except WorkerVerificationError:
        raise
    except (OSError, ValueError, RuntimeError, EOFError, struct.error, zipfile.BadZipFile, zlib.error):
        raise WorkerVerificationError("input_unreadable_or_malformed") from None
    digest = "sha256:" + hashlib.sha256(packaged).hexdigest()
    return {"schema_version": 1, "evidence_kind": "installed_compiler_model_worker_packaging", "state": "verified",
            "scope": SCOPE, "expected_worker_task": APPLICATION_TASK, "installed_path": WORKER_RELATIVE,
            "built_digest": digest, "installed_digest": digest, "bytes": len(packaged), **contents,
            "ordinary_jars_checked": jar_count, "worker_execution": False, "compiler_provider_execution": False,
            "release_qualified": False, "publication": "not_performed", "license_selection": "not_performed"}


def failure(code: str) -> dict:
    return {"schema_version": 1, "evidence_kind": "installed_compiler_model_worker_packaging", "state": "failed",
            "scope": SCOPE, "errors": [code], "worker_execution": False, "compiler_provider_execution": False,
            "release_qualified": False, "publication": "not_performed", "license_selection": "not_performed"}


def installation_directory_identities(installation: Path) -> set[tuple[int, int]]:
    """Bounded metadata-only walk: an output alias may target any image subtree."""
    pending, observed, identities, count = [installation], {}, set(), 0
    while pending:
        directory, info = checked_path(pending.pop(), directory=True)
        identity = physical_identity(fingerprint(info))
        require(identity not in identities, "aliased_installation_directory")
        identities.add(identity)
        observed[directory] = fingerprint(info)
        with os.scandir(directory) as entries:
            for entry in entries:
                count += 1
                require(count <= MAX_TREE_ENTRIES, "installation_tree_entry_limit")
                if entry.is_dir(follow_symlinks=False):
                    pending.append(Path(entry.path))
                else:
                    checked_path(Path(entry.path))
    require(all(fingerprint(checked_path(path, directory=True)[1]) == value for path, value in observed.items()),
            "observed_input_drift")
    return identities


def write_new_report(output: Path, result: dict, installation: Path, built_worker: Path) -> None:
    output = output.absolute()
    checked_path(output.parent, directory=True)
    require(not output.is_relative_to(installation.absolute()) and not output.is_relative_to(installation.resolve()) and
            output != built_worker.absolute(), "unsafe_report_destination")
    # Lexical resolve() is not a physical containment test on case-insensitive
    # volumes or bind aliases. No existing installed directory may be an output
    # ancestor. Unavailable identities fail closed; this is not a race sandbox.
    installed_directories = installation_directory_identities(installation)
    for parent in output.parents:
        _, info = checked_path(parent, directory=True)
        require(physical_identity(fingerprint(info)) not in installed_directories, "report_directory_aliases_installation")
    raw = (json.dumps(result, indent=2, sort_keys=True) + "\n").encode("utf-8")
    require(len(raw) <= 256 * 1024, "report_byte_limit")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    with os.fdopen(os.open(output, flags, 0o600), "wb") as handle:
        require(handle.write(raw) == len(raw), "report_write_incomplete")
    require(read_file(output, 256 * 1024)[0] == raw, "report_readback_failed")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--installation", type=Path, required=True)
    parser.add_argument("--built-worker", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New report; existing link-free parent required")
    args = parser.parse_args(argv)
    try:
        result = verify(args.installation, args.built_worker)
    except WorkerVerificationError as error:
        result = failure(error.code)
    try:
        write_new_report(args.output, result, args.installation, args.built_worker)
    except (OSError, ValueError):
        print("Compiler-model worker packaging: report delivery failed")
        return 1
    print("Compiler-model worker packaging: " + result["state"] + "; new report delivered")
    return 0 if result["state"] == "verified" else 1


if __name__ == "__main__":
    raise SystemExit(main())
