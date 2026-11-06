#!/usr/bin/env python3
"""Explicit one-rule hosted Compose corpus contract; never an implicit test run.

An owner-reviewed complete runtime lock is REQUIRED and intentionally not supplied
with this SOURCE proposal. Missing/malformed enrollment fails unavailable, never
skips successfully. Lock data only has meaning inside the separately installed,
explicitly dispatched trusted fixture-owner workflow; it is not request authority.
No dependency closure or version is inferred. All raw logs, descriptors, reports
and process data stay private. Only whitelisted original successful JUnit XML and
sanitized metadata may be published. No live sidecar or qualification is created.

Process execution uses existing ci_common utilities, including their documented
best-effort outer-CI limits. This helper adds no Popen/kill/timeout wrapper and
claims no native ownership, descendant cleanup, immutable custody or sandbox.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

if __package__:
    from .ci_common import REPO_ROOT, gradle_result, run_logged, source_identity, utc_now, wrapper_argv
    from .collect_evidence import junit_report
    from .run_supervisor_checks import manifest as supervisor_manifest
else:
    from ci_common import REPO_ROOT, gradle_result, run_logged, source_identity, utc_now, wrapper_argv
    from collect_evidence import junit_report
    from run_supervisor_checks import manifest as supervisor_manifest

CORPUS = "providers/detekt-fixtures/corpus/compose-state-effects-v1"
TASK = ":provider-detekt:composeStateEffectsCorpusTest"
SUPERVISOR_TASK = ":provider-runtime:buildOwnedProcessSupervisor"
TEST_CLASS = "dev.ktchecker.providers.detekt.ComposeStateEffectsCorpusTest"
TEST_NAME = "one explicitly selected rule retains actual facets or visible no-launch denials()"
XML_REL = f"modules/provider-detekt/build/test-results/composeStateEffectsCorpusTest/TEST-{TEST_CLASS}.xml"
LOCK_REL = CORPUS + "/ci-runtime-lock.json"
LOCK_SCHEMA = "ktchecker/compose-corpus-ci-owner-lock/1"
OWNER_SCHEMA = "ktchecker/compose-state-effects-owner-compilations/1"
VECTOR_KIND = "complete_owner_resolved_jvm_compile_classpath"
CONSENT = "compose-state-effects-v1-six-rules-46-analyses"
FIXTURE_SCOPE = {"module": ":compose-state-effects-corpus", "source_set": "main", "target": "jvm", "compilation": "main"}
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
COORDINATE = re.compile(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+-]+)")
MAX_JSON = 2 * 1024 * 1024
MAX_FILE = 8 * 1024 * 1024
MAX_ARTIFACT = 200_000_000
MAX_LIBRARY_TOTAL = 512 * 1024 * 1024
AGGREGATE_MILLIS = 300_000
CHILD_MILLIS = 30_000
SUPERVISOR_PROTOCOL = "ktchecker-owned-child-supervisor/preview-1"
SUPERVISOR_TARGET = "linux-x86_64"
SUPERVISOR_PREFIX = "modules/provider-runtime/build/generated/nativeResources/dev/ktchecker/providers/runtime/native"
RULES = {
    "ktc.compose.pack.RememberMissing": ("remember-missing", 8, "partial_reviewed_message_facets"),
    "ktc.compose.pack.RememberContentMissing": ("remember-content-missing", 8, "reviewed_api_surface"),
    "ktc.compose.pack.ContentSlotReused": ("content-slot-reused", 8, "reviewed_api_surface"),
    "ktc.compose.pack.MutableStateAutoboxing": ("mutable-state-autoboxing", 8, "partial_reviewed_message_facets"),
    "ktc.compose.pack.StateParam": ("state-param", 7, "reviewed_api_surface"),
    "ktc.compose.pack.UnstableCollections": ("unstable-collections", 7, "reviewed_api_surface"),
}
ALL_RULES = set(RULES) | {"ktc.compose.pack.LambdaParameterInRestartableEffect", "ktc.compose.pack.MutableStateParam"}
API_PINS = {
    "androidx.compose.runtime:runtime-desktop:1.12.1": ("sha256:ed390d7bf7bdba951359d127f4c1ddd223f535f5a6a63078c5c5ac6b6924eb08", 1_948_637),
    "androidx.compose.runtime:runtime-annotation-jvm:1.12.1": ("sha256:e291a6ae01ab71a7f48a568ec9744c6bf9b3f46ef72fbcdeb28c3407ae531c5e", 9_379),
    "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.8": ("sha256:72e9a9b0003ec5254b638186f7ca1675b08006bdde4c9c4c589970342ccd2e77", 246_751),
}
RUNTIME_GAS = {"androidx.compose.runtime:runtime", "androidx.compose.runtime:runtime-desktop",
               "androidx.compose.runtime:runtime-android", "org.jetbrains.compose.runtime:runtime",
               "org.jetbrains.compose.runtime:runtime-desktop"}
ANNOTATION_GAS = {"androidx.compose.runtime:runtime-annotation", "androidx.compose.runtime:runtime-annotation-jvm"}
IMMUTABLE_GAS = {"org.jetbrains.kotlinx:kotlinx-collections-immutable", "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm"}

# These are exact SOURCE dependencies selected by the final corpus/task-r2 and
# guard-r2 packets. They are not test results or source-to-binary attestation.
SOURCE_PINS = {
    "modules/contracts/src/main/kotlin/dev/ktchecker/contracts/Contracts.kt": {
        "bytes": 9866,
        "sha256": "82755f7081c4b00f100a4810a8ddac19fcd2085dbb720c05dc01ba25111bd71e"
    },
    "modules/provider-detekt/src/corpusTest/kotlin/dev/ktchecker/providers/detekt/ComposeStateEffectsCorpusTest.kt": {
        "bytes": 59918,
        "sha256": "beb1e6eb0d7a08540628a1ae571139faf1583bc9c41ab9ec26deffdb58b272ea"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/README.md": {
        "bytes": 10393,
        "sha256": "acdb1d141cb3561d3174c253e9a4f97bba34d0af3fe4a78d09d548ed36d5c68c"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/admission-contexts.json": {
        "bytes": 9554,
        "sha256": "c029c119b0ffce1329f1cf99063d155dde8cce871157645e3b13b76a9e445ef2"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/api-surface-review.json": {
        "bytes": 47870,
        "sha256": "a027758498d025d3dce68a0feabd05f11cc2c2afbe8e84fcdc991a56abe0aba2"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/fixtures/content-slot-reused.json": {
        "bytes": 11739,
        "sha256": "38292bd913bf17c436df22120ccfe169b271d584edad0cb3f94eba8525e48c4a"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/fixtures/lambda-parameter-in-restartable-effect.json": {
        "bytes": 12227,
        "sha256": "d161a74a26d6641a834b1d93794c68285a2efde438850598ce59619c0def1e3f"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/fixtures/mutable-state-autoboxing.json": {
        "bytes": 11773,
        "sha256": "ea1afe6fa542ec723b939273748896b77d986663a08200e812b58f9328637b6c"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/fixtures/mutable-state-param.json": {
        "bytes": 10872,
        "sha256": "c3b182d724e6b1cde1f24b249060248968b8a3e9568088cfada0454f5205c43d"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/fixtures/remember-content-missing.json": {
        "bytes": 12062,
        "sha256": "7cec613305a3440b4ae7a6bdde9996ca7143d59d777dbb411f250c22f3a8ab2f"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/fixtures/remember-missing.json": {
        "bytes": 12323,
        "sha256": "9eef666f2aeb916905e6f48829bd32ae63324771f2c93e0d60ecea8d0917151e"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/fixtures/state-param.json": {
        "bytes": 10546,
        "sha256": "6058d0233a6dbbabf86349251b930dc520e32ee15df1abe8fc8dfdf04abc4b39"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/fixtures/unstable-collections.json": {
        "bytes": 12023,
        "sha256": "eeb8ee583489fa015e3553465f875a1de935db887a5a8a464e5e53d4f717dc2e"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/manifest.json": {
        "bytes": 6231,
        "sha256": "907c9374788e13a549eba81626adbfb8ca0b97bfbb3bb8bce8a6967e2273f79a"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/owner-input-format.json": {
        "bytes": 2411,
        "sha256": "b84f71b54c87c31f736f26a31aceb9b21926cd347f39d61addb891af985adc6d"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/pending-coverage.json": {
        "bytes": 7571,
        "sha256": "80501f4bdea67077c2850597d58918cf32633ccb8940699ab857c0bab372b409"
    },
    "providers/detekt-fixtures/corpus/compose-state-effects-v1/source-oracles.json": {
        "bytes": 28666,
        "sha256": "4474e3ed59dd595c908b9b10246cd4e8ebd256df3c47ee0bbc4fe5b1d3ffa21f"
    },
    "modules/provider-detekt/src/main/kotlin/dev/ktchecker/providers/detekt/DetektComposeApplicability.kt": {
        "bytes": 25850,
        "sha256": "5ee57ba286ba75a9d5db2287bf11794f89adae5a4794213bebca714d6b0c38f2"
    },
    "modules/provider-detekt/src/main/kotlin/dev/ktchecker/providers/detekt/DetektProvider.kt": {
        "bytes": 33653,
        "sha256": "692e2530e0f8e3d05ac220dc202c859690a2976f639b5b1fbffec66a9dd77f59"
    },
    "modules/provider-detekt/src/test/kotlin/dev/ktchecker/providers/detekt/DetektComposeApplicabilityTest.kt": {
        "bytes": 27467,
        "sha256": "0a46800f30e95e90766bc84e1f76f9efaa52994ebfc9ae502b3acedf0a35d047"
    },
    "modules/provider-detekt/src/test/kotlin/dev/ktchecker/providers/detekt/DetektProviderTest.kt": {
        "bytes": 18453,
        "sha256": "409ed6cb04effe040b1c1d31742973240461b4612c297187b5e9de837c7fdb58"
    },
    "modules/provider-detekt/build.gradle.kts": {
        "bytes": 13064,
        "sha256": "9b0e452fc12a73a7e32bf9f8a5c70e049ab4eb2e564a7a95f2bebe50ad907c7c"
    },
    "scripts/ci/ci_common.py": {
        "bytes": 12753,
        "sha256": "73630a12802a3aacdb99973daf014f6971d266282fa72444fd6a474e8b604c95"
    },
    "scripts/ci/collect_evidence.py": {
        "bytes": 12339,
        "sha256": "840546f4cf59bfdee7f3929921e224f0254502a41486f4c292bc501d0269a815"
    },
    "scripts/ci/record_environment.py": {
        "bytes": 22519,
        "sha256": "e5c234c82b897bd25da9ac618cc1ee79c61874dd7008d400fb00f575e747de70"
    },
    "scripts/ci/run_supervisor_checks.py": {
        "bytes": 56555,
        "sha256": "0856359b6869e4d75aaeddb3426a96789ff0468adc305afcfa30b142f1962fda"
    },
    "providers/detekt-fixtures/resolve_artifacts.py": {
        "bytes": 4641,
        "sha256": "cf32db3395c1e952ceb7b3b608d3c9b790d19ac1bb71a0f49b3293bf9a188c22"
    },
    "modules/provider-detekt/src/main/resources/detekt-artifacts.json": {
        "bytes": 2849,
        "sha256": "09575c9dfdd6d1cbb751532edbae0f5408113102240a6f2220c1e45c63a4b937"
    },
    "modules/provider-detekt/src/main/resources/detekt-rule-map.json": {
        "bytes": 285207,
        "sha256": "47ce5a08d971f8174441a578130188915a141e3c80724f3553609e3672aa1202"
    },
    "gradle/wrapper/gradle-wrapper.properties": {
        "bytes": 368,
        "sha256": "89f62533208a72b7a8cc2892b6b3540c445fa6175508297d932dae57d653591a"
    }
}


class Problem(ValueError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def need(condition: bool, code: str) -> None:
    if not condition:
        raise Problem(code)


def integer(value: object, low: int, high: int) -> bool:
    return type(value) is int and low <= value <= high


def sha(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def encoded(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def unique_pairs(rows: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in rows:
        need(key not in result, "compose_ci_duplicate_json_key")
        result[key] = value
    return result


def strict_json(raw: bytes, limit: int = MAX_JSON) -> dict:
    need(0 < len(raw) <= limit, "compose_ci_json_bound")
    def finite_float(number: str) -> float:
        # JSON's parse_constant hook does not cover exponent overflow (1e309).
        parsed = float(number)
        need(math.isfinite(parsed), "compose_ci_nonfinite_json")
        return parsed
    value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_pairs,
                       parse_float=finite_float,
                       parse_constant=lambda _: (_ for _ in ()).throw(Problem("compose_ci_nonfinite_json")))
    need(type(value) is dict, "compose_ci_json_object_required")
    return value


def relative(value: str) -> PurePosixPath:
    need(isinstance(value, str) and 0 < len(value) <= 1024 and re.fullmatch(r"[A-Za-z0-9_./+-]+", value) is not None,
         "compose_ci_path_invalid")
    path = PurePosixPath(value)
    # PurePosixPath(".") has no parts, so all(parts) alone accepts the root alias.
    need(bool(path.parts) and not path.is_absolute() and str(path) == value
         and all(x not in {"", ".", ".."} for x in path.parts), "compose_ci_path_invalid")
    return path


def exact_path(path: Path, *, directory: bool | None = None) -> Path:
    need(path.is_absolute() and os.path.normpath(str(path)) == str(path), "compose_ci_path_invalid")
    for part in reversed((path,) + tuple(path.parents)):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        need(not stat.S_ISLNK(info.st_mode), "compose_ci_symlink_rejected")
    if directory is True:
        need(path.is_dir(), "compose_ci_directory_unavailable")
    elif directory is False:
        need(path.is_file(), "compose_ci_file_unavailable")
    return path


def stable_key(info: os.stat_result) -> tuple:
    return info.st_dev, info.st_ino, info.st_mode, info.st_size, info.st_mtime_ns, info.st_ctime_ns


def open_leaf(path: Path, flags: int) -> int:
    """Anchor each component without following aliases; no immutable-custody claim."""
    exact_path(path)
    need(hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY"),
         "compose_ci_nofollow_io_unavailable")
    current = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for component in path.parts[1:-1]:
            child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=current)
            os.close(current)
            current = child
        return os.open(path.name, flags | os.O_NOFOLLOW, 0o600, dir_fd=current)
    finally:
        os.close(current)


def read_regular(path: Path, limit: int) -> bytes:
    exact_path(path, directory=False)
    fd = open_leaf(path, os.O_RDONLY | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        need(stat.S_ISREG(before.st_mode) and 0 <= before.st_size <= limit, "compose_ci_file_bound")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            raw = stream.read(limit + 1)
        after = os.fstat(fd)
        need(len(raw) == before.st_size and stable_key(before) == stable_key(after)
             and stable_key(after) == stable_key(path.lstat()), "compose_ci_file_changed")
        return raw
    finally:
        os.close(fd)


def file_identity(path: Path, maximum: int = MAX_ARTIFACT) -> dict:
    exact_path(path, directory=False)
    fd = open_leaf(path, os.O_RDONLY | os.O_NONBLOCK)
    digest = hashlib.sha256()
    try:
        before = os.fstat(fd)
        need(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum, "compose_ci_artifact_bound")
        size = 0
        with os.fdopen(fd, "rb", closefd=False) as stream:
            while chunk := stream.read(1024 * 1024):
                size += len(chunk)
                need(size <= maximum, "compose_ci_artifact_bound")
                digest.update(chunk)
        need(size == before.st_size and stable_key(before) == stable_key(os.fstat(fd))
             and stable_key(before) == stable_key(path.lstat()), "compose_ci_artifact_changed")
        return {"size": size, "digest": "sha256:" + digest.hexdigest()}
    finally:
        os.close(fd)


def make_directory(path: Path) -> None:
    exact_path(path)
    need(hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY"),
         "compose_ci_nofollow_io_unavailable")
    current = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for component in path.parts[1:]:
            try:
                os.mkdir(component, 0o700, dir_fd=current)
            except FileExistsError:
                pass
            child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=current)
            os.close(current)
            current = child
    finally:
        os.close(current)
    exact_path(path, directory=True)


def write_new(path: Path, raw: bytes) -> None:
    need(len(raw) <= MAX_FILE, "compose_ci_output_bound")
    make_directory(path.parent)
    exact_path(path)
    fd = open_leaf(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
    with os.fdopen(fd, "wb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    need(read_regular(path, len(raw)) == raw, "compose_ci_write_readback_changed")


def paths(root: Path, rule: str) -> dict[str, Path]:
    need(rule in RULES, "compose_ci_rule_not_supported_for_native_matrix")
    slug = RULES[rule][0]
    public = root / "build/ci-evidence/compose" / slug
    private = root / "build/ci-evidence/raw/compose" / slug
    return {"public": public, "private": private, "runtime": private / "runtime",
            "enrollment": private / "enrollment.json", "build": private / "build-result.json",
            "environment": public / "environment.json", "wrapper": public / "gradle-version.json",
            "evidence": private / "runtime/corpus-evidence", "xml": root / XML_REL}


def check_source(root: Path) -> dict:
    exact_path(root, directory=True)
    for rel, expected in SOURCE_PINS.items():
        raw = read_regular(root / relative(rel), max(expected["bytes"], 1))
        need(len(raw) == expected["bytes"] and sha(raw) == "sha256:" + expected["sha256"],
             "compose_ci_source_dependency_changed")
    identity = source_identity(root)
    need(identity.get("dirty") is False and integer(identity.get("unsupported_directories"), 0, 0),
         "compose_ci_reviewed_clean_source_required")
    return identity


def dispatch_context(source: dict, environment: dict[str, str] | None = None) -> dict:
    env = os.environ if environment is None else environment
    need(env.get("KTC_COMPOSE_CI_CONSENT") == CONSENT, "compose_ci_explicit_six_job_consent_required")
    expected = env.get("KTC_COMPOSE_EXPECTED_COMMIT", "")
    need(re.fullmatch(r"[0-9a-f]{40}", expected) is not None and
         expected == source.get("commit") == env.get("GITHUB_SHA") == env.get("GITHUB_WORKFLOW_SHA"),
         "compose_ci_exact_source_workflow_commit_required")
    need(env.get("GITHUB_ACTIONS") == "true" and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
         and env.get("RUNNER_ENVIRONMENT") == "github-hosted" and env.get("RUNNER_OS") == "Linux"
         and env.get("RUNNER_ARCH") == "X64" and env.get("GITHUB_RUN_ATTEMPT") == "1",
         "compose_ci_fresh_hosted_linux_dispatch_required")
    need(re.fullmatch(r"[1-9][0-9]{0,19}", env.get("GITHUB_RUN_ID", "")) is not None,
         "compose_ci_run_identity_invalid")
    repository = env.get("GITHUB_REPOSITORY", "")
    need(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is not None
         and env.get("GITHUB_JOB") == "compose-corpus"
         and re.fullmatch(re.escape(repository) + r"/\.github/workflows/compose-corpus-validation\.yml@refs/(?:heads|tags)/[A-Za-z0-9_./-]{1,256}",
                          env.get("GITHUB_WORKFLOW_REF", "")) is not None,
         "compose_ci_run_identity_invalid")
    return {key: env[key] for key in ("GITHUB_REPOSITORY", "GITHUB_JOB", "GITHUB_RUN_ID",
                                      "GITHUB_RUN_ATTEMPT", "GITHUB_SHA", "GITHUB_WORKFLOW_SHA", "GITHUB_WORKFLOW_REF", "GITHUB_EVENT_NAME")}


def artifact_url(coordinate: str, url: str) -> str:
    match = COORDINATE.fullmatch(coordinate) if isinstance(coordinate, str) else None
    need(match is not None and not coordinate.endswith(":sources"), "compose_ci_coordinate_invalid")
    group, module, version = match.groups()
    need(all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_+-]*", part) for part in group.split("."))
         and all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.+-]*", part) and ".." not in part for part in (module, version))
         and not module.endswith("-sources"), "compose_ci_coordinate_invalid")
    filename = module + "-" + version + ".jar"
    suffix = group.replace(".", "/") + "/" + module + "/" + version + "/" + filename
    need(isinstance(url, str) and url.isascii() and len(url) <= 2048, "compose_ci_artifact_url_invalid")
    parsed = urlsplit(url)
    allowed = {"repo.maven.apache.org": "/maven2/", "dl.google.com": "/dl/android/maven2/"}
    need(parsed.scheme == "https" and parsed.netloc in allowed and parsed.hostname == parsed.netloc
         and not parsed.query and not parsed.fragment and parsed.path == allowed[parsed.netloc] + suffix
         and url == "https://" + parsed.netloc + allowed[parsed.netloc] + suffix,
         "compose_ci_artifact_url_invalid")
    return filename


def validate_lock(document: dict, root: Path, rule: str) -> dict:
    need(set(document) == {"schema", "dependency_vector_kind", "resolution_provenance", "reviewed", "unsupported_runtime"}
         and document["schema"] == LOCK_SCHEMA and document["dependency_vector_kind"] == VECTOR_KIND,
         "compose_ci_owner_lock_schema_invalid")
    provenance = document["resolution_provenance"]
    need(isinstance(provenance, list) and 1 <= len(provenance) <= 64, "compose_ci_resolution_provenance_missing")
    seen_provenance = set()
    for row in provenance:
        need(type(row) is dict and set(row) == {"path", "size", "digest"}, "compose_ci_resolution_provenance_invalid")
        rel = relative(row["path"])
        need(str(rel) not in seen_provenance and rel.suffix in {".json", ".module", ".pom", ".toml", ".md", ".lock"}
             and integer(row["size"], 1, MAX_JSON) and isinstance(row["digest"], str) and DIGEST.fullmatch(row["digest"]),
             "compose_ci_resolution_provenance_invalid")
        seen_provenance.add(str(rel))
        raw = read_regular(root / rel, row["size"])
        need(len(raw) == row["size"] and sha(raw) == row["digest"], "compose_ci_resolution_provenance_changed")
    artifacts = {}
    environment_keys = {"compilation_id", "scope", "platform", "source_set_ids", "compiler_version", "compiler_arguments", "dependencies"}
    for name in ("reviewed", "unsupported_runtime"):
        item = document[name]
        need(type(item) is dict and set(item) == environment_keys, "compose_ci_owner_environment_invalid")
        need(item["compilation_id"] == ":compose-state-effects-corpus:jvm:main" and item["scope"] == FIXTURE_SCOPE
             and item["platform"] == "jvm" and item["source_set_ids"] == ["main"] and item["compiler_version"] == "2.4.21",
             "compose_ci_owner_scope_invalid")
        arguments = item["compiler_arguments"]
        need(isinstance(arguments, list) and len(arguments) <= 64
             and all(isinstance(x, str) and 0 < len(x) <= 1024 and x.isprintable() for x in arguments),
             "compose_ci_owner_arguments_invalid")
        dependencies = item["dependencies"]
        need(isinstance(dependencies, list) and 3 <= len(dependencies) <= 256, "compose_ci_complete_vector_missing")
        seen, seen_ga = set(), set()
        for row in dependencies:
            need(type(row) is dict and set(row) == {"coordinate", "digest", "size", "url"},
                 "compose_ci_dependency_record_invalid")
            artifact_url(row["coordinate"], row["url"])
            ga = row["coordinate"].rsplit(":", 1)[0]
            need(row["coordinate"] not in seen and ga not in seen_ga and integer(row["size"], 1, MAX_ARTIFACT)
                 and isinstance(row["digest"], str) and DIGEST.fullmatch(row["digest"]), "compose_ci_dependency_record_invalid")
            seen.add(row["coordinate"])
            seen_ga.add(ga)
            previous = artifacts.setdefault(row["coordinate"], row)
            need(previous == row, "compose_ci_conflicting_cross_vector_pin")
        need(any(x.startswith("org.jetbrains.kotlin:kotlin-stdlib:") for x in seen)
             and any(x.startswith("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:") for x in seen),
             "compose_ci_complete_vector_missing")
        runtime = [x for x in dependencies if x["coordinate"].rsplit(":", 1)[0] in RUNTIME_GAS]
        need(len(runtime) == 1 and runtime[0]["coordinate"].startswith("androidx.compose.runtime:runtime-desktop:"),
             "compose_ci_ambiguous_runtime_family")
        if name == "unsupported_runtime":
            coordinate = "androidx.compose.runtime:runtime-desktop:1.12.1"
            need(runtime[0]["coordinate"] != coordinate and runtime[0]["digest"] != API_PINS[coordinate][0],
                 "compose_ci_real_other_runtime_required")
        else:
            families = [(RUNTIME_GAS, "androidx.compose.runtime:runtime-desktop:1.12.1"),
                        (ANNOTATION_GAS, "androidx.compose.runtime:runtime-annotation-jvm:1.12.1")]
            if rule.endswith(".UnstableCollections"):
                families.append((IMMUTABLE_GAS, "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.8"))
            for gas, coordinate in families:
                members = [x for x in dependencies if x["coordinate"].rsplit(":", 1)[0] in gas]
                need(len(members) == 1 and members[0]["coordinate"] == coordinate
                     and (members[0]["digest"], members[0]["size"]) == API_PINS[coordinate],
                     "compose_ci_reviewed_api_tuple_unavailable")
    need(len(artifacts) <= 256 and sum(row["size"] for row in artifacts.values()) <= MAX_LIBRARY_TOTAL,
         "compose_ci_dependency_aggregate_bound")
    return document


def owner_inputs(root: Path, rule: str, lock_digest: str) -> tuple[dict, dict, dict]:
    need(rule in RULES and isinstance(lock_digest, str) and DIGEST.fullmatch(lock_digest),
         "compose_ci_independent_owner_lock_digest_required")
    lock_path = root / LOCK_REL
    need(os.path.lexists(lock_path), "compose_ci_owner_lock_unavailable")
    raw = read_regular(lock_path, MAX_JSON)
    need(sha(raw) == lock_digest, "compose_ci_owner_lock_digest_mismatch")
    source = check_source(root)
    context = dispatch_context(source)
    document = validate_lock(strict_json(raw), root, rule)
    return source, context, document


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Problem("compose_ci_artifact_redirect_rejected")


def acquire(row: dict, destination: Path, deadline: float, download: bool, opener=None) -> dict:
    """Exact expected bytes only, no dependency resolver or trust-on-first-use.

    Socket/chunk checks are cooperative acquisition bounds. The hosted step/job
    bound is separate; this is not hard preemption or a new process supervisor.
    Failed/partial downloads remain private; there is no retry or cache repair.
    """
    artifact_url(row["coordinate"], row["url"])
    need(time.monotonic() < deadline, "compose_ci_provision_deadline")
    need(not os.path.lexists(destination), "compose_ci_fresh_artifact_destination_required")
    need(download, "compose_ci_explicit_download_consent_required")
    make_directory(destination.parent)
    opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    request = urllib.request.Request(row["url"], headers={"Accept-Encoding": "identity", "User-Agent": "ktchecker-compose-corpus-ci/1"})
    remaining = deadline - time.monotonic()
    need(remaining > 0, "compose_ci_provision_deadline")
    with opener.open(request, timeout=min(15.0, remaining)) as response:
        need(response.geturl() == row["url"] and response.status == 200, "compose_ci_artifact_response_invalid")
        need(response.headers.get("Content-Encoding", "identity") == "identity", "compose_ci_artifact_encoding_invalid")
        length = response.headers.get("Content-Length")
        need(length is None or length == str(row["size"]), "compose_ci_artifact_length_invalid")
        fd = open_leaf(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
        digest, total = hashlib.sha256(), 0
        with os.fdopen(fd, "wb") as output:
            while True:
                need(time.monotonic() < deadline, "compose_ci_provision_deadline")
                chunk = response.read(min(64 * 1024, row["size"] + 1 - total))
                if not chunk:
                    break
                total += len(chunk)
                need(total <= row["size"], "compose_ci_artifact_download_bound")
                digest.update(chunk)
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        need(total == row["size"] and "sha256:" + digest.hexdigest() == row["digest"],
             "compose_ci_artifact_expected_bytes_mismatch")
    actual = file_identity(destination)
    need(actual == {key: row[key] for key in ("size", "digest")}, "compose_ci_artifact_changed")
    return dict(coordinate=row["coordinate"], path=str(destination), **actual)


def environment_input(root: Path, rule: str, source: dict, context: dict) -> tuple[dict, Path]:
    value = strict_json(read_regular(paths(root, rule)["environment"], MAX_JSON))
    need(value.get("state") == "passed" and value.get("ci_context_required") is True
         and value.get("source") == source and value.get("host", {}).get("os") == "Linux"
         and value.get("host", {}).get("arch") == "x86_64"
         and all(value.get("runner", {}).get(key) == expected for key, expected in
                 {"RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64"}.items()),
         "compose_ci_environment_unavailable")
    need(all(value.get("ci", {}).get(key) == selected for key, selected in context.items()),
         "compose_ci_environment_context_mismatch")
    java = value.get("java", {})
    need(type(java.get("major")) is int and java["major"] == 21
         and value.get("gradle", {}).get("executed_wrapper_version") == "9.6.1",
         "compose_ci_jdk21_wrapper_unavailable")
    home = exact_path(Path(os.environ.get("JAVA_HOME", "")), directory=True)
    launcher = home / "bin/java"
    need(java.get("path") == str(launcher) and file_identity(launcher)["digest"] == java.get("launcher_digest")
         and file_identity(home / "release", MAX_JSON)["digest"] == java.get("release_file_digest"),
         "compose_ci_observed_jdk_changed")
    return value, home


def public_base(phase: str, rule: str) -> dict:
    return {"schema_version": 1, "evidence_kind": "compose_corpus_ci_" + phase, "phase": phase,
            "selected_rule_id": rule if rule in RULES else None, "state": "unavailable",
            "provider_controls_executed": None if phase in {"execute", "collect"} else False,
            "whole_batch_observed": False,
            "all43_compose_release_scope_retained": True, "complete_runtime_lock_delivered_by_this_proposal": False,
            "tuple_qualified": False, "release_qualified": False, "whole_jdk_distribution_hash_locked": False,
            "raw_logs_or_private_native_reports_selected_for_upload": False, "custody_or_cleanup_proven": False,
            "errors": [], "recorded_at": utc_now()}


def gate(root: Path, rule: str, lock_digest: str) -> dict:
    source, context, _ = owner_inputs(root, rule, lock_digest)
    return dict(public_base("gate", rule), state="passed", source=source, ci=context,
                owner_lock_digest=lock_digest, native_calls=0,
                qualification="Input enrollment only, not execution consent outside this exact owner workflow")


def wrapper(root: Path, rule: str, lock_digest: str) -> dict:
    source, context, _ = owner_inputs(root, rule, lock_digest)
    layout = paths(root, rule)
    write_new(layout["private"] / "wrapper-attempt.json", encoded({"source": source, "ci": context, "state": "launch_pending_not_execution_proof"}))
    command, private_output = gradle_result(["--version"], root=root, log_base=layout["private"] / "wrapper", timeout=180)
    match = re.search(r"^Gradle ([\d.]+)\s*$", private_output, re.MULTILINE)
    need(command.get("state") == "passed" and integer(command.get("return_code"), 0, 0)
         and all(isinstance(command.get(key), str) and DIGEST.fullmatch(command[key]) for key in ("stdout_digest", "stderr_digest"))
         and match is not None and match[1] == "9.6.1",
         "compose_ci_wrapper_execution_failed")
    need(check_source(root) == source, "compose_ci_source_changed")
    return dict(public_base("wrapper", rule), state="passed", source=source, ci=context,
                executed_gradle_version=match[1], return_code=command["return_code"],
                stdout_digest=command["stdout_digest"], stderr_digest=command["stderr_digest"])


def library_path(layout: dict, row: dict) -> Path:
    filename = artifact_url(row["coordinate"], row["url"])
    group, module, version = row["coordinate"].split(":")
    return layout["runtime"] / "libraries" / group / module / version / (row["digest"][7:] + "-" + filename)


def provision(root: Path, rule: str, lock_digest: str, download: bool) -> dict:
    source, context, lock = owner_inputs(root, rule, lock_digest)
    environment, home = environment_input(root, rule, source, context)
    layout = paths(root, rule)
    need(not os.path.lexists(layout["runtime"]) and not os.path.lexists(layout["enrollment"])
         and not os.path.lexists(layout["xml"].parent), "compose_ci_fresh_outputs_required")
    make_directory(layout["runtime"])
    make_directory(layout["evidence"])
    engine_dir = layout["runtime"] / "engine"
    engine_manifest = strict_json(read_regular(root / "modules/provider-detekt/src/main/resources/detekt-artifacts.json", MAX_JSON))
    need(download, "compose_ci_explicit_download_consent_required")
    command, private_output, _ = run_logged(
        [sys.executable, str(root / "providers/detekt-fixtures/resolve_artifacts.py"), "--download", "--cache", str(engine_dir)],
        cwd=root, log_base=layout["private"] / "engine-resolution", timeout=600, max_output_bytes=1024 * 1024)
    need(command.get("state") == "completed" and integer(command.get("return_code"), 0, 0),
         "compose_ci_engine_provision_failed")
    resolved = strict_json(private_output.encode("utf-8")).get("artifacts")
    engine = []
    for pin in engine_manifest["artifacts"]:
        path = engine_dir / pin["filename"]
        actual = file_identity(path)
        need(actual == {"size": pin["size"], "digest": "sha256:" + pin["sha256"]},
             "compose_ci_engine_pin_mismatch")
        engine.append(dict(coordinate=pin["coordinate"], path=str(path), **actual))
    need(len(engine) == 5 and resolved == [{key: row[key] for key in ("coordinate", "digest", "path")} for row in engine],
         "compose_ci_engine_resolver_join_failed")
    # Ordered per-environment vectors are preserved; shared exact binaries are
    # acquired once, never merged into a fabricated API-only dependency vector.
    unique = {}
    for name in ("reviewed", "unsupported_runtime"):
        for row in lock[name]["dependencies"]:
            unique.setdefault(row["coordinate"], row)
    deadline = time.monotonic() + 600
    binaries = {}
    for coordinate, row in unique.items():
        destination = library_path(layout, row)
        binaries[coordinate] = acquire(row, destination, deadline, download)
    descriptor = {"schema": OWNER_SCHEMA, "dependency_vector_kind": VECTOR_KIND}
    for name in ("reviewed", "unsupported_runtime"):
        descriptor[name] = dict(lock[name], dependencies=[binaries[row["coordinate"]] for row in lock[name]["dependencies"]])
    raw_descriptor = encoded(descriptor)
    need(len(raw_descriptor) <= 1_048_576, "compose_ci_owner_descriptor_bound")
    owner_file = layout["runtime"] / "owner-compilations.json"
    write_new(owner_file, raw_descriptor)
    enrollment = {"schema_version": 1, "kind": "trusted_fixture_owner_inputs_not_a_serialized_permit",
                  "source": source, "ci": context, "rule_id": rule, "owner_lock_digest": lock_digest,
                  "owner_file": str(owner_file), "owner_descriptor_digest": sha(raw_descriptor), "descriptor": descriptor,
                  "engine": engine, "engine_directory": str(engine_dir), "jdk_home": str(home),
                  "jdk_launcher_digest": environment["java"]["launcher_digest"],
                  "jdk_release_digest": environment["java"]["release_file_digest"],
                  "source_dependencies_digest": sha(encoded(SOURCE_PINS)), "xml_absent_before_run": True,
                  "prepared_at": utc_now(), "prepared_time_ns": time.time_ns(),
                  "maximum_native_invocations": RULES[rule][1], "aggregate_millis": AGGREGATE_MILLIS,
                  "child_millis": CHILD_MILLIS, "evidence_root": str(layout["evidence"])}
    need(check_source(root) == source, "compose_ci_source_changed")
    environment_input(root, rule, source, context)
    write_new(layout["enrollment"], encoded(enrollment))
    return dict(public_base("provision", rule), state="passed", source=source, ci=context,
                owner_lock_digest=lock_digest, owner_descriptor_digest=sha(raw_descriptor),
                complete_owner_vectors_supplied=True, native_calls=0,
                dependency_count={name: len(descriptor[name]["dependencies"]) for name in ("reviewed", "unsupported_runtime")},
                qualification="Exact supplied owner runtime inputs verified; no provider execution or resolved-site proof")


def enrollment_input(root: Path, rule: str, lock_digest: str) -> tuple[dict, dict, dict]:
    source, context, lock = owner_inputs(root, rule, lock_digest)
    layout = paths(root, rule)
    enrollment = strict_json(read_regular(layout["enrollment"], MAX_JSON))
    need(integer(enrollment.get("schema_version"), 1, 1)
         and enrollment.get("kind") == "trusted_fixture_owner_inputs_not_a_serialized_permit"
         and enrollment.get("source") == source and enrollment.get("ci") == context and enrollment.get("rule_id") == rule
         and enrollment.get("owner_lock_digest") == lock_digest
         and enrollment.get("source_dependencies_digest") == sha(encoded(SOURCE_PINS))
         and integer(enrollment.get("maximum_native_invocations"), RULES[rule][1], RULES[rule][1])
         and integer(enrollment.get("aggregate_millis"), AGGREGATE_MILLIS, AGGREGATE_MILLIS)
         and integer(enrollment.get("child_millis"), CHILD_MILLIS, CHILD_MILLIS),
         "compose_ci_enrollment_mismatch")
    environment, home = environment_input(root, rule, source, context)
    need(str(home) == enrollment["jdk_home"]
         and environment["java"]["launcher_digest"] == enrollment["jdk_launcher_digest"]
         and environment["java"]["release_file_digest"] == enrollment["jdk_release_digest"],
         "compose_ci_enrolled_jdk_changed")
    descriptor = enrollment["descriptor"]
    need(descriptor.get("schema") == OWNER_SCHEMA and descriptor.get("dependency_vector_kind") == VECTOR_KIND
         and enrollment["owner_file"] == str(layout["runtime"] / "owner-compilations.json")
         and enrollment["evidence_root"] == str(layout["evidence"])
         and enrollment["engine_directory"] == str(layout["runtime"] / "engine"),
         "compose_ci_enrollment_paths_mismatch")
    raw = read_regular(Path(enrollment["owner_file"]), MAX_JSON)
    need(strict_json(raw) == descriptor and sha(raw) == enrollment["owner_descriptor_digest"],
         "compose_ci_enrolled_descriptor_changed")
    for name in ("reviewed", "unsupported_runtime"):
        expected = lock[name]["dependencies"]
        actual = descriptor[name]["dependencies"]
        need(len(expected) == len(actual), "compose_ci_enrolled_vector_changed")
        need({key: descriptor[name][key] for key in descriptor[name] if key != "dependencies"}
             == {key: lock[name][key] for key in lock[name] if key != "dependencies"},
             "compose_ci_enrolled_scope_changed")
        for row, observed in zip(expected, actual):
            destination = library_path(layout, row)
            need(observed == dict(coordinate=row["coordinate"], digest=row["digest"], size=row["size"], path=str(destination))
                 and file_identity(destination) == {key: row[key] for key in ("size", "digest")},
                 "compose_ci_enrolled_library_changed")
    engine_manifest = strict_json(read_regular(root / "modules/provider-detekt/src/main/resources/detekt-artifacts.json", MAX_JSON))
    expected_engine = [{"coordinate": pin["coordinate"], "path": str(layout["runtime"] / "engine" / pin["filename"]),
                        "size": pin["size"], "digest": "sha256:" + pin["sha256"]} for pin in engine_manifest["artifacts"]]
    need(enrollment["engine"] == expected_engine, "compose_ci_enrolled_engine_changed")
    for row in expected_engine:
        need(file_identity(Path(row["path"])) == {key: row[key] for key in ("size", "digest")},
             "compose_ci_enrolled_engine_changed")
    return source, context, enrollment


def build_arguments(rule: str, enrollment: dict) -> list[str]:
    return ["--rerun-tasks", "--no-build-cache", SUPERVISOR_TASK, TASK,
            "-PnativeSupervisorPython=" + sys.executable, "-PnativeSupervisorCc=/usr/bin/cc",
            "-PdetektComposeConsent=compose-state-effects-v1-one-rule", "-PdetektComposeRule=" + rule,
            "-PdetektComposeJavaHome=" + enrollment["jdk_home"],
            "-PdetektComposeArtifacts=" + enrollment["engine_directory"],
            "-PdetektComposeOwnerCompilation=" + enrollment["owner_file"],
            "-PdetektComposeOwnerCompilationSha256=" + enrollment["owner_descriptor_digest"],
            "-PdetektComposeEvidenceRoot=" + enrollment["evidence_root"],
            "-PdetektComposeMaxInvocations=" + str(RULES[rule][1]),
            "-PdetektComposeAggregateMillis=" + str(AGGREGATE_MILLIS),
            "-PdetektComposeChildMillis=" + str(CHILD_MILLIS)]


def execute(root: Path, rule: str, lock_digest: str) -> dict:
    source, context, enrollment = enrollment_input(root, rule, lock_digest)
    layout = paths(root, rule)
    need(not os.path.lexists(layout["build"]) and not os.path.lexists(layout["xml"].parent)
         and not list(exact_path(layout["evidence"], directory=True).iterdir()), "compose_ci_fresh_outputs_required")
    arguments = build_arguments(rule, enrollment)
    attempt = {"source": source, "ci": context, "selected_rule_id": rule, "owner_lock_digest": lock_digest,
               "owner_descriptor_digest": enrollment["owner_descriptor_digest"],
               "arguments": arguments, "started_time_ns": time.time_ns(), "state": "launch_pending_not_execution_proof"}
    # O_EXCL before launch: a failed/unknown outer command is never retried here.
    write_new(layout["private"] / "execute-attempt.json", encoded(attempt))
    # Existing execution utility only. Its private returned console text is NEVER
    # echoed, including failure, unlike run_gradle.py's diagnostic-printing CLI.
    result, _private_output = gradle_result(arguments, root=root, log_base=layout["private"] / "gradle",
                                            timeout=1200, require_tasks=(SUPERVISOR_TASK, TASK))
    result.update(source=source, ci=context, selected_rule_id=rule,
                  owner_descriptor_digest=enrollment["owner_descriptor_digest"], owner_lock_digest=lock_digest,
                  execution_attempt_digest=sha(encoded(attempt)))
    write_new(layout["build"], encoded(result))
    need(result.get("state") == "passed", "compose_ci_actual_build_failed")
    enrollment_input(root, rule, lock_digest)
    return dict(public_base("execute", rule), state="passed", source=source, ci=context,
                build_result_digest=sha(encoded(result)), task_executed=result["tasks"].get(TASK) == "EXECUTED",
                qualification="Build returned success; exact original XML/native evidence join is still mandatory")


def original_xml(raw: bytes) -> dict:
    """Whitelist ORIGINAL successful XML, including bytes ignored by XML parsers.

    Never scrub/reconstruct XML and label it original. Comments, PIs, alternate
    encodings, arbitrary properties, output and diagnostics cannot be published.
    """
    need(0 < len(raw) <= MAX_FILE and all(byte in {9, 10, 13} or 32 <= byte <= 126 for byte in raw),
         "compose_ci_original_xml_unsafe")
    text = raw.decode("ascii")
    body = re.sub(r"""\A<\?xml version=(?:"1\.0"|'1\.0') encoding=(?:"UTF-8"|'UTF-8')\?>\s*""", "", text, count=1)
    need("<!--" not in body and "<?" not in body and
         re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", body, re.I) is None, "compose_ci_original_xml_unsafe")
    # Namespace declarations are original bytes too, but ElementTree removes
    # unused xmlns attributes. Reject every declaration via parser events;
    # never publish hidden namespace metadata or rely on the parsed attr view.
    parsed = ET.iterparse(io.BytesIO(raw), events=("start-ns",))
    for _namespace_event in parsed:
        raise Problem("compose_ci_original_xml_unsafe")
    element = parsed.root
    need(element.tag == "testsuite", "compose_ci_exact_junit_suite_required")
    allowed_suite = {"name", "tests", "skipped", "failures", "errors", "timestamp", "hostname", "time"}
    need(set(element.attrib) <= allowed_suite and element.get("name") == TEST_CLASS
         and all(element.get(key) == value for key, value in {"tests": "1", "skipped": "0", "failures": "0", "errors": "0"}.items()),
         "compose_ci_exact_junit_suite_required")
    for node in element.iter():
        need(not (node.text or "").strip() and not (node.tail or "").strip(), "compose_ci_original_xml_private_text")
    for key, expression in {"time": r"[0-9]+(?:\.[0-9]+)?", "timestamp": r"[0-9T:Z.+-]{10,40}",
                            "hostname": r"[A-Za-z0-9_.-]{1,128}"}.items():
        need(key not in element.attrib or re.fullmatch(expression, element.attrib[key]) is not None,
             "compose_ci_original_xml_private_attribute")
    cases = element.findall("testcase")
    need(len(cases) == 1 and set(cases[0].attrib) <= {"name", "classname", "time"}
         and cases[0].get("name") == TEST_NAME and cases[0].get("classname") == TEST_CLASS
         and len(cases[0]) == 0, "compose_ci_exact_junit_method_required")
    need("time" not in cases[0].attrib or re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", cases[0].get("time", "")) is not None,
         "compose_ci_original_xml_private_attribute")
    extras = [node for node in element if node.tag != "testcase"]
    need(len({node.tag for node in extras}) == len(extras)
         and all(node.tag in {"properties", "system-out", "system-err"} and not node.attrib and len(node) == 0 for node in extras),
         "compose_ci_original_xml_private_elements")
    return {"class": TEST_CLASS, "name": TEST_NAME, "state": "passed", "original_xml_digest": sha(raw)}


def indexed_evidence(run: Path) -> tuple[dict[str, bytes], str]:
    need(not os.path.lexists(run / "failed-or-incomplete.json"), "compose_ci_native_attempt_failed_or_incomplete")
    raw_index = read_regular(run / "evidence-index.json", MAX_JSON)
    index = strict_json(raw_index)
    need(index.get("kind") == "hashes_of_completed_private_evidence_writes_not_custody_or_execution_authority"
         and index.get("excludes_this_index_and_any_later_failure_marker") is True
         and index.get("qualifies_surrounding_build_or_release") is False,
         "compose_ci_evidence_index_invalid")
    entries = index.get("entries")
    need(isinstance(entries, list) and 1 <= len(entries) <= 4096, "compose_ci_evidence_index_bound")
    result, total = {}, 0
    for row in entries:
        need(type(row) is dict and set(row) == {"path", "size", "sha256"}
             and integer(row["size"], 0, MAX_FILE) and isinstance(row["sha256"], str) and DIGEST.fullmatch(row["sha256"]),
             "compose_ci_evidence_entry_invalid")
        rel = str(relative(row["path"]))
        need(rel not in result and rel not in {"evidence-index.json", "failed-or-incomplete.json"}, "compose_ci_evidence_duplicate")
        total += row["size"]
        need(total <= 64 * 1024 * 1024, "compose_ci_evidence_total_bound")
        raw = read_regular(run / rel, row["size"])
        need(len(raw) == row["size"] and sha(raw) == row["sha256"], "compose_ci_evidence_changed")
        result[rel] = raw
    return result, sha(raw_index)


def guard_evidence(rule: str, data: dict[str, bytes]) -> dict:
    """Retained no-launch assertions are guard observations, not native negatives."""
    denied = {
        "untrusted-library-labels": "detekt_compose_library_not_authorized",
        "copied-approved-profile": "detekt_compose_library_not_authorized",
        "missing-runtime": "detekt_compose_library_missing",
        "missing-annotations": "detekt_compose_library_missing",
        "unsupported-runtime": "detekt_compose_tuple_unsupported",
        "changed-owner-resolution": "detekt_compose_owner_resolution_changed",
        "provider-instance-rebind": "detekt_compose_binding_mismatch",
        "revoked-library-permit": "detekt_compose_library_not_authorized",
        "owner-disabled-selected-rule": "rule_disabled_by_owner_selected_configuration",
    }
    rejected = {
        "wrong-source-set": "detekt_compose_compilation_mismatch",
        "wrong-membership": "detekt_compose_compilation_members_mismatch",
        "reordered-dependencies": "detekt_compose_compilation_mismatch",
        "profile-present-at-prepare": "detekt_compose_preparation_profile_present",
    }
    if rule.endswith(".UnstableCollections"):
        denied["missing-immutable-family"] = "detekt_compose_api_surface_unknown"
    expected = {"controls/" + key + "/" + suffix for key in denied for suffix in ("observation.json", "result.json")}
    expected |= {"controls/" + key + "/preparation-rejection.json" for key in rejected}
    need({key for key in data if key.startswith("controls/")} == expected, "compose_ci_exact_guard_inventory_required")
    for key, reason in denied.items():
        prefix = "controls/" + key + "/"
        observation = strict_json(data[prefix + "observation.json"])
        need(set(observation) == {"kind", "runner_callbacks", "tested", "qualified"}
             and observation["kind"] == "actual_provider_guard_check_not_detector_execution"
             and integer(observation["runner_callbacks"], 0, 0)
             and observation["tested"] is False and observation["qualified"] is False,
             "compose_ci_guard_not_no_launch")
        result = strict_json(data[prefix + "result.json"], MAX_FILE)
        coverage = result.get("coverage")
        need(result.get("status") == "partial" and result.get("providerId") == "detekt"
             and result.get("findings") == [] and result.get("rawDiagnostics") == []
             and isinstance(result.get("reasons"), list) and reason in result["reasons"]
             and isinstance(coverage, list) and len(coverage) == 1 and type(coverage[0]) is dict,
             "compose_ci_guard_result_invalid")
        coverage = coverage[0]
        need(coverage.get("ruleId") == rule and coverage.get("state") == "unavailable"
             and coverage.get("reason") == reason and coverage.get("provenance") == "not_executed"
             and coverage.get("certificateId") is None
             and all(coverage.get(field) is False for field in ("implemented", "tested", "qualified")),
             "compose_ci_guard_coverage_overclaim")
    for key, reason in rejected.items():
        observation = strict_json(data["controls/" + key + "/preparation-rejection.json"])
        need(set(observation) == {"kind", "code", "runner_callbacks", "qualified"}
             and observation["kind"] == "structural_preparation_rejection_not_provider_coverage"
             and observation["code"] == reason and integer(observation["runner_callbacks"], 0, 0)
             and observation["qualified"] is False, "compose_ci_guard_preparation_invalid")
    return {"guard_assertions_retained": len(denied) + len(rejected), "guard_native_callbacks": 0,
            "guard_observations_are_native_detector_negatives": False}


def supervisor_input(root: Path) -> dict:
    """Join actual runner identity to current built bytes; do not infer qualification."""
    source = sha(read_regular(root / "modules/provider-runtime/native/owned_process_supervisor.c", MAX_JSON))
    generated = root / SUPERVISOR_PREFIX
    raw_manifest = read_regular(generated / "manifest", 512)
    binary_digest = supervisor_manifest(raw_manifest, SUPERVISOR_TARGET, source)
    actual = file_identity(generated / SUPERVISOR_TARGET / "supervisor", 1_048_576)
    need(actual["digest"] == binary_digest, "compose_ci_current_supervisor_binary_changed")
    return {"protocol_version": SUPERVISOR_PROTOCOL, "target": SUPERVISOR_TARGET,
            "source_digest": source, "binary_digest": binary_digest}


def light_arguments(enrollment: dict, case: dict, binding_digest: str, config: bytes) -> list[str]:
    engine = enrollment["engine"]
    cli = next(row["path"] for row in engine if row["coordinate"] == "dev.detekt:detekt-cli:2.0.0-alpha.6:all")
    plugins = ":".join(row["path"] for row in engine if row["path"] != cli)
    aggregate = "\0".join(row["coordinate"] + ":" + row["digest"] for row in sorted(engine, key=lambda row: row["coordinate"]))
    return ["-Xmx384m", "-Dktchecker.detekt.composeBinding=" + binding_digest, "-Djava.io.tmpdir={output}/tmp",
            "-Dktchecker.detekt.configurationSha256=" + sha(config),
            "-Dktchecker.detekt.artifactsSha256=" + sha(aggregate.encode("utf-8")),
            "-jar", cli, "--input", ":".join("{snapshot}/" + path for path in sorted(case["compilation_files"])),
            "--base-path", "{snapshot}", "--analysis-mode", "light", "--config", "{output}/detekt.yml",
            "--plugins", plugins, "--report", "sarif:{output}/detekt.sarif", "--fail-on-severity", "Never"]


def native_evidence(root: Path, rule: str, enrollment: dict, run: Path) -> dict:
    data, index_digest = indexed_evidence(run)
    def document(rel: str) -> dict:
        need(rel in data, "compose_ci_required_evidence_missing")
        return strict_json(data[rel], MAX_FILE)
    started = document("started.json")
    completed = document("assertions-completed.json")
    slug, maximum, mode = RULES[rule]
    fixture = strict_json(read_regular(root / CORPUS / "fixtures" / (slug + ".json"), MAX_JSON))
    cases = fixture["cases"]
    need(len(cases) == maximum and fixture["rule_id"] == rule
         and started.get("selected_rule_id") == completed.get("selected_rule_id") == rule
         and started.get("selected_mode") == completed.get("selected_mode") == mode
         and all(integer(started.get(key), maximum, maximum) for key in ("source_case_count", "expected_native_calls"))
         and integer(completed.get("native_calls"), maximum, maximum)
         and completed.get("state") == "selected_assertions_completed_build_outcome_unknown",
         "compose_ci_selected_rule_evidence_mismatch")
    others = completed.get("source_oracles_not_run_for_other_rules")
    need(started.get("build_outcome") is None and started.get("qualified") is False
         and completed.get("build_outcome") is None
         and completed.get("whole_batch_observed") is False and completed.get("all_eight_rules_delivered") is False
         and completed.get("parser_compatibility_qualified") is False and completed.get("release_qualified") is False
         and completed.get("publication_committed") is False and integer(completed.get("authored_batch_rule_count"), 8, 8)
         and integer(completed.get("authored_batch_case_count"), 61, 61)
         and integer(completed.get("aggregate_elapsed_millis"), 0, AGGREGATE_MILLIS)
         and isinstance(others, list) and len(others) == 7 and all(isinstance(item, str) for item in others)
         and set(others) == ALL_RULES - {rule}, "compose_ci_native_scope_overclaim")
    owner_raw = data.get("owner/independently-selected-compilations.json", b"")
    need(sha(owner_raw) == enrollment["owner_descriptor_digest"] and strict_json(owner_raw) == enrollment["descriptor"],
         "compose_ci_observed_owner_descriptor_mismatch")
    guards = guard_evidence(rule, data)
    selected_supervisor = supervisor_input(root)
    outcomes = completed.get("cases")
    need(isinstance(outcomes, list) and len(outcomes) == maximum and all(type(row) is dict for row in outcomes)
         and [row.get("case_id") for row in outcomes] == [row["id"] for row in cases],
         "compose_ci_exact_case_inventory_required")
    processes = {key for key in data if key.startswith("cases/") and key.endswith("/process.json")}
    need(processes == {"cases/" + case["id"] + "/process.json" for case in cases},
         "compose_ci_exact_native_inventory_required")
    observed_cases = []
    for case, outcome in zip(cases, outcomes):
        prefix = "cases/" + case["id"] + "/"
        need(document(prefix + "source-oracle.json") == case, "compose_ci_case_oracle_changed")
        process = document(prefix + "process.json")
        input_value = document(prefix + "input.json")
        execution = input_value.get("execution", {})
        binding = document(prefix + "library-binding.json")
        report, config = data.get(prefix + "actual-report.sarif"), data.get(prefix + "actual-config.yml")
        need(report is not None and config is not None and config == data.get(prefix + "planned-config.yml")
             and outcome.get("report_digest") == sha(report) and outcome.get("configuration_digest") == sha(config),
             "compose_ci_actual_reports_missing_or_changed")
        need(binding.get("source") == "live_fixture_owner_preparation"
             and binding.get("owner_descriptor_digest") == enrollment["owner_descriptor_digest"]
             and binding.get("execution_granted_by_library_permit") is False
             and isinstance(binding.get("binding_digest"), str) and DIGEST.fullmatch(binding["binding_digest"])
             and outcome.get("binding_digest") == binding["binding_digest"],
             "compose_ci_live_binding_evidence_invalid")
        expected_arguments = light_arguments(enrollment, case, binding["binding_digest"], config)
        need(input_value.get("requiredRuleIds") == [rule] and execution.get("approved") is True
             and execution.get("executable") == enrollment["jdk_home"] + "/bin/java"
             and execution.get("executableDigest") == enrollment["jdk_launcher_digest"]
             and integer(execution.get("timeoutMillis"), 1, CHILD_MILLIS)
             and integer(execution.get("maxOutputBytes"), 1_048_576, 1_048_576)
             and execution.get("arguments") == expected_arguments and execution.get("environment") == {},
             "compose_ci_actual_profile_mismatch")
        expected_classpath = [{key: row[key] for key in ("coordinate", "digest", "path")}
                              for row in enrollment["descriptor"]["reviewed"]["dependencies"]]
        selected_context = input_value.get("context", {})
        expected_scope = {"module": FIXTURE_SCOPE["module"], "sourceSet": FIXTURE_SCOPE["source_set"],
                          "target": "jvm", "compilation": "main"}
        need(selected_context.get("classpath") == expected_classpath
             and selected_context.get("scope") == expected_scope
             and selected_context.get("compilerVersion") == "2.4.21"
             and selected_context.get("compilerArguments") == enrollment["descriptor"]["reviewed"]["compiler_arguments"]
             and selected_context.get("compilationFiles") == case["compilation_files"],
             "compose_ci_observed_classpath_or_compilation_mismatch")
        need(document(prefix + "input-before-profile.json") == dict(input_value, execution=None),
             "compose_ci_pre_profile_input_changed")
        materialized = str(run / "scratch" / case["id"] / "sources")
        output_parent = run / "outputs" / case["id"]
        output = process.get("output_directory")
        need(input_value.get("materializedRoot") == materialized and input_value.get("outputRoot") == str(output_parent)
             and isinstance(output, str) and output == str(Path(output)) and Path(output).parent == output_parent
             and re.fullmatch(r"provider-[A-Za-z0-9_-]{1,96}", Path(output).name) is not None,
             "compose_ci_process_paths_invalid")
        exact_path(Path(output), directory=True)
        exact_path(Path(materialized), directory=True)
        snapshot_files = input_value.get("snapshot", {}).get("files")
        need(isinstance(snapshot_files, list) and all(type(row) is dict and isinstance(row.get("path"), str) for row in snapshot_files)
             and len(snapshot_files) == len(case["sources"])
             and {row["path"] for row in snapshot_files} == set(case["sources"]), "compose_ci_snapshot_inventory_mismatch")
        for row in snapshot_files:
            source_bytes = case["sources"][row["path"]].encode("utf-8")
            need(row.get("digest") == sha(source_bytes) and integer(row.get("size"), len(source_bytes), len(source_bytes))
                 and data.get(prefix + "sources/" + row["path"]) == source_bytes
                 and read_regular(Path(materialized) / relative(row["path"]), MAX_FILE) == source_bytes,
                 "compose_ci_snapshot_source_changed")
        command = [execution["executable"]] + [value.replace("{snapshot}", materialized).replace("{output}", output)
                                                for value in expected_arguments]
        need(process.get("command") == command and process.get("status") == "executed"
             and integer(process.get("exit_code"), 0, 0)
             and all(process.get(key) is True for key in ("executed", "execution_confirmed", "successful_execution"))
             and all(process.get(key) is False for key in ("cancelled", "timed_out", "truncated"))
             and process.get("reasons") == [], "compose_ci_actual_process_incomplete")
        supervisor = process.get("supervisor_identity")
        need(type(supervisor) is dict and supervisor.get("native_qualification") is False
             and supervisor == dict(selected_supervisor, origin="actual_runner_outcome_not_an_inspector_or_synthetic_receipt",
                                    native_qualification=False), "compose_ci_actual_supervisor_identity_mismatch")
        engine = document(prefix + "engine-artifacts.json")
        need(engine == {row["coordinate"]: {key: row[key] for key in ("path", "digest")} for row in enrollment["engine"]},
             "compose_ci_observed_engine_pins_mismatch")
        need(read_regular(Path(output) / "detekt.sarif", MAX_FILE) == report
             and read_regular(Path(output) / "detekt.yml", MAX_FILE) == config, "compose_ci_original_native_output_changed")
        raw_count, admitted, withheld = (len(case[key]) for key in ("expected_raw_findings", "expected_admitted_findings", "expected_withheld_raw_findings"))
        state = "unavailable" if mode == "partial_reviewed_message_facets" else "findings" if admitted else "unknown"
        need(outcome.get("observation_kind") == "actual_detekt_light_sarif"
             and integer(outcome.get("native_invocations"), 1, 1)
             and integer(outcome.get("expected_raw_locations"), raw_count, raw_count)
             and integer(outcome.get("actual_raw_locations"), raw_count, raw_count)
             and integer(outcome.get("advisory_locations"), admitted, admitted)
             and integer(outcome.get("withheld_family_locations"), withheld, withheld)
             and outcome.get("whole_rule_state") == state and outcome.get("tested") is False and outcome.get("qualified") is False
             and outcome.get("semantic_interpretation") == case["semantic_interpretation"],
             "compose_ci_actual_case_count_or_scope_mismatch")
        result = document(prefix + "provider-result.json")
        coverage = result.get("coverage")
        need(result.get("providerId") == "detekt" and result.get("providerVersion") == "2.0.0-alpha.6"
             and result.get("status") == "partial" and isinstance(result.get("findings"), list)
             and len(result["findings"]) == admitted and isinstance(coverage, list) and len(coverage) == 1
             and type(coverage[0]) is dict, "compose_ci_actual_provider_result_mismatch")
        coverage = coverage[0]
        need(coverage.get("ruleId") == rule and coverage.get("state") == state
             and coverage.get("context") == expected_scope and coverage.get("provenance") == "executed"
             and coverage.get("certificateId") is None and coverage.get("tested") is False and coverage.get("qualified") is False
             and coverage.get("implemented") is (mode != "partial_reviewed_message_facets"),
             "compose_ci_actual_provider_coverage_overclaim")
        if mode == "partial_reviewed_message_facets":
            need(coverage.get("reason") == "detekt_compose_partial_api_surface", "compose_ci_partial_facets_promoted")
        observed_cases.append({"case_id": case["id"], "native_invocations": 1, "raw_locations": raw_count,
                               "advisory_locations": admitted, "withheld_locations": withheld, "whole_rule_state": state,
                               "report_digest": sha(report), "configuration_digest": sha(config), "qualified": False})
    need(supervisor_input(root) == selected_supervisor, "compose_ci_final_supervisor_changed")
    return {"native_calls": maximum, "selected_mode": mode, "cases": observed_cases, "private_evidence_index_digest": index_digest,
            "supervisors": [selected_supervisor], "current_generated_supervisor_bytes_joined": True,
            "private_native_reports_retained_on_job_disk": True, "private_native_reports_durable_off_host_retention_proven": False,
            "whole_rule_qualified": False, **guards}


def collect(root: Path, rule: str, lock_digest: str) -> dict:
    source, context, enrollment = enrollment_input(root, rule, lock_digest)
    layout = paths(root, rule)
    # Preserve original private XML even when the actual task failed. Unreadable,
    # absent or over-bound XML remains in place; no cleanup/reconstruction occurs.
    raw_xml = read_regular(layout["xml"], MAX_FILE)
    write_new(layout["private"] / "original-junit.xml", raw_xml)
    build_raw = read_regular(layout["build"], MAX_JSON)
    build = strict_json(build_raw)
    attempt_raw = read_regular(layout["private"] / "execute-attempt.json", MAX_JSON)
    attempt = strict_json(attempt_raw)
    arguments = build_arguments(rule, enrollment)
    need(attempt.get("source") == source and attempt.get("ci") == context
         and attempt.get("selected_rule_id") == rule and attempt.get("owner_lock_digest") == lock_digest
         and attempt.get("owner_descriptor_digest") == enrollment["owner_descriptor_digest"]
         and attempt.get("arguments") == arguments and integer(attempt.get("started_time_ns"), 1, 10**30)
         and attempt.get("state") == "launch_pending_not_execution_proof", "compose_ci_execution_attempt_mismatch")
    need(build.get("source") == source and build.get("ci") == context and build.get("selected_rule_id") == rule
         and build.get("owner_lock_digest") == lock_digest and build.get("execution_attempt_digest") == sha(attempt_raw)
         and build.get("owner_descriptor_digest") == enrollment["owner_descriptor_digest"]
         and build.get("state") == "passed" and integer(build.get("return_code"), 0, 0)
         and build.get("tasks", {}).get(TASK) == "EXECUTED" and build.get("tasks", {}).get(SUPERVISOR_TASK) == "EXECUTED"
         and build.get("required_tasks") == [SUPERVISOR_TASK, TASK] and build.get("expected_failure") is False
         and build.get("errors") == [] and build.get("raw_logs_uploaded") is False
         and build.get("command") == wrapper_argv(root) + ["--no-daemon", "--console=plain", "--max-workers=2",
                                                         "--no-parallel", "--no-configuration-cache"] + arguments,
         "compose_ci_actual_executed_task_evidence_required")
    need(os.environ.get("KT_CI_JOB_STATUS") == "success", "compose_ci_surrounding_job_not_success")
    need(enrollment.get("xml_absent_before_run") is True and integer(enrollment.get("prepared_time_ns"), 1, 10**30)
         and attempt["started_time_ns"] >= enrollment["prepared_time_ns"], "compose_ci_fresh_xml_enrollment_missing")
    need(layout["xml"].stat().st_mtime_ns >= attempt["started_time_ns"], "compose_ci_stale_xml")
    xml_files = [path for path in exact_path(layout["xml"].parent, directory=True).iterdir() if path.suffix.lower() == ".xml"]
    need(xml_files == [layout["xml"]], "compose_ci_exact_junit_file_inventory_required")
    public_xml = original_xml(raw_xml)
    actual = junit_report(layout["xml"], root)
    need(actual.get("digest") == sha(raw_xml) and not actual.get("inconsistencies")
         and actual.get("counts") == {"tests": 1, "passed": 1, "failure": 0, "error": 0, "skipped": 0},
         "compose_ci_actual_junit_not_one_pass")
    children = list(exact_path(layout["evidence"], directory=True).iterdir())
    need(len(children) == 1 and children[0].is_dir() and re.fullmatch(r"compose-state-effects-[A-Za-z0-9_-]{1,96}", children[0].name),
         "compose_ci_exact_fresh_native_run_required")
    run = exact_path(children[0], directory=True)
    observed = native_evidence(root, rule, enrollment, run)
    enrollment_input(root, rule, lock_digest)
    need(read_regular(layout["xml"], MAX_FILE) == raw_xml and read_regular(layout["build"], MAX_JSON) == build_raw
         and read_regular(layout["private"] / "execute-attempt.json", MAX_JSON) == attempt_raw,
         "compose_ci_final_report_or_build_changed")
    # Second index+write readback is a byte join, explicitly not immutable custody.
    need(indexed_evidence(run)[1] == observed["private_evidence_index_digest"], "compose_ci_final_index_changed")
    write_new(layout["public"] / "junit-original.xml", raw_xml)
    return dict(public_base("collect", rule), state="passed", source=source, ci=context,
                provider_controls_executed=True, owner_lock_digest=lock_digest,
                owner_descriptor_digest=enrollment["owner_descriptor_digest"], junit=public_xml,
                original_successful_xml_retained_byte_identical=True, build_result_digest=sha(build_raw),
                jdk_launcher_digest=enrollment["jdk_launcher_digest"], jdk_release_digest=enrollment["jdk_release_digest"],
                engine_pins=[{key: row[key] for key in ("coordinate", "digest", "size")} for row in enrollment["engine"]],
                **observed,
                qualification="Only exact selected fixture observations; partial rule coverage stays unavailable, clean full-surface cases stay unknown")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("gate", "wrapper", "provision", "execute", "collect"), required=True)
    parser.add_argument("--rule", required=True)
    parser.add_argument("--owner-lock-sha256", required=True)
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args(argv)
    root = Path(REPO_ROOT)
    os.umask(0o077)
    result = public_base(args.phase, args.rule)
    try:
        need(args.rule in RULES, "compose_ci_rule_not_supported_for_native_matrix")
        need(not args.download or args.phase == "provision", "compose_ci_download_wrong_phase")
        phases = {"gate": gate, "wrapper": wrapper, "execute": execute, "collect": collect}
        result = provision(root, args.rule, args.owner_lock_sha256, args.download) if args.phase == "provision" else phases[args.phase](root, args.rule, args.owner_lock_sha256)
    except Problem as failure:
        result["errors"] = [failure.code]
    except Exception:
        # Never print raw exception messages, paths, resolver/Gradle text or data.
        result["errors"] = ["compose_ci_invalid_or_unavailable_input"]
    output = None
    try:
        layout = paths(root, args.rule)
        filename = {"gate": "gate.json", "wrapper": "gradle-version.json", "provision": "prerequisites.json",
                    "execute": "execution.json", "collect": "summary.json"}[args.phase]
        output = layout["public"] / filename
        write_new(output, encoded(result))
    except Exception:
        result["state"] = "unavailable"
        result["errors"] = ["compose_ci_evidence_output_unavailable"]
    code = 0 if result["state"] == "passed" else 1
    print("Compose CI " + args.phase + ": " + result["state"] +
          ("" if code == 0 else " (" + result["errors"][0] + ")") + "; not release qualification")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
