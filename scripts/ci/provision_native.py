#!/usr/bin/env python3
"""Enroll one pinned Native distribution on an ephemeral hosted arm64 Mac.

Only availability is established here. There is no backend bootstrap, target
compilation, generated-binary execution, or Native-throws build authority.
"""

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import tarfile
import tempfile
import time
import zipfile

if __package__:
    from .ci_common import REPO_ROOT, canonical_digest, digest_bytes, digest_file, run_logged, source_identity, utc_now, write_json
    from .run_library_checks import regular_input, root_relative, strict_json
else:
    from ci_common import REPO_ROOT, canonical_digest, digest_bytes, digest_file, run_logged, source_identity, utc_now, write_json
    from run_library_checks import regular_input, root_relative, strict_json


VERSION = "2.4.21"
TARGETS = {"iosArm64": "ios_arm64", "iosSimulatorArm64": "ios_simulator_arm64"}
HOME_NAME = "kotlin-native-prebuilt-macos-aarch64-2.4.21"
COORDINATE = "org.jetbrains.kotlin:kotlin-native-prebuilt:2.4.21:macos-aarch64@tar.gz"
ARCHIVE_URL = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-native-prebuilt/2.4.21/kotlin-native-prebuilt-2.4.21-macos-aarch64.tar.gz"
ARCHIVE_SIZE = 290_160_802
ARCHIVE_SHA1 = "bfa7a92e6654ac8f060648a2937ec08f8d4b1c49"
# Author-enrolled SHA-256, NOT upstream-published or signature-verified. One
# authorized fixed-HTTPS acquisition on 2026-11-04 verified exact bytes and the
# official Maven .sha1. Official .sha256 and .sha512 each returned HTTP 404.
# Private author acquisition receipt SHA-256:
# f3bf8f647cf47d0a56fc477e6c92bf0eef7c944560bba8c589d611683547a35a
ARCHIVE_SHA256 = "bf4edb526d91e17d0862102f69250525d788e79011f86c2c64362749adf260ae"
CHECKSUM_PROVENANCE = {
    "published_checksum_algorithm": "sha1", "published_checksum": ARCHIVE_SHA1,
    "published_checksum_url": ARCHIVE_URL + ".sha1",
    "published_sha256_http_status_at_enrollment": 404, "published_sha512_http_status_at_enrollment": 404,
    "sha256_origin": "author_enrolled_fixed_https_exact_size_and_published_sha1_verified",
    "author_acquired_at": "2026-11-04T14:51:07+00:00", "signature_verified": False,
}
MAX_ARCHIVE_BYTES = 384 * 1024 * 1024
MAX_EXTRACTED_BYTES = 4 * 1024 * 1024 * 1024
MAX_FILE_BYTES = 512 * 1024 * 1024
MAX_MEMBERS = 100_000
MAX_METADATA_BYTES = 64 * 1024 * 1024
MAX_EXTENSION_DEPTH = 16
MAX_PATH_COMPONENTS = 64
MAX_READ_BYTES = 1024 * 1024
# Regular bytes plus bounded metadata, headers and per-file tar padding.
MAX_TAR_STREAM_BYTES = MAX_EXTRACTED_BYTES + MAX_METADATA_BYTES + MAX_MEMBERS * 1024
EXTRACTION_TIMEOUT_SECONDS = 300
MAX_COMPONENTS = 20_000
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
PART = re.compile(r"[A-Za-z0-9_.+-]{1,200}")
CORE_FILES = ("bin/konanc", "bin/run_konan", "tools/env_blacklist", "konan/konan.properties",
              "konan/lib/kotlin-native-compiler-embeddable.jar")
JAVA_FILES = ("bin/java", "release", "lib/modules")
LIMITATIONS = [
    "Same-job Native distribution/JDK launcher availability only; not target compilation, linking, cinterop, simulator or runtime evidence.",
    "Trust boundary is fresh/quiescent ephemeral GitHub-hosted state with trusted same-job scripts and no hostile concurrent same-UID writer; pathname rechecks are not race-proof capability binding or host attestation.",
    "The enrolled official archive was acquired and hashed only, never listed/extracted/executed during author enrollment; its actual layout, metadata and link-policy compatibility remain unvalidated until hosted provisioning.",
    "Extraction uses bounded read/parser work and a cooperative 300-second deadline, not a preemptive hard real-time guarantee.",
    "SHA-256 was author-enrolled from one exact official HTTPS acquisition verified against published SHA-1; no upstream SHA-256 or signature validation is claimed.",
    "Selected component hashes do not qualify the complete LLVM/libffi/Apple SDK/header/cache closure or the whole JDK distribution.",
    "No dependency bootstrap or no-source dependency-readiness claim; actual offline library compiles remain a separate acceptance gate.",
    "This availability receipt is not independent Native-throws build authority, rule/tuple qualification, or release evidence.",
    "Private paths, archive, selection, tool streams and commands are retained locally, never uploaded.",
]


class NativePrerequisiteError(ValueError):
    """A fixed caller-selected code; never expose a transport/parser message."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def fail(code: str):
    raise NativePrerequisiteError(code)


def publish_json_no_clobber(path: Path, value: object) -> None:
    """Publish sanitized JSON without replacing any destination or fixed temp.

    A private, exclusively created sibling is linked into an absent destination.
    Neither a pre-existing destination nor the historical ``name.json.tmp``
    namespace is opened for writing. This is no-clobber publication, not a claim
    of capability-safe protection against an excluded concurrent same-UID writer.
    """
    if path.exists() or path.is_symlink():
        fail("native_provisioning_output_not_fresh")
    descriptor, temporary = None, None
    try:
        encoded = (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, name = tempfile.mkstemp(prefix="." + path.name + ".", suffix=".pending", dir=path.parent)
        temporary = Path(name)
        stream = os.fdopen(descriptor, "wb")
        descriptor = None
        with stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temporary, path, follow_symlinks=False)
        except FileExistsError:
            fail("native_provisioning_output_not_fresh")
    except NativePrerequisiteError:
        raise
    except (OSError, ValueError, TypeError):
        fail("native_evidence_publication_failed")
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary is not None:
            # Scratch contains sanitized JSON only and does not match the
            # uploaded *.json glob. A cleanup failure cannot invalidate the
            # already-published observations or justify replacing a receipt.
            try:
                temporary.unlink()
            except OSError:
                pass


def build_path(path: Path, root: Path, *, private: bool = False) -> Path:
    try:
        relative = root_relative(path, root)
        if not relative.parts or relative.parts[0] != "build" or private and "raw" not in relative.parts[1:]:
            fail("native_evidence_path_unsafe")
        current = root.resolve()
        for part in relative.parts:
            current /= part
            if current.is_symlink():
                fail("native_evidence_path_unsafe")
        return current
    except (OSError, ValueError) as error:
        if isinstance(error, NativePrerequisiteError):
            raise
        fail("native_evidence_path_unsafe")


def selected_file(base: Path, relative: str) -> Path:
    if not isinstance(relative, str) or not relative or len(relative) > 1600 \
            or any(not PART.fullmatch(part) or part in {".", ".."} for part in relative.split("/")):
        fail("native_component_path_unsafe")
    current = base
    for part in relative.split("/"):
        current /= part
        if current.is_symlink():
            fail("native_component_path_unsafe")
    if not current.is_file():
        fail("native_component_missing")
    return current


def run_binding() -> dict:
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" \
            or os.environ.get("RUNNER_OS") != "macOS" or os.environ.get("RUNNER_ARCH") != "ARM64":
        fail("native_hosted_context_required")
    result = {key: os.environ.get(variable, "") for key, variable in
              (("id", "GITHUB_RUN_ID"), ("attempt", "GITHUB_RUN_ATTEMPT"), ("job", "GITHUB_JOB"))}
    if any(not re.fullmatch(r"[1-9][0-9]{0,19}", result[key]) for key in ("id", "attempt")) \
            or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", result["job"]):
        fail("native_hosted_run_binding_unavailable")
    return result


def properties(path: Path, keys: set[str], limit: int = 1024 * 1024) -> dict:
    if path.stat().st_size > limit:
        fail("native_component_metadata_oversized")
    result = {}
    try:
        text = path.read_bytes().decode("utf-8")
    except (OSError, UnicodeError):
        fail("native_component_metadata_invalid")
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() in keys and not line.lstrip().startswith(("#", "!")):
            key = key.strip()
            if key in result:
                fail("native_component_metadata_ambiguous")
            result[key] = value.strip().strip('"')
    return result


def observed_prerequisites(path: Path, root: Path, source: dict) -> tuple[dict, Path, dict]:
    if platform.system() != "Darwin":
        fail("native_host_os_unavailable")
    if platform.machine() not in {"arm64", "aarch64"}:
        fail("native_host_arch_unavailable")
    binding = run_binding()
    if not path.exists():
        fail("native_environment_missing")
    try:
        environment = strict_json(regular_input(path, root))
    except (OSError, ValueError):
        fail("native_environment_invalid_or_unsafe")
    if type(environment.get("schema_version")) is not int or environment["schema_version"] != 1 \
            or environment.get("evidence_kind") != "observed_environment":
        fail("native_environment_invalid_or_unsafe")
    observed_source = environment.get("source", {})
    if not isinstance(observed_source, dict) or any(observed_source.get(key) != source.get(key) for key in ("commit", "working_tree_digest")):
        fail("native_environment_source_mismatch")
    ci = environment.get("ci", {})
    if not isinstance(ci, dict) or any(ci.get(variable) != binding[key] for key, variable in
                                      (("id", "GITHUB_RUN_ID"), ("attempt", "GITHUB_RUN_ATTEMPT"), ("job", "GITHUB_JOB"))):
        fail("native_environment_run_mismatch")
    host = environment.get("host", {})
    if not isinstance(host, dict) or host.get("os") != "Darwin" or host.get("arch") not in {"arm64", "aarch64"}:
        fail("native_observed_host_mismatch")
    java, xcode, gradle = (environment.get(key, {}) for key in ("java", "xcode", "gradle"))
    if not isinstance(java, dict) or type(java.get("major")) is not int:
        fail("native_java_unobserved")
    if java["major"] != 25:
        fail("native_java_major_mismatch")
    if not isinstance(xcode, dict) or xcode.get("state") != "observed":
        fail("native_xcode_unobserved")
    if xcode.get("version") != "26.4.1":
        fail("native_xcode_version_mismatch")
    if xcode.get("build") != "17E202":
        fail("native_xcode_build_mismatch")
    if not isinstance(gradle, dict) or gradle.get("state") != "pinned":
        fail("native_gradle_not_pinned")
    value = os.environ.get("JAVA_HOME")
    if not value or not Path(value).is_absolute():
        fail("native_java_home_missing_or_unsafe")
    try:
        java_home = Path(value).resolve(strict=True)
        java_path, release = (selected_file(java_home, relative) for relative in JAVA_FILES[:2])
        selected_file(java_home, "lib/modules")
    except (OSError, NativePrerequisiteError):
        fail("native_java_distribution_incomplete_or_unsafe")
    if not os.access(java_path, os.X_OK):
        fail("native_java_launcher_unexecutable")
    if not isinstance(java.get("path"), str) or Path(java["path"]).resolve() != java_path \
            or digest_file(java_path) != java.get("launcher_digest") or digest_file(release) != java.get("release_file_digest"):
        fail("native_selected_java_changed")
    release_properties = properties(release, {"JAVA_VERSION", "JAVA_RUNTIME_VERSION", "IMPLEMENTOR"}, 65536)
    if not isinstance(java.get("release_properties"), dict):
        fail("native_java_release_mismatch")
    if not re.fullmatch(r"25(?:\.[0-9]+)*(?:[+_-][A-Za-z0-9._+-]+)?", release_properties.get("JAVA_VERSION", "")) \
            or release_properties != {key: item for key, item in java.get("release_properties", {}).items()
                                      if key in {"JAVA_VERSION", "JAVA_RUNTIME_VERSION", "IMPLEMENTOR"}}:
        fail("native_java_release_mismatch")
    developer = os.environ.get("DEVELOPER_DIR")
    tool_paths = environment.get("tool_paths", {})
    if not developer or not Path(developer).is_absolute() or not isinstance(tool_paths, dict) or tool_paths.get("DEVELOPER_DIR") != developer:
        fail("native_xcode_selection_changed_or_unavailable")
    selected_xcode = {"version": "26.4.1", "build": "17E202"}
    for key in ("iphoneos_sdk", "iphonesimulator_sdk"):
        value = xcode.get(key)
        observed = isinstance(value, str) and re.fullmatch(r"[0-9]{1,3}(?:\.[0-9]{1,3}){0,2}", value) is not None
        selected_xcode[key] = {"state": "observed" if observed else "unobserved", "version": value if observed else None}
    return environment, java_home, selected_xcode


def process_projection(value: dict) -> dict:
    result = {"state": value.get("state") if value.get("state") in
              {"not_started", "completed", "unavailable", "timeout", "output_limit", "cleanup_unverified"} else "invalid",
              "return_code": value.get("return_code") if type(value.get("return_code")) is int and -255 <= value["return_code"] <= 255 else None,
              "raw_logs_uploaded": False}
    for key in ("stdout_digest", "stderr_digest"):
        if isinstance(value.get(key), str) and DIGEST.fullmatch(value[key]):
            result[key] = value[key]
    for key in ("elapsed_seconds", "output_bytes"):
        item = value.get(key)
        if type(item) in {int, float} and 0 <= item <= 1_000_000_000:
            result[key] = item
    return result


def archive_digests(path: Path) -> tuple[int, str, str]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_ARCHIVE_BYTES:
        fail("native_archive_size_or_type_invalid")
    sha1, sha256, size = hashlib.sha1(), hashlib.sha256(), 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(block)
            if size > MAX_ARCHIVE_BYTES:
                fail("native_archive_size_or_type_invalid")
            sha1.update(block)
            sha256.update(block)
    return size, sha1.hexdigest(), sha256.hexdigest()


def verify_archive(path: Path) -> None:
    size, sha1, sha256 = archive_digests(path)
    if size != ARCHIVE_SIZE:
        fail("native_archive_size_mismatch")
    if sha1 != ARCHIVE_SHA1:
        fail("native_archive_published_sha1_mismatch")
    if sha256 != ARCHIVE_SHA256:
        fail("native_archive_enrolled_sha256_mismatch")


def acquire_archive(path: Path, private: Path, result: dict) -> None:
    # --disable must be first: never load an ambient .curlrc. No redirects,
    # proxies, retry, ambient credentials or alternate repositories are used.
    command = ["/usr/bin/curl", "--disable", "--fail", "--silent", "--show-error", "--proto", "=https",
               "--proxy", "", "--max-time", "590", "--connect-timeout", "20", "--max-filesize", str(MAX_ARCHIVE_BYTES),
               "--max-redirs", "0", "--retry", "0", "--output", str(path), "--write-out", "%{http_code}", ARCHIVE_URL]
    result.update(phase="acquisition", distribution_acquisition_attempted=True, state="failed")
    execution, stdout, _ = run_logged(command, cwd=private, log_base=private / "transport", timeout=600,
                                    max_output_bytes=65536, env={"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"})
    result["acquisition"] = process_projection(execution)
    if execution.get("state") == "unavailable":
        fail("native_archive_transport_start_failed")
    if execution.get("state") == "timeout":
        fail("native_archive_transport_timeout")
    if execution.get("state") != "completed" or execution.get("return_code") != 0:
        fail("native_archive_transport_failed")
    if stdout != "200":
        fail("native_archive_http_response_not_200")
    result["phase"] = "archive_verification"
    verify_archive(path)
    result["archive_verified"] = True


METADATA_TYPES = {tarfile.XHDTYPE, tarfile.XGLTYPE, tarfile.SOLARIS_XHDTYPE,
                  tarfile.GNUTYPE_LONGNAME, tarfile.GNUTYPE_LONGLINK}
ENTRY_TYPES = {tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE, tarfile.SYMTYPE, tarfile.LNKTYPE}


def effective_member_bounds(member) -> None:
    if member.issparse() or member.type == tarfile.GNUTYPE_SPARSE:
        fail("native_archive_sparse_unsupported")
    if member.size < 0 or member.size > MAX_FILE_BYTES:
        fail("native_archive_member_bound_exceeded")


class ExtractionBudget:
    def __init__(self):
        self.deadline = time.monotonic() + EXTRACTION_TIMEOUT_SECONDS
        self.headers = self.metadata_bytes = self.extension_depth = 0
        self.compressed_bytes = self.stream_bytes = 0

    def check(self):
        if time.monotonic() > self.deadline:
            fail("native_archive_extraction_timeout")


class BoundedReader:
    """Limit both gzip's compressed/header reads and tar's decompressed reads."""

    def __init__(self, stream, budget: ExtractionBudget, *, compressed: bool):
        self.stream, self.budget, self.compressed = stream, budget, compressed

    def read(self, size=-1):
        self.budget.check()
        if type(size) is not int or size < 0 or size > MAX_READ_BYTES:
            fail("native_archive_parser_read_bound_exceeded")
        counter = "compressed_bytes" if self.compressed else "stream_bytes"
        limit = MAX_ARCHIVE_BYTES if self.compressed else MAX_TAR_STREAM_BYTES
        remaining = limit - getattr(self.budget, counter)
        block = self.stream.read(min(size, remaining + 1))
        self.budget.check()
        setattr(self.budget, counter, getattr(self.budget, counter) + len(block))
        if getattr(self.budget, counter) > limit:
            fail("native_archive_compressed_byte_bound_exceeded" if self.compressed else "native_archive_stream_byte_bound_exceeded")
        return block


class BoundedTarFile(tarfile.TarFile):
    def __init__(self, *args, native_budget: ExtractionBudget, **kwargs):
        # TarFile reads its first member inside __init__: install the budget
        # before that parse, rather than attaching it after open() returns.
        self.native_budget = native_budget
        super().__init__(*args, **kwargs)


class BoundedTarInfo(tarfile.TarInfo):
    def _proc_member(self, archive):
        # Operative parser hook: current Python 3.12 internally recurses via
        # _fromtarfile/_frombuf, bypassing public fromtarfile/frombuf overrides.
        # This hook runs for each raw GNU/PAX header BEFORE payload processing.
        budget = archive.native_budget
        budget.check()
        budget.headers += 1
        if budget.headers > MAX_MEMBERS:
            fail("native_archive_member_bound_exceeded")
        effective_member_bounds(self)
        metadata = self.type in METADATA_TYPES
        if not metadata and self.type not in ENTRY_TYPES:
            fail("native_archive_member_type_or_bound_invalid")
        if metadata:
            budget.metadata_bytes += self.size
            if self.size > 65536 or budget.metadata_bytes > MAX_METADATA_BYTES:
                fail("native_archive_metadata_bound_exceeded")
            if budget.extension_depth >= MAX_EXTENSION_DEPTH:
                fail("native_archive_extension_depth_exceeded")
            budget.extension_depth += 1
        try:
            member = super()._proc_member(archive)
            budget.check()
            effective_member_bounds(member)
            return member
        finally:
            if metadata:
                budget.extension_depth -= 1

    def _apply_pax_info(self, headers, encoding, errors):
        if any(key.startswith("GNU.sparse.") or key in {"SCHILY.realsize", "SUN.holesdata"}
               or key == "SCHILY.filetype" and value == "sparse" for key, value in headers.items()):
            fail("native_archive_sparse_unsupported")
        super()._apply_pax_info(headers, encoding, errors)
        # PAX can replace the raw header size; reject before tarfile computes
        # an effective skip/offset or the caller starts reading file content.
        effective_member_bounds(self)

    def _proc_gnusparse_00(self, *args):
        fail("native_archive_sparse_unsupported")

    def _proc_gnusparse_01(self, *args):
        fail("native_archive_sparse_unsupported")

    def _proc_gnusparse_10(self, *args):
        # In particular, never enter tarfile's internal sparse-map read loop.
        fail("native_archive_sparse_unsupported")


def archive_parts(name: str) -> tuple[str, ...]:
    parts = tuple(name.rstrip("/").split("/"))
    if len(name) > 1600 or not parts or len(parts) > MAX_PATH_COMPONENTS or parts[0] != HOME_NAME \
            or any(not PART.fullmatch(part) or part in {".", ".."} for part in parts):
        fail("native_archive_path_unsafe")
    return parts


def link_target(parts: tuple[str, ...], member) -> tuple[str, ...]:
    text = member.linkname
    if not text or text.startswith("/") or "\\" in text or ":" in text or len(text) > 1600:
        fail("native_archive_link_unsafe")
    base = list(parts[:-1]) if member.issym() else []
    for part in text.split("/"):
        if part == "..":
            if len(base) <= 1:
                fail("native_archive_link_unsafe")
            base.pop()
        elif part not in {"", "."}:
            if not PART.fullmatch(part):
                fail("native_archive_link_unsafe")
            base.append(part)
    if not base or len(base) > MAX_PATH_COMPONENTS or base[0] != HOME_NAME:
        fail("native_archive_link_unsafe")
    return tuple(base)


def extract_archive(path: Path, staging: Path) -> dict:
    staging.mkdir(mode=0o700)
    seen, spellings, regular, links, extracted = set(), {}, set(), [], 0
    budget = ExtractionBudget()
    try:
        with path.open("rb") as compressed, gzip.GzipFile(
                fileobj=BoundedReader(compressed, budget, compressed=True), mode="rb") as uncompressed, \
                BoundedTarFile.open(fileobj=BoundedReader(uncompressed, budget, compressed=False), mode="r|",
                                    tarinfo=BoundedTarInfo, native_budget=budget) as archive:
            for member in archive:
                budget.check()
                parts = archive_parts(member.name)
                # Enroll every implicit directory spelling too. Leaf-only
                # checks would merge HOME/Foo/a and HOME/foo/b on macOS.
                for length in range(1, len(parts) + 1):
                    prefix = parts[:length]
                    prefix_key = "/".join(prefix).casefold()
                    if prefix_key in spellings and spellings[prefix_key] != prefix:
                        fail("native_archive_case_collision")
                    spellings[prefix_key] = prefix
                    if len(spellings) > MAX_MEMBERS:
                        fail("native_archive_namespace_bound_exceeded")
                key = "/".join(parts).casefold()
                if key in seen:
                    fail("native_archive_duplicate_entry")
                seen.add(key)
                if len(seen) > MAX_MEMBERS or not (member.isdir() or member.isfile() or member.issym() or member.islnk()) \
                        or member.issparse():
                    fail("native_archive_member_type_or_bound_invalid")
                destination = staging.joinpath(*parts)
                destination.parent.mkdir(parents=True, exist_ok=True)
                if member.isdir():
                    destination.mkdir(exist_ok=True)
                elif member.isfile():
                    extracted += member.size
                    if extracted > MAX_EXTRACTED_BYTES or member.size > MAX_FILE_BYTES:
                        fail("native_archive_extracted_byte_bound_exceeded")
                    stream = archive.extractfile(member)
                    if stream is None:
                        fail("native_archive_corrupt")
                    remaining = member.size
                    with stream, destination.open("xb") as output:
                        while remaining:
                            budget.check()
                            block = stream.read(min(1024 * 1024, remaining))
                            if not block:
                                fail("native_archive_corrupt")
                            output.write(block)
                            remaining -= len(block)
                    destination.chmod(0o755 if member.mode & 0o111 else 0o644)
                    regular.add(parts)
                else:
                    links.append((parts, link_target(parts, member), member.issym()))
        # Publish links only after every entry was validated/written. Links may
        # target only an archive-enrolled regular file, never a directory/link.
        for parts, target, symbolic in links:
            budget.check()
            if target not in regular or any(other[:len(parts)] == parts for other in regular) \
                    or any(other != parts and other[:len(parts)] == parts for other, _, _ in links):
                fail("native_archive_link_unsafe")
            destination, source = staging.joinpath(*parts), staging.joinpath(*target)
            if symbolic:
                os.symlink(os.path.relpath(source, destination.parent), destination)
            else:
                os.link(source, destination)
    except NativePrerequisiteError:
        raise
    except (OSError, EOFError, ValueError, RuntimeError, tarfile.TarError):
        fail("native_archive_corrupt_or_path_conflict")
    return {"members": len(seen), "extracted_regular_bytes": extracted, "links": len(links),
            "headers": budget.headers, "namespace_entries": len(spellings), "metadata_bytes": budget.metadata_bytes,
            "compressed_read_bytes": budget.compressed_bytes, "decompressed_read_bytes": budget.stream_bytes,
            "max_members": MAX_MEMBERS, "max_extracted_bytes": MAX_EXTRACTED_BYTES,
            "max_metadata_bytes": MAX_METADATA_BYTES, "max_decompressed_bytes": MAX_TAR_STREAM_BYTES,
            "max_extension_depth": MAX_EXTENSION_DEPTH, "max_path_components": MAX_PATH_COMPONENTS,
            "deadline_seconds": EXTRACTION_TIMEOUT_SECONDS, "deadline_kind": "cooperative"}


def validate_home(home: Path, targets: tuple[str, ...]) -> None:
    if not home.is_absolute() or home.name != HOME_NAME or not home.is_dir() or home.is_symlink():
        fail("native_home_missing_or_unsafe")
    for relative in ("bin", "tools", "konan", "klib/common", *(f"klib/platform/{TARGETS[target]}" for target in targets)):
        directory = home / relative
        if not directory.is_dir():
            fail("native_target_klib_missing" if relative.startswith("klib/") else "native_distribution_incomplete")
        if any(parent.is_symlink() for parent in (directory, *directory.parents) if parent.is_relative_to(home)):
            fail("native_component_path_unsafe")
    for relative in CORE_FILES:
        selected_file(home, relative)
    if any(not os.access(home / relative, os.X_OK) for relative in ("bin/konanc", "bin/run_konan")):
        fail("native_compiler_launcher_unexecutable")
    compiler = selected_file(home, CORE_FILES[-1])
    if compiler.stat().st_size > 200 * 1024 * 1024:
        fail("native_compiler_manifest_invalid")
    try:
        with zipfile.ZipFile(compiler) as jar:
            if len(jar.infolist()) > 100_000 or jar.namelist().count("META-INF/MANIFEST.MF") != 1:
                fail("native_compiler_manifest_invalid")
            manifest = jar.getinfo("META-INF/MANIFEST.MF")
            if manifest.file_size > 65536:
                fail("native_compiler_manifest_invalid")
            text = jar.read(manifest).decode("utf-8")
    except (OSError, ValueError, zipfile.BadZipFile):
        fail("native_compiler_manifest_invalid")
    if re.findall(r"^Implementation-Version: ([^\r\n]*)\r?$", text, re.MULTILINE) != [VERSION]:
        fail("native_compiler_version_mismatch")
    stdlib = properties(selected_file(home, "klib/common/stdlib/default/manifest"), {"unique_name", "builtins_platform", "native_targets"})
    if stdlib.get("unique_name") != "stdlib" or stdlib.get("builtins_platform") != "NATIVE" \
            or not {TARGETS[target] for target in targets}.issubset(set(stdlib.get("native_targets", "").split())):
        fail("native_standard_klib_invalid")
    for target in targets:
        base = home / "klib/platform" / TARGETS[target]
        manifests = list(base.glob("*/default/manifest"))
        if not manifests or len(manifests) > 2000:
            fail("native_target_klib_missing_or_oversized")
        for manifest in manifests:
            values = properties(selected_file(home, manifest.relative_to(home).as_posix()), {"builtins_platform", "native_targets", "unique_name"})
            if values.get("builtins_platform") != "NATIVE" or TARGETS[target] not in values.get("native_targets", "").split() \
                    or not re.fullmatch(r"org\.jetbrains\.kotlin\.native\.platform\.[A-Za-z0-9_.+-]+", values.get("unique_name", "")):
                fail("native_target_klib_invalid")


def component_inventory(home: Path, targets: tuple[str, ...]) -> list[dict]:
    validate_home(home, targets)
    rows = []
    roots = ("bin", "tools", "konan", "klib/common", *(f"klib/platform/{TARGETS[target]}" for target in targets))
    for relative in roots:
        for directory, directories, files in os.walk(home / relative, followlinks=False):
            if any((Path(directory) / name).is_symlink() for name in directories):
                fail("native_component_path_unsafe")
            for name in files:
                path = Path(directory) / name
                relative_file = path.relative_to(home).as_posix()
                if any(not PART.fullmatch(part) or part in {".", ".."} for part in relative_file.split("/")):
                    fail("native_component_path_unsafe")
                target = path.resolve(strict=True) if path.is_symlink() else path
                if not target.is_relative_to(home) or not target.is_file() or target.stat().st_size > MAX_FILE_BYTES:
                    fail("native_component_path_unsafe")
                row = {"path": relative_file, "kind": "symlink" if path.is_symlink() else "file",
                       "size": target.stat().st_size, "digest": digest_file(target), "executable": bool(target.stat().st_mode & 0o111)}
                if path.is_symlink():
                    row["target"] = target.relative_to(home).as_posix()
                rows.append(row)
                if len(rows) > MAX_COMPONENTS:
                    fail("native_component_inventory_bound_exceeded")
    return sorted(rows, key=lambda row: row["path"])


def java_inventory(home: Path) -> list[dict]:
    return [{"coordinate": "selected-native-jdk:" + relative, "digest": digest_file(selected_file(home, relative))}
            for relative in JAVA_FILES]


def native_version(text: str) -> bool:
    return re.search(r"(?:kotlinc-native|Kotlin/Native(?: compiler)?(?: version)?)\s+2\.4\.21(?:\s|$|\()", text, re.IGNORECASE) is not None


def consume_selection(selection_path: Path, receipt_path: Path, target: str, root: Path, source: dict,
                      java_home: Path, selected_xcode: dict) -> tuple[Path, Path, dict]:
    private_path = build_path(selection_path, root, private=True)
    public_path = build_path(receipt_path, root)
    if not private_path.exists():
        fail("native_explicit_selection_missing")
    if not public_path.exists():
        fail("native_provisioning_receipt_missing")
    try:
        raw = regular_input(private_path, root)
        selection = strict_json(raw, limit=8 * 1024 * 1024)
        receipt_raw = regular_input(public_path, root)
        receipt = strict_json(receipt_raw)
    except (OSError, ValueError):
        fail("native_selection_or_receipt_invalid")
    binding = run_binding()
    targets = selection.get("targets")
    if not isinstance(targets, list) or not targets or any(not isinstance(item, str) or item not in TARGETS for item in targets) \
            or targets != sorted(set(targets)) or target not in targets:
        fail("native_selection_target_mismatch")
    if not isinstance(selection.get("source"), dict) or not isinstance(receipt.get("source"), dict):
        fail("native_selection_or_receipt_invalid")
    if selection.get("schema_version") != 1 or type(selection.get("schema_version")) is not int \
            or selection.get("evidence_kind") != "private_native_selection" or selection.get("run") != binding \
            or any(selection.get("source", {}).get(key) != source.get(key) for key in ("commit", "working_tree_digest")):
        fail("native_selection_source_or_run_mismatch")
    if receipt.get("state") != "passed":
        fail("native_provisioning_did_not_pass")
    if receipt.get("evidence_kind") != "native_distribution_provisioning" or receipt.get("run") != binding \
            or type(receipt.get("schema_version")) is not int or receipt.get("schema_version") != 1 \
            or any(receipt.get("source", {}).get(key) != source.get(key) for key in ("commit", "working_tree_digest")) \
            or receipt.get("selection_digest") != digest_bytes(raw) or receipt.get("selected_targets") != targets \
            or any(receipt.get(key) is not False for key in ("tuple_qualified", "custom_rules_qualified", "release_qualified")) \
            or receipt.get("archive_digest") != "sha256:" + ARCHIVE_SHA256 or receipt.get("archive_size") != ARCHIVE_SIZE \
            or receipt.get("checksum_provenance") != CHECKSUM_PROVENANCE or receipt.get("archive_verified") is not True \
            or receipt.get("compiler_version_observed") != VERSION:
        fail("native_provisioning_receipt_identity_invalid")
    private = private_path.parent
    data_dir = build_path(private / "data", root, private=True)
    home = data_dir / HOME_NAME
    if selection.get("native_home") != str(home) or selection.get("konan_data_dir") != str(data_dir) \
            or os.environ.get("KONAN_DATA_DIR") != str(data_dir) or os.environ.get("KONAN_USE_INTERNAL_SERVER") != "0" \
            or selection.get("java_home") != str(java_home) or selection.get("developer_dir") != os.environ.get("DEVELOPER_DIR"):
        fail("native_explicit_selected_paths_or_environment_changed")
    if selection.get("selected_xcode") != selected_xcode:
        fail("native_selected_xcode_observation_changed")
    archive_path = build_path(private / "archive.tar.gz", root, private=True)
    if selection.get("archive_path") != str(archive_path):
        fail("native_selection_archive_identity_invalid")
    verify_archive(archive_path)
    inventory = component_inventory(home, tuple(targets))
    if selection.get("components") != inventory or selection.get("inventory_digest") != canonical_digest(inventory) \
            or receipt.get("inventory_digest") != canonical_digest(inventory):
        fail("native_selected_components_changed")
    if selection.get("java_components") != java_inventory(java_home):
        fail("native_enrolled_java_components_changed")
    return home, data_dir, {"state": "passed", "receipt_digest": digest_bytes(receipt_raw),
                            "selection_digest": digest_bytes(raw), "archive_digest": "sha256:" + ARCHIVE_SHA256,
                            "inventory_digest": canonical_digest(inventory), "selected_targets": targets}


def provision(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    source = source_identity(root)
    targets = tuple(sorted(set(args.targets)))
    result = {"schema_version": 1, "evidence_kind": "native_distribution_provisioning", "recorded_at": utc_now(),
              "source": source, "state": "unavailable", "phase": "preflight", "errors": [], "selected_targets": list(targets),
              "coordinate": COORDINATE, "archive_url": ARCHIVE_URL, "archive_size": ARCHIVE_SIZE,
              "archive_digest": "sha256:" + ARCHIVE_SHA256, "checksum_provenance": CHECKSUM_PROVENANCE,
              "distribution_acquisition_attempted": False, "archive_verified": False, "compiler_version_probe_executed": False,
              "backend_bootstrap_executed": False, "target_compilation_executed": False, "target_runtime_executed": False,
              "tuple_qualified": False, "custom_rules_qualified": False, "release_qualified": False,
              "raw_logs_uploaded": False, "limitations": LIMITATIONS}
    try:
        output = build_path(args.output, root)
        selection_path = build_path(args.selection, root, private=True)
        if output.exists() or selection_path.parent.exists():
            fail("native_provisioning_output_not_fresh")
        _, java_home, selected_xcode = observed_prerequisites(args.environment, root, source)
        result.update(run=run_binding(), selected_xcode=selected_xcode)
        private = selection_path.parent
        private.mkdir(parents=True, mode=0o700)
        data_dir = private / "data"
        data_dir.mkdir(mode=0o700)
        if os.environ.get("KONAN_DATA_DIR") != str(data_dir) or os.environ.get("KONAN_USE_INTERNAL_SERVER") != "0":
            fail("native_explicit_selected_paths_or_environment_changed")
        archive = private / "archive.tar.gz"
        acquire_archive(archive, private, result)
        result["phase"] = "extraction"
        staging = private / "staging"
        result["extraction"] = extract_archive(archive, staging)
        staged_home = staging / HOME_NAME
        component_inventory(staged_home, targets)
        home = data_dir / HOME_NAME
        staged_home.rename(home)
        staging.rmdir()
        inventory = component_inventory(home, targets)
        before_java = java_inventory(java_home)
        result["phase"] = "compiler_version_probe"
        env = {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C", "JAVA_HOME": str(java_home), "JAVA_OPTS": "-Xmx512m",
               "KONAN_DATA_DIR": str(data_dir), "KONAN_USE_INTERNAL_SERVER": "0", "DEVELOPER_DIR": os.environ["DEVELOPER_DIR"],
               "HOME": str(private / "home")}
        (private / "home").mkdir(mode=0o700)
        execution, stdout, stderr = run_logged([str(home / "bin/konanc"), "-version", "-Xoverride-konan-properties=airplaneMode=true"],
            cwd=private, log_base=private / "compiler-version", timeout=60, max_output_bytes=65536, env=env, separate_stderr=True)
        result.update(compiler_version_probe=process_projection(execution),
                      compiler_version_probe_executed=execution.get("return_code") is not None)
        if execution.get("state") == "unavailable":
            fail("native_compiler_version_probe_start_failed")
        if execution.get("state") == "timeout":
            fail("native_compiler_version_probe_timeout")
        if execution.get("state") != "completed" or execution.get("return_code") != 0:
            fail("native_compiler_version_probe_failed")
        if not native_version(stdout + "\n" + stderr):
            fail("native_compiler_version_probe_mismatch")
        if component_inventory(home, targets) != inventory or java_inventory(java_home) != before_java:
            fail("native_selected_inputs_changed_during_probe")
        verify_archive(archive)
        if source_identity(root)["working_tree_digest"] != source["working_tree_digest"]:
            fail("native_source_changed_during_provisioning")
        selection = {"schema_version": 1, "evidence_kind": "private_native_selection", "source": source, "run": result["run"],
                     "targets": list(targets), "native_home": str(home), "konan_data_dir": str(data_dir), "java_home": str(java_home),
                     "developer_dir": os.environ["DEVELOPER_DIR"], "archive_path": str(archive), "selected_xcode": selected_xcode,
                     "components": inventory, "inventory_digest": canonical_digest(inventory), "java_components": before_java}
        publish_json_no_clobber(selection_path, selection)
        result.update(state="passed", phase="enrolled", compiler_version_observed=VERSION, selection_digest=digest_file(selection_path),
                      inventory_digest=canonical_digest(inventory), selected_component_count=len(inventory), selected_java_components=before_java,
                      selected_components=[{"coordinate": "selected-native-2.4.21:" + relative, "digest": digest_file(home / relative)} for relative in CORE_FILES],
                      target_klibs=[{"target": target, "component_count": len(rows), "inventory_digest": canonical_digest(rows)}
                                   for target in targets for rows in [[row for row in inventory if row["path"].startswith("klib/platform/" + TARGETS[target] + "/")]]])
    except NativePrerequisiteError as error:
        result["errors"].append(error.code)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RuntimeError, zipfile.BadZipFile):
        result["errors"].append("native_provisioning_unclassified_failure")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--selection", type=Path, required=True)
    parser.add_argument("--environment", type=Path, default=REPO_ROOT / "build/ci-evidence/environment.json")
    parser.add_argument("--targets", choices=tuple(TARGETS), nargs="+", default=list(TARGETS))
    args = parser.parse_args()
    result = provision(args, REPO_ROOT)
    try:
        publish_json_no_clobber(build_path(args.output, REPO_ROOT), result)
    except NativePrerequisiteError as error:
        print(error.code)
        return 1
    except OSError:
        print("native_evidence_publication_failed")
        return 1
    print(f"Native provisioning: {result['state']}; phase={result['phase']}; no target or qualified-rule claim")
    for code in result["errors"]:
        print(code)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
