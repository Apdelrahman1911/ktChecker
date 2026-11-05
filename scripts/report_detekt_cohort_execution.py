#!/usr/bin/env python3
"""Project ONE explicitly selected Detekt cohort's retained fixture receipts.

This is not rule_coverage.py, a provider runner, a qualification certificate, or
an evidence finder. This version accepts only the historical sixteen-rule/145-
analysis producer schema, NOT later expanded authoring scopes or source roots.
It never imports project code, launches a child, reads the
current source tree as a substitute, or updates catalog/activation/coverage data.
The output label is fixture_execution_checked: the pinned producer assertions
and settled execution receipts agree. Natural-project FP/FN rates, compatibility,
reproducible source-to-bytecode provenance and release readiness are NOT proved.

Selection JSON (schema_version=1, kind=detekt_cohort_fixture_execution_selection):
  source_selection: historical|current; source_digest/context_digest: sha256:HEX;
  original_repository_root: absolute original name, never opened;
  run_parent/run_directory: original-repository-relative evidence boundaries;
  provider: {id: detekt, version: STRING, artifacts: [{coordinate, digest}]};
  inputs: {source_before, source_after, command, outer_exit,
           runtime_configuration, junit, sidecar}, each {path, sha256: bare HEX};
  original_inputs: [{role: source|installed_resource, original: REF, retained: REF}];
  images: {runtime_code, corpus_code, native_binary}, each a retained REF;
  approved_rule_ids: the exact ordered sixteen-rule authoring roster;
  selected_rules: [{rule_id, slug, upstream_id, sarif_rule_id,
                    cases: [{case_id, kind, assessment, descriptor_enabled}]}].

The caller must review and pin this selection independently; hashes are not a
signature or proof against a dishonest producer. All nested reference bytes are
bounded/hash-checked. Process/SARIF/source/image bodies are opaque, not executed
or summarized. Original source/resource references MUST have explicit retained
bindings; no implicit fallback or filesystem discovery. The START source tuple
and fixture-set digest are recomputed from those original names and hashes.
The whole-command source digest is only a pinned execution-receipt claim, NOT
recomputed from the smaller START subset. The mandatory current-source comparison
is caller supplied, not observed by this reporter. Historical selection is legal.

CLI requires --input-root, --run-evidence, --expect-run-evidence-sha256,
--expect-source-digest, --expect-context-digest, --expect-provider-version,
--current-source-digest, and --output. Input root is an explicit original-relative
mirror; output must be a NEW file outside it, in an existing directory. POSIX
no-follow/dir-fd support is required. No implicit cwd, output replacement or mkdir.
"""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
import os
import re
import stat
import sys
import xml.etree.ElementTree as ET


MAX_FILE = 32_000_000
MAX_TOTAL = 128_000_000
MAX_FILES = 2000
MAX_JSON = 2_000_000
MAX_OUTPUT = 2_000_000
TASK = ":provider-detekt:corpusTest"
TEST_CLASS = "dev.ktchecker.providers.detekt.DetektSyntaxCorpusIntegrationTest"
TEST_NAME = "explicit bounded selected cohort retains actual native outcomes and heuristic gaps()"
KINDS = ("detector_analysis", "cli_probe", "unsupported_target_abstention",
         "changed_configuration_authorization_denial")
CASE_KINDS = ("positive", "negative", "configuration", "version", "suppression", "regression")
SIGNALS = dict(zip(KINDS, ("runtime_validated_owned_child_terminal",
                          "runtime_validated_owned_child_terminal",
                          "provider_abstention_without_runtime_callback",
                          "exact_authorization_refusal_before_setup")))
SOURCE_ROOTS = ("modules/provider-detekt/src/main", "modules/provider-detekt/src/corpusTest",
                "modules/provider-runtime/src/main", "modules/provider-runtime/native",
                "modules/contracts/src/main", "modules/snapshot/src/main",
                "providers/detekt-fixtures/corpus/syntax-v1",
                "providers/detekt-fixtures/corpus/syntax-expansion-v1")
BUILD_FILES = ("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
               "gradle/wrapper/gradle-wrapper.properties", "modules/provider-detekt/build.gradle.kts",
               "modules/provider-runtime/build.gradle.kts", "modules/contracts/build.gradle.kts",
               "modules/snapshot/build.gradle.kts", "providers/detekt-fixtures/test_syntax_corpus.py",
               "providers/detekt-fixtures/test_syntax_expansion.py",
               "providers/detekt-fixtures/corpus/syntax-v1/README.md")
RUNTIME_SOURCE = "modules/provider-runtime/src/main/kotlin/dev/ktchecker/providers/runtime/Execution.kt"
NATIVE_SOURCE = "modules/provider-runtime/native/owned_process_supervisor.c"


class ReportError(ValueError):
    """Fail closed; messages contain labels, never input bodies or command text."""


def need(condition, label):
    if not condition:
        raise ReportError(label)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def same(actual, expected, label):
    if type(expected) in (bytes, set):
        need(type(actual) is type(expected) and actual == expected, label)
    else:
        need(canonical(actual) == canonical(expected), label)


def obj(value, label):
    need(type(value) is dict, label)
    return value


def keys(value, names, label):
    need(set(obj(value, label)) == set(names), label)


def integer(value, minimum, maximum, label):
    need(type(value) is int and minimum <= value <= maximum, label)
    return value


def text_value(value, label, maximum=4096):
    need(type(value) is str and 0 < len(value) <= maximum and
         not any(ord(c) < 32 for c in value), label)
    return value


def sha(value, prefixed=False):
    pattern = r"sha256:[0-9a-f]{64}" if prefixed else r"[0-9a-f]{64}"
    need(type(value) is str and re.fullmatch(pattern, value) is not None, "invalid digest")
    return value


def relative(value):
    text_value(value, "invalid relative path", 2048)
    need(not value.startswith("/") and "\\" not in value and ":" not in value and
         all(p not in ("", ".", "..") for p in value.split("/")), "invalid relative path")
    return value


def absolute(value):
    need(type(value) is str and value.startswith("/") and value != "/", "absolute path required")
    relative(value[1:])
    return value


def beneath(path, directory):
    return path.startswith(directory + "/")


def ref(value):
    keys(value, ("path", "sha256"), "invalid reference")
    relative(value["path"])
    sha(value["sha256"])
    return value


def references(value, nonempty=True):
    need(type(value) is list and (1 if nonempty else 0) <= len(value) <= MAX_FILES,
         "invalid reference list")
    result = [ref(item) for item in value]
    need(len({item["path"] for item in result}) == len(result), "duplicate reference")
    return result


def parse_json(data):
    need(len(data) <= MAX_JSON, "JSON byte limit")
    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, "duplicate JSON key")
            result[key] = value
        return result
    def constant(_):
        raise ReportError("nonfinite JSON number")
    try:
        value = json.loads(data, object_pairs_hook=pairs, parse_constant=constant)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise ReportError("malformed JSON") from error
    pending, count = [(value, 0)], 0
    while pending:
        item, depth = pending.pop()
        count += 1
        need(depth <= 32 and count <= 100_000, "JSON structure limit")
        if type(item) is dict:
            need(all(len(k) <= 256 for k in item), "JSON key limit")
            for k in ("qualified", "native_qualification", "compatibility_qualified", "release_qualified"):
                if k in item:
                    need(item[k] is False, "qualification promotion rejected")
            pending.extend((v, depth + 1) for v in item.values())
        elif type(item) is list:
            pending.extend((v, depth + 1) for v in item)
        elif type(item) is str:
            need(len(item) <= 65_536, "JSON string limit")
        elif type(item) is float:
            need(math.isfinite(item), "nonfinite JSON number")
    return obj(value, "JSON object required")


def identity(info):
    need(info.st_ino > 0 and info.st_dev >= 0, "unavailable file identity")
    return info.st_dev, info.st_ino


def fingerprint(info):
    return (identity(info), info.st_mode, info.st_nlink, info.st_size,
            info.st_mtime_ns, info.st_ctime_ns)


def open_directory(path):
    absolute(path)
    need(hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY") and
         os.open in os.supports_dir_fd, "POSIX no-follow handles required")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    fd = os.open("/", flags)
    ancestors = [identity(os.fstat(fd))]
    try:
        for part in path[1:].split("/"):
            next_fd = os.open(part, flags, dir_fd=fd)
            os.close(fd)
            fd = next_fd
            item = identity(os.fstat(fd))
            need(item not in ancestors, "directory alias")
            ancestors.append(item)
        return fd, ancestors
    except BaseException:
        os.close(fd)
        raise


class ReadSet:
    """An explicit, bounded byte snapshot. No directory walk or source fallback."""

    def __init__(self, root):
        self.root = absolute(root)
        self.fd, _ = open_directory(self.root)
        self.directories = {"": identity(os.fstat(self.fd))}
        self.files = {}
        self.total = 0

    def close(self):
        os.close(self.fd)

    def read(self, reference, repeat=False):
        reference = ref(reference)
        path, expected = reference["path"], reference["sha256"]
        if path in self.files and not repeat:
            old_digest, _, data = self.files[path]
            same(expected, old_digest, "reference digest conflict")
            return data
        fd = os.dup(self.fd)
        try:
            prefix = ""
            for part in path.split("/")[:-1]:
                next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.close(fd)
                fd = next_fd
                prefix = part if not prefix else prefix + "/" + part
                current = identity(os.fstat(fd))
                if prefix in self.directories:
                    same(current, self.directories[prefix], "directory identity changed")
                else:
                    need(current not in self.directories.values(), "directory alias")
                    self.directories[prefix] = current
            file_fd = os.open(path.split("/")[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
            try:
                before = os.fstat(file_fd)
                need(stat.S_ISREG(before.st_mode) and before.st_nlink == 1, "regular unaliased file required")
                need(0 <= before.st_size <= MAX_FILE, "file byte limit")
                if path not in self.files:
                    need(len(self.files) < MAX_FILES and self.total + before.st_size <= MAX_TOTAL,
                         "evidence read budget")
                    need(identity(before) not in [v[1][0] for v in self.files.values()], "file identity alias")
                chunks, size = [], 0
                while True:
                    block = os.read(file_fd, min(65_536, MAX_FILE + 1 - size))
                    if not block:
                        break
                    chunks.append(block)
                    size += len(block)
                    need(size <= MAX_FILE and size <= before.st_size, "file grew beyond limit")
                data = b"".join(chunks)
                same(fingerprint(os.fstat(file_fd)), fingerprint(before), "file changed while reading")
                same(fingerprint(os.stat(path.split("/")[-1], dir_fd=fd, follow_symlinks=False)),
                     fingerprint(before), "file path rebound")
                same(len(data), before.st_size, "short evidence read")
                same(digest(data), expected, "evidence digest mismatch")
                if path in self.files:
                    same(fingerprint(before), self.files[path][1], "evidence identity changed")
                else:
                    self.total += len(data)
                    self.files[path] = (expected, fingerprint(before), data)
                return data
            finally:
                os.close(file_fd)
        finally:
            os.close(fd)

    def document(self, reference):
        return parse_json(self.read(reference))

    def recheck(self):
        # Reopen the named root as well as anchored leaves: a renamed/rebound root
        # must not silently become an apparently current evidence location.
        fd, _ = open_directory(self.root)
        try:
            same(identity(os.fstat(fd)), self.directories[""], "input root rebound")
        finally:
            os.close(fd)
        for path, (expected, _, _) in list(self.files.items()):
            self.read({"path": path, "sha256": expected}, repeat=True)


class Evidence:
    def __init__(self, reader, selection):
        self.reader = reader
        self.run = selection["run_directory"]
        self.bindings, self.used, self.claims = {}, set(), {}
        bindings = selection["original_inputs"]
        need(type(bindings) is list and 1 <= len(bindings) <= 500, "original input boundary required")
        retained_names = set()
        for binding in bindings:
            keys(binding, ("role", "original", "retained"), "invalid original input binding")
            origin, retained = ref(binding["original"]), ref(binding["retained"])
            need(origin["path"] not in self.bindings and retained["path"] not in retained_names,
                 "duplicate original input binding")
            same(origin["sha256"], retained["sha256"], "retained original digest mismatch")
            need(not beneath(origin["path"], self.run) and beneath(retained["path"], self.run),
                 "original input boundary overlap")
            if binding["role"] == "source":
                need(origin["path"] in BUILD_FILES or any(beneath(origin["path"], p) for p in SOURCE_ROOTS),
                     "source outside original producer boundaries")
                need("__pycache__" not in origin["path"].split("/"), "non-source cache boundary")
                same(retained["path"], self.run + "/at-start/" + origin["path"], "START source location mismatch")
            else:
                same(binding["role"], "installed_resource", "unknown original input role")
                need(beneath(origin["path"], "modules/provider-runtime/build") and
                     any(beneath(retained["path"], self.run + "/" + p) for p in ("native-image", "code-images")),
                     "installed resource boundary mismatch")
            self.bindings[origin["path"]] = binding
            retained_names.add(retained["path"])

    def read(self, reference):
        reference = ref(reference)
        path = reference["path"]
        if path in self.claims:
            same(self.claims[path], reference["sha256"], "conflicting evidence reference")
        self.claims[path] = reference["sha256"]
        if path in self.bindings:
            binding = self.bindings[path]
            same(reference, binding["original"], "original reference mismatch")
            self.used.add(path)
            reference = binding["retained"]
        else:
            need(beneath(path, self.run), "unbound external evidence reference")
        return self.reader.read(reference)

    def document(self, reference):
        return parse_json(self.read(reference))

    def follow(self, value, nonempty=True):
        result = references(value, nonempty)
        for item in result:
            self.read(item)
        return {item["path"]: item for item in result}


def timestamp(value):
    text_value(value, "invalid timestamp", 64)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ReportError("invalid timestamp") from error
    need(parsed.tzinfo is not None, "timestamp timezone required")
    return parsed.astimezone(timezone.utc)


def seconds(value):
    need(type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 1800,
         "invalid elapsed seconds")
    return value


def artifact_identities(value):
    need(type(value) is list and 1 <= len(value) <= 16, "invalid provider artifacts")
    result = []
    for item in value:
        obj(item, "invalid artifact")
        coordinate = text_value(item.get("coordinate"), "invalid artifact coordinate", 256)
        result.append({"coordinate": coordinate, "digest": sha(item.get("digest"), True)})
    need(len({item["coordinate"] for item in result}) == len(result), "duplicate artifact coordinate")
    return sorted(result, key=lambda item: item["coordinate"])


def supervisor(value, context, installed=False):
    obj(value, "missing supervisor identity")
    for name in ("protocol_version", "target", "source_digest", "binary_digest"):
        same(value.get(name), context["supervisor_" + name], "supervisor identity mismatch")
    need(value.get("native_qualification") is False, "supervisor qualification")
    origin = ("exact_installed_code_source_manifest_and_binary_at_START_not_inspector" if installed
              else "actual_launched_helper_result_not_inspector")
    same(value.get("identity_origin"), origin, "supervisor origin mismatch")


def expected_schedule(selection):
    approved = selection["approved_rule_ids"]
    need(type(approved) is list and len(approved) == 16 and len(set(approved)) == 16,
         "complete sixteen-rule roster required")
    for rule_id in approved:
        need(re.fullmatch(r"ktc\.[a-z0-9.-]+", text_value(rule_id, "invalid rule ID", 256)) is not None,
             "invalid rule ID")
    rules = selection["selected_rules"]
    need(type(rules) is list and 1 <= len(rules) < 16, "explicit bounded selected rules required")
    ids, schedule = [], []
    for index, rule in enumerate(rules):
        keys(rule, ("rule_id", "slug", "upstream_id", "sarif_rule_id", "cases"), "invalid selected rule")
        rule_id = rule["rule_id"]
        need(rule_id in approved and rule_id not in ids, "selected rule mismatch or duplicate")
        ids.append(rule_id)
        need(re.fullmatch(r"[a-z0-9-]+", text_value(rule["slug"], "invalid slug", 128)) is not None,
             "invalid slug")
        text_value(rule["upstream_id"], "invalid upstream ID", 256)
        text_value(rule["sarif_rule_id"], "invalid SARIF ID", 256)
        cases = rule["cases"]
        need(type(cases) is list and 6 <= len(cases) <= 36, "explicit case roster required")
        seen = set()
        for position, case in enumerate(cases):
            keys(case, ("case_id", "kind", "assessment", "descriptor_enabled"), "invalid selected case")
            name = text_value(case["case_id"], "invalid case ID", 128)
            need(re.fullmatch(r"[a-z0-9-]+", name) is not None and name not in seen, "case ID duplicate or invalid")
            seen.add(name)
            need(case["kind"] in CASE_KINDS, "invalid case kind")
            if position < 6:
                same((name, case["kind"]), (CASE_KINDS[position], CASE_KINDS[position]), "standard six-case order")
            text_value(case["assessment"], "invalid authored assessment", 256)
            same(case["descriptor_enabled"], name != "configuration", "disabled descriptor scope")
            schedule.append((KINDS[0], rule_id, name))
            if name == "positive":
                schedule.append((KINDS[3], rule_id, "configuration-permit-replay"))
            if name == "version":
                if index == 0:
                    schedule.append((KINDS[1], rule_id, "cli-version-probe"))
                schedule.extend((KINDS[2], rule_id, "unsupported-" + target) for target in ("common", "native"))
    need(len(schedule) <= 40 and len(set(schedule)) == len(schedule), "invocation schedule limit")
    return ids, [rule for rule in approved if rule not in ids], schedule


def completion(reader, inputs, selection, context, source_digest):
    before, after = (reader.document(inputs[key]) for key in ("source_before", "source_after"))
    keys(before, ("commit", "dirty", "file_count", "unsupported_directories", "working_tree_digest", "digest_scope"),
         "source receipt boundary schema")
    same(before, after, "before/after source mismatch")
    same(before.get("working_tree_digest"), source_digest, "selected command source mismatch")
    need(re.fullmatch(r"[0-9a-f]{40}", text_value(before.get("commit"), "invalid source commit", 40)) is not None,
         "invalid source commit")
    need(type(before.get("dirty")) is bool, "source dirty state missing")
    integer(before.get("file_count"), 1, 100_000, "source file count")
    same(before.get("unsupported_directories"), 0, "unsupported source boundary")
    same(before.get("digest_scope"),
         "tracked + untracked nonignored actual bytes, missing files, symlinks and executable bits",
         "source digest scope mismatch")
    command = reader.document(inputs["command"])
    for name, value in {"schema_version": 1, "evidence_kind": "executed_gradle_command", "state": "passed",
                        "return_code": 0, "expected_failure": False, "expected_failed_tasks": [], "errors": [],
                        "required_tasks": [TASK]}.items():
        same(command.get(name), value, "settled command mismatch")
    same(command.get("source"), before, "command/source receipt mismatch")
    same(obj(command.get("tasks"), "missing actual task outcomes").get(TASK), "EXECUTED", "corpus task not executed")
    same(reader.document(inputs["outer_exit"]).get("return_code"), 0, "outer execution failed")
    runtime = reader.document(inputs["runtime_configuration"])
    for name, value in {"task": TASK, "workers": 1, "forks": 1, "fork_every": 1,
                        "selected": [TEST_CLASS], "cohort": context["corpus_cohort"],
                        "max_invocations": str(context["invocation_ceiling"]),
                        "aggregate_millis": str(context["aggregate_limit_millis"]),
                        "child_millis": str(context["child_limit_millis"]),
                        "evidence_root": selection["original_repository_root"] + "/" + selection["run_parent"] + "/evidence"}.items():
        same(runtime.get(name), value, "runtime selector mismatch")
    xml = reader.read(inputs["junit"])
    need(len(xml) <= MAX_JSON and b"\x00" not in xml, "unsafe JUnit XML envelope")
    try:
        xml_text = xml.decode("utf-8", errors="strict")
    except UnicodeError as error:
        raise ReportError("JUnit XML must be UTF-8") from error
    declaration = re.search(r"<\?xml\b[^?]*\bencoding\s*=\s*['\"]([^'\"]+)['\"]", xml_text, re.IGNORECASE)
    need(declaration is None or declaration.group(1).upper() == "UTF-8", "JUnit XML encoding must be UTF-8")
    need("<!DOCTYPE" not in xml_text.upper() and "<!ENTITY" not in xml_text.upper(), "unsafe JUnit XML")
    try:
        suite = ET.fromstring(xml_text)
    except ET.ParseError as error:
        raise ReportError("malformed JUnit XML") from error
    need(suite.tag == "testsuite", "single JUnit suite required")
    for name, value in {"name": TEST_CLASS, "tests": "1", "failures": "0", "errors": "0", "skipped": "0"}.items():
        same(suite.get(name), value, "JUnit totals mismatch")
    cases = list(suite.iter("testcase"))
    need(len(cases) == 1 and len(suite.findall("testcase")) == 1, "single settled JUnit method required")
    same((cases[0].get("name"), cases[0].get("classname")), (TEST_NAME, TEST_CLASS), "JUnit method mismatch")
    need(not any(node.tag in ("failure", "error", "skipped", "testsuite") for node in list(suite.iter())[1:]),
         "JUnit failure/incomplete suite")
    start = timestamp(command.get("started_at"))
    end = start + timedelta(seconds=seconds(command.get("elapsed_seconds")) + 1)
    junit_start = timestamp(suite.get("timestamp"))
    try:
        junit_end = junit_start + timedelta(seconds=seconds(float(suite.get("time", "nan"))) + 1)
    except ValueError as error:
        raise ReportError("invalid JUnit duration") from error
    need(start <= junit_start <= junit_end <= end + timedelta(seconds=1), "JUnit/command time boundary mismatch")
    return before, (junit_start, min(junit_end, end))


def start_bindings(evidence, sidecar, selection, context):
    witness = evidence.document(sidecar["start_witness"])
    same(witness.get("schema_version"), 1, "START witness schema")
    need(witness.get("qualified") is False, "START witness qualification")
    refs = evidence.follow(witness.get("references"))
    same(evidence.used, set(evidence.bindings), "unused or missing original input binding")
    for binding in evidence.bindings.values():
        need(binding["retained"]["path"] in refs, "retained original absent from START witness")
        same(refs[binding["retained"]["path"]], binding["retained"], "retained START reference mismatch")
    sources = {name: binding["original"]["sha256"] for name, binding in evidence.bindings.items()
               if binding["role"] == "source"}
    def tuple_digest(items):
        return "sha256:" + digest("\n".join(name + ":sha256:" + value for name, value in sorted(items)).encode())
    same(tuple_digest(sources.items()), context["corpus_source_tuple_digest"], "START source tuple mismatch")
    fixtures = [(name, value) for name, value in sources.items()
                if name.endswith(".json") and any(beneath(name, p) for p in SOURCE_ROOTS[-2:])]
    need(fixtures, "START fixture boundary absent")
    same(tuple_digest(fixtures), context["corpus_fixture_set_digest"], "START fixture-set mismatch")
    for name, field in ((RUNTIME_SOURCE, "runtime_execution_source_digest"), (NATIVE_SOURCE, "supervisor_source_digest")):
        need(name in sources, "required original source absent")
        same("sha256:" + sources[name], context[field], "original runtime/native source mismatch")
    supervisor(witness.get("installed_supervisor_identity"), context, installed=True)
    keys(selection["images"], ("runtime_code", "corpus_code", "native_binary"), "missing exact code images")
    for name, field in (("runtime_code", "runtime_code_image_digest"), ("corpus_code", "corpus_code_image_digest"),
                        ("native_binary", "supervisor_binary_digest")):
        image = ref(selection["images"][name])
        same(refs.get(image["path"]), image, "code image absent from START")
        prefix = "/native-image/" if name == "native_binary" else "/code-images/"
        need(beneath(image["path"], evidence.run + prefix.rstrip("/")), "code image boundary mismatch")
        same("sha256:" + image["sha256"], context[field], "code image/context mismatch")
    manifest_path = evidence.run + "/native-image/manifest"
    need(manifest_path in refs, "native manifest absent from START")
    native = (context["supervisor_protocol_version"] + "\n" + context["supervisor_target"] + "\n" +
              context["supervisor_source_digest"] + "\n" + context["supervisor_binary_digest"] + "\n").encode()
    same(evidence.read(refs[manifest_path]), native, "native manifest/context mismatch")
    tool_path = evidence.run + "/tool-start-witness.json"
    need(tool_path in refs, "tool START witness absent")
    tool = evidence.document(refs[tool_path])
    same(tool.get("schema_version"), 1, "tool witness schema")
    need(tool.get("qualified") is False, "tool witness qualification")
    artifacts = artifact_identities(tool.get("artifacts"))
    same(artifacts, artifact_identities(selection["provider"]["artifacts"]), "provider artifact mismatch")
    cli = "dev.detekt:detekt-cli:" + selection["provider"]["version"] + ":all"
    need(cli in [item["coordinate"] for item in artifacts], "selected CLI artifact absent")
    sha(tool.get("java_executable_digest"), True)
    text_value(tool.get("java_runtime_version"), "Java runtime witness missing", 256)
    same(tool["java_runtime_version"].split("+", 1)[0], context["jdk_version"], "Java runtime/context mismatch")
    return witness, refs, artifacts


def progress(evidence, sidecar, context, schedule, bounds):
    refs = references(sidecar["progress_evidence"])
    same(len(refs), 3 * len(schedule) + 2, "progress event count mismatch")
    events = []
    previous_elapsed, previous_remaining, previous_time = -1, context["aggregate_limit_millis"], bounds[0]
    for sequence, reference in enumerate(refs, 1):
        same(reference["path"], evidence.run + "/progress/" + f"{sequence:04d}.json", "progress path/order mismatch")
        event = evidence.document(reference)
        for field, value in (("schema_version", 1), ("sequence", sequence), ("cohort", context["corpus_cohort"]), ("qualified", False)):
            same(event.get(field), value, "progress identity mismatch")
        elapsed = integer(event.get("elapsed_monotonic_millis"), 0, context["aggregate_limit_millis"], "progress elapsed bound")
        remaining = integer(event.get("remaining_millis"), 1, context["aggregate_limit_millis"], "progress remaining bound")
        now = timestamp(event.get("captured_at"))
        need(previous_elapsed <= elapsed and remaining <= previous_remaining and
             elapsed + remaining <= context["aggregate_limit_millis"] and previous_time <= now <= bounds[1],
             "stale/reordered/deadline-exhausted progress")
        previous_elapsed, previous_remaining, previous_time = elapsed, remaining, now
        events.append(event)
    for event, phase, count in ((events[0], "run_started", 0), (events[-1], "all_cohort_invocations_checked", len(schedule))):
        for field, value in (("phase", phase), ("reserved_invocations", count), ("checked_invocations", count)):
            same(event.get(field), value, "progress run boundary mismatch")
        need(not any(key in event for key in ("invocation", "kind", "rule_id", "case_id")), "run boundary ticket contamination")
    checked = {}
    for ordinal, ticket in enumerate(schedule, 1):
        kind, rule_id, case_id = ticket
        reserved, observed, done = events[1 + (ordinal - 1) * 3:1 + ordinal * 3]
        observe_phase = "actual_provider_abstention_retained" if kind == KINDS[2] else "actual_runtime_callback_retained"
        for event, phase in zip((reserved, observed, done), ("reserved_before_call", observe_phase, "assertions_checked")):
            for field, value in (("phase", phase), ("invocation", ordinal), ("kind", kind), ("rule_id", rule_id),
                                 ("case_id", case_id), ("reserved_invocations", ordinal), ("checked_invocations", ordinal - 1)):
                same(event.get(field), value, "ticket schedule/count mismatch")
        for event in (observed, done):
            same(event.get("cleanup_signal"), SIGNALS[kind], "unverified terminal/no-execution signal")
        need(done.get("passed") is True, "unchecked invocation")
        if kind in (KINDS[0], KINDS[1]):
            for field, value in (("actual_status", "executed"), ("actual_exit_code", 0), ("execution_confirmed", True),
                                 ("actual_reasons", []), ("actual_supervisor_target", context["supervisor_target"])):
                same(observed.get(field), value, "native runtime outcome mismatch")
        elif kind == KINDS[3]:
            for field, value in (("actual_status", "unavailable"), ("actual_exit_code", None), ("execution_confirmed", False),
                                 ("actual_reasons", ["execution_not_authorized"]), ("actual_supervisor_target", None)):
                same(observed.get(field), value, "denial must not execute")
        else:
            for field, value in (("actual_status", "partial"), ("runtime_callback_observed", False),
                                 ("unexpected_execution_confirmed", None),
                                 ("actual_reasons", ["detekt_jvm_provider_does_not_qualify_common_or_native_targets"])):
                same(observed.get(field), value, "abstention must not execute")
        checked[ticket] = evidence.follow(done.get("evidence"))
    return checked, events


def control_receipts(evidence, checked, plans, context, provider_version):
    by_rule = {rule["rule_id"]: rule for rule in plans}
    links = {}
    shared_probe = None
    for (kind, rule_id, case_id), refs in checked.items():
        if kind == KINDS[0]:
            continue
        base = evidence.run + "/cases/" + by_rule[rule_id]["slug"]
        if kind == KINDS[1]:
            receipt_path = evidence.run + "/version/receipt.json"
            expected_paths = [evidence.run + "/version/process.json", receipt_path]
            case = "version"
        elif kind == KINDS[3]:
            prefix = base + "/positive/configuration-permit-replay"
            receipt_path = prefix + "-denial-receipt.json"
            expected_paths = [prefix + ".json", prefix + "-denial-process.json", receipt_path]
            case = "positive"
        else:
            target = case_id.removeprefix("unsupported-")
            prefix = base + "/version/" + target
            receipt_path = prefix + "-abstention-receipt.json"
            expected_paths = [prefix + "-provider-result.json", receipt_path]
            case = "version"
        same(set(refs), set(expected_paths), "control progress receipt boundary mismatch")
        receipt = evidence.document(refs[receipt_path])
        if kind == KINDS[1]:
            for field, value in (("actual_exit_code", 0), ("execution_confirmed", True),
                                 ("declared_project_compiler", context["compiler_version"]),
                                 ("embedded_compiler_pin", context["embedded_kotlin_version"]),
                                 ("compatibility_qualified", False), ("process", refs[expected_paths[0]]), ("stderr", "")):
                same(receipt.get(field), value, "shared version probe mismatch")
            need(type(receipt.get("stdout")) is str and receipt["stdout"].strip() == provider_version,
                 "shared probe version mismatch")
            supervisor(receipt.get("supervisor_identity"), context)
            shared_probe = refs
        else:
            need(receipt.get("passed") is True and receipt.get("qualified") is False, "control receipt not checked")
            if kind == KINDS[3]:
                for field, value in (("case_kind", "changed_configuration_permit_replay"), ("execution_confirmed", False),
                                     ("supervisor_identity", None), ("provider_result", refs[expected_paths[0]]),
                                     ("process", refs[expected_paths[1]])):
                    same(receipt.get(field), value, "authorization refusal receipt mismatch")
            else:
                for field, value in (("case_kind", "unsupported_target_abstention"), ("target", target), ("rule_id", rule_id),
                                     ("runtime_callback_observed", False), ("cleanup_signal", SIGNALS[kind]),
                                     ("provider_result", refs[expected_paths[0]])):
                    same(receipt.get(field), value, "abstention receipt mismatch")
        links.setdefault((rule_id, case), {}).update(refs)
    need(shared_probe is not None, "shared version probe absent")
    for rule_id in by_rule:
        links.setdefault((rule_id, "version"), {}).update(shared_probe)
    return links


def case_results(evidence, row, expected, context, artifacts, start_ref, checked, control_links):
    cases = row.get("cases")
    need(type(cases) is list, "case rows missing")
    same(len(cases), len(expected["cases"]), "case roster count mismatch")
    output = []
    for case, plan in zip(cases, expected["cases"]):
        obj(case, "invalid sidecar case")
        for field, value in (("case_id", plan["case_id"]), ("kind", plan["kind"]), ("assessment", plan["assessment"]), ("passed", True)):
            same(case.get(field), value, "sidecar case roster/assertion mismatch")
        prefix = evidence.run + "/cases/" + expected["slug"] + "/" + plan["case_id"]
        refs = evidence.follow(case.get("evidence"))
        need(all(refs.get(path) == value for path, value in
                 control_links.get((expected["rule_id"], plan["case_id"]), {}).items()), "case/control progress join mismatch")
        receipt_ref, analysis_ref = (refs.get(prefix + "/" + name) for name in ("receipt.json", "analysis-checked.json"))
        need(receipt_ref is not None and analysis_ref is not None, "case receipt/analysis witness missing")
        receipt, analysis = evidence.document(receipt_ref), evidence.document(analysis_ref)
        for item in (receipt, analysis):
            for field, value in (("case_id", plan["case_id"]), ("rule_id", expected["rule_id"]), ("passed", True),
                                 ("qualified", False), ("execution_confirmed", True)):
                same(item.get(field), value, "case execution identity mismatch")
            supervisor(item.get("supervisor_identity"), context)
        for field, value in (("schema_version", 1), ("case_kind", plan["kind"]), ("provider_id", "detekt"),
                             ("provider_version", context["provider_version"]), ("upstream_id", expected["upstream_id"]),
                             ("actual_exit_code", 0), ("actual_provider_status", "partial"), ("analysis_mode", "light"),
                             ("registered_materialization_verified", True), ("source_rewrites", False),
                             ("authored_assessment", plan["assessment"]), ("start_witness", start_ref)):
            same(receipt.get(field), value, "receipt scope mismatch")
        for field in ("snapshot_id", "analysis_key", "configuration_sha256"):
            sha(receipt.get(field), True)
        if receipt.get("parent_snapshot_id") is not None:
            sha(receipt["parent_snapshot_id"], True)
        for field in ("authored_intent", "assessment_scope"):
            text_value(receipt.get(field), "receipt authored assessment missing")
        same(artifact_identities(receipt.get("artifact_pins")), artifacts, "case/provider artifact swap")
        receipt_refs = evidence.follow(receipt.get("artifacts"))
        same(receipt_refs, {path: value for path, value in refs.items() if path != receipt_ref["path"]},
             "case artifact closure mismatch")
        for field, value in (("selected_sarif_rule_id", expected["sarif_rule_id"]),
                             ("selected_sarif_descriptor_enabled", plan["descriptor_enabled"]),
                             ("cleanup_signal", SIGNALS[KINDS[0]])):
            same(analysis.get(field), value, "analysis descriptor/terminal mismatch")
        analysis_refs = evidence.follow(analysis.get("evidence"))
        # The producer always retains these five opaque artifacts plus every
        # selected case source. A consistently re-pinned empty/weakened graph
        # is not a substitute for that minimum per-case evidence boundary.
        core = {prefix + "/" + name for name in ("input.json", "provider-result.json", "generated-config.yml", "process.json", "report.sarif")}
        sources = {path for path in analysis_refs if beneath(path, prefix + "/sources")}
        need(1 <= len(sources) <= 128 and set(analysis_refs) == core | sources,
             "analysis requires five exact core artifacts and bounded same-case sources")
        config_ref = analysis_refs.get(prefix + "/generated-config.yml")
        need(config_ref is not None, "case configuration artifact absent")
        same("sha256:" + config_ref["sha256"], receipt["configuration_sha256"], "case configuration digest mismatch")
        same({**analysis_refs, analysis_ref["path"]: analysis_ref},
             checked[(KINDS[0], expected["rule_id"], plan["case_id"])], "analysis progress/receipt join mismatch")
        need(all(refs.get(path) == value for path, value in analysis_refs.items()), "analysis artifact not in receipt")
        for field in ("findings", "expected_findings"):
            need(type(receipt.get(field)) is list and len(receipt[field]) <= 10_000, "finding count bound")
        same(len(receipt["findings"]), len(receipt["expected_findings"]), "producer finding counts disagree")
        output.append({"case_id": plan["case_id"], "kind": plan["kind"], "fixture_execution_checked": True,
                       "authored_assessment": plan["assessment"], "descriptor_enabled": plan["descriptor_enabled"],
                       "observed_findings": len(receipt["findings"]), "authored_expected_findings": len(receipt["expected_findings"]),
                       "provider_status": receipt["actual_provider_status"], "execution_confirmed": True,
                       "analysis_key": receipt["analysis_key"], "snapshot_id": receipt["snapshot_id"],
                       "parent_snapshot_id": receipt.get("parent_snapshot_id"), "configuration_sha256": receipt["configuration_sha256"],
                       "receipt": receipt_ref, "analysis_checked": analysis_ref, "qualified": False})
    return output


def create_report(reader, run_evidence, selection_sha, source_digest, context_digest, provider_version, current_source_digest):
    for value in (source_digest, context_digest, current_source_digest):
        sha(value, True)
    selection_ref = {"path": relative(run_evidence), "sha256": sha(selection_sha)}
    selection = reader.document(selection_ref)
    keys(selection, ("schema_version", "kind", "source_selection", "source_digest", "context_digest",
                     "original_repository_root", "run_parent", "run_directory", "provider", "inputs",
                     "original_inputs", "images", "approved_rule_ids", "selected_rules"), "invalid explicit selection schema")
    same(selection["schema_version"], 1, "selection schema version")
    same(selection["kind"], "detekt_cohort_fixture_execution_selection", "selection kind")
    same(selection["source_digest"], source_digest, "expected selected source mismatch")
    same(selection["context_digest"], context_digest, "expected selected context mismatch")
    need(selection["source_selection"] in ("historical", "current"), "explicit source selection required")
    if selection["source_selection"] == "current":
        same(source_digest, current_source_digest, "current selection differs from caller comparison")
    absolute(selection["original_repository_root"])
    parent, run = relative(selection["run_parent"]), relative(selection["run_directory"])
    need(beneath(parent, "build/ci-evidence") and beneath(run, parent + "/evidence/detekt/runs") and
         "/" not in run[len(parent + "/evidence/detekt/runs/"):], "explicit run boundary mismatch")
    keys(selection["provider"], ("id", "version", "artifacts"), "invalid provider selection")
    same(selection["provider"]["id"], "detekt", "provider selection mismatch")
    text_value(provider_version, "provider version required", 128)
    same(selection["provider"]["version"], provider_version, "provider version selection mismatch")
    inputs = selection["inputs"]
    names = {"source_before": "source.before.json", "source_after": "source.after.json", "command": "command.json",
             "outer_exit": "outer-exit.json", "runtime_configuration": "runtime-configuration.json",
             "junit": "junit/TEST-" + TEST_CLASS + ".xml"}
    keys(inputs, tuple(names) + ("sidecar",), "complete settled execution inputs required")
    for name, filename in names.items():
        same(ref(inputs[name])["path"], parent + "/" + filename, "settled receipt path mismatch")
    sidecar_ref = ref(inputs["sidecar"])
    need(beneath(sidecar_ref["path"], parent + "/evidence/detekt/evidence"), "committed sidecar location required")
    sidecar = reader.document(sidecar_ref)
    for field, value in (("schema_version", 1), ("provider_id", "detekt"), ("provider_version", provider_version),
                         ("candidate_only", False), ("run_complete", True), ("publication_committed", True)):
        same(sidecar.get(field), value, "sidecar not committed or provider mismatch")
    context = obj(sidecar.get("context"), "full context missing")
    same("sha256:" + digest(canonical(context)), context_digest, "full context digest mismatch")
    for field, value in (("target", "jvm"), ("source_set", "synthetic"), ("analysis_mode", "light"), ("native_qualification", False),
                         ("execution_backend", "owned_child_native_supervisor_preview1"),
                         ("supervisor_protocol_version", "ktchecker-owned-child-supervisor/preview-1"),
                         ("kotlin_version", "2.4.21"), ("compiler_version", "2.4.21"), ("embedded_kotlin_version", "2.4.10")):
        same(context.get(field), value, "unsupported execution context")
    for field in ("id", "corpus_cohort", "supervisor_target", "jdk_version", "module", "compilation", "scope_note"):
        text_value(context.get(field), "context identity missing")
    for field in ("cohort_plan_digest", "corpus_source_tuple_digest", "corpus_code_image_digest", "corpus_fixture_set_digest",
                  "supervisor_source_digest", "supervisor_binary_digest", "runtime_execution_source_digest", "runtime_code_image_digest"):
        sha(context.get(field), True)
    obj(context.get("library_versions"), "complete library context missing")
    selected, not_run, schedule = expected_schedule(selection)
    integer(context.get("invocation_ceiling"), len(schedule), 40, "invocation ceiling")
    integer(context.get("aggregate_limit_millis"), 1, 1_200_000, "aggregate ceiling")
    integer(context.get("child_limit_millis"), 1, 60_000, "child ceiling")
    source, bounds = completion(reader, inputs, selection, context, source_digest)
    evidence = Evidence(reader, selection)
    for name, filename in (("start_witness", "start-witness.json"), ("cohort_controls", "selected-cohort-controls.json"),
                           ("checked_budget", "checked-cohort-budget.json")):
        same(ref(sidecar[name])["path"], run + "/" + filename, "run metadata boundary mismatch")
    controls = evidence.document(sidecar["cohort_controls"])
    for item in (sidecar, controls):
        for field, value in (("full_corpus_state", "not_run"), ("approved_corpus_rule_count", 16),
                             ("approved_corpus_analysis_count", 145), ("not_run_rule_ids", not_run), ("scope_exclusions", [])):
            same(item.get(field), value, "full not-run authoring scope mismatch")
    for field, value in (("cohort", context["corpus_cohort"]), ("cohort_plan_digest", context["cohort_plan_digest"]),
                         ("selected_rule_ids", selected), ("planned_invocations", len(schedule))):
        same(controls.get(field), value, "selected controls mismatch")
    same(sidecar.get("selected_cohort_rule_count"), len(selected), "selected row count mismatch")
    witness, witness_refs, artifacts = start_bindings(evidence, sidecar, selection, context)
    checked, events = progress(evidence, sidecar, context, schedule, bounds)
    control_links = control_receipts(evidence, checked, selection["selected_rules"], context, provider_version)
    need(timestamp(events[0]["captured_at"]) <= timestamp(witness.get("captured_at")) <= timestamp(events[1]["captured_at"]),
         "START witness not between run start and first invocation")
    counts = {kind: sum(ticket[0] == kind for ticket in schedule) for kind in KINDS}
    budget = evidence.document(sidecar["checked_budget"])
    native = counts[KINDS[0]] + counts[KINDS[1]]
    callbacks = native + counts[KINDS[3]]
    for field, value in {"cohort": context["corpus_cohort"], "reserved_invocations": len(schedule), "checked_invocations": len(schedule),
                        "invocation_ceiling": context["invocation_ceiling"], "aggregate_limit_millis": context["aggregate_limit_millis"],
                        "child_limit_millis": context["child_limit_millis"], "stopped": False, "all_invocations_checked": True,
                        "run_complete": False, "publication_committed": False, "reserved_by_kind": counts, "checked_by_kind": counts,
                        "actual_execution_confirmations": native, "actual_runtime_callbacks": callbacks,
                        "native_launch_ceiling": native, "qualified": False}.items():
        same(budget.get(field), value, "checked candidate-budget mismatch")
    elapsed = integer(budget.get("elapsed_monotonic_millis"), events[-1]["elapsed_monotonic_millis"],
                      context["aggregate_limit_millis"], "budget elapsed bound")
    remaining = integer(budget.get("remaining_millis"), 1, events[-1]["remaining_millis"], "budget remaining bound")
    need(elapsed + remaining <= context["aggregate_limit_millis"], "budget deadline mismatch")
    rows = sidecar.get("rules")
    need(type(rows) is list and len(rows) == 16, "full sidecar row roster required")
    same([obj(row, "invalid sidecar row").get("rule_id") for row in rows], selected + not_run, "sidecar roster/order mismatch")
    projected = []
    # Provider version is used locally without modifying the full hashed context.
    receipt_context = {**context, "provider_version": provider_version}
    for index, row in enumerate(rows):
        need(row.get("qualified") is False and row.get("qualification_evidence") == [], "row qualification promotion")
        gaps = row.get("gaps")
        need(type(gaps) is list and 1 <= len(gaps) <= 64, "limitations must be retained")
        for gap in gaps:
            text_value(gap, "invalid limitation")
        selected_row = index < len(selected)
        for field, value in (("implemented", selected_row), ("tested", selected_row),
                             ("provider_status", "executed_unqualified" if selected_row else "not_run")):
            same(row.get(field), value, "sidecar local claim mismatch")
        if selected_row:
            impl = evidence.follow(row.get("implementation_evidence"))
            same(impl, {**witness_refs, sidecar["start_witness"]["path"]: sidecar["start_witness"],
                        sidecar["cohort_controls"]["path"]: sidecar["cohort_controls"]}, "implementation START boundary mismatch")
            cases = case_results(evidence, row, selection["selected_rules"][index], receipt_context, artifacts,
                                 sidecar["start_witness"], checked, control_links)
        else:
            for field, value in (("attempted", False), ("implementation_evidence", []), ("cases", [])):
                same(row.get(field), value, "not-run row borrowed evidence")
            cases = []
        assessments = {}
        for case in cases:
            assessments.setdefault(case["authored_assessment"], []).append(case["case_id"])
        projected.append({"rule_id": row["rule_id"], "state": "fixture_execution_checked" if selected_row else "not_run",
                          "fixture_execution_checked": selected_row, "qualified": False, "activation_changed": False,
                          "sidecar_local_claims_not_global_ledger_credit": {key: row[key] for key in ("implemented", "tested", "provider_status")},
                          "detector_analyses": len(cases), "cases": cases,
                          "configuration_authorization_denials": 1 if selected_row else 0,
                          "unsupported_target_abstentions": 2 if selected_row else 0,
                          "authored_control_assessments_not_real_world_error_rates": assessments, "limitations": gaps})
    same(evidence.used, set(evidence.bindings), "original input closure incomplete")
    reader.recheck()
    read_pins = [{"path": path, "sha256": value[0]} for path, value in sorted(reader.files.items())]
    return {"schema_version": 1, "report_kind": "detekt_cohort_fixture_execution", "state": "fixture_execution_checked",
            "producer_schema_scope": "Historical sixteen-rule/145-analysis execution-time authoring scope only; not a later expanded/current authoring scope.",
            "qualified": False, "release_qualified": False, "native_qualification": False,
            "coverage_ledger_updated": False, "activation_changed": False,
            "scope": "Retained authored synthetic fixture execution receipts only; not natural-project validity, compatibility or release qualification.",
            "source": {"selection": selection["source_selection"], "execution_receipt": source,
                       "caller_supplied_current_source_digest": current_source_digest,
                       "matches_caller_current_source": source_digest == current_source_digest,
                       "attribution": "Whole-command source is a pinned receipt claim; START subset and retained image hashes are separate, not reproducible source-to-bytecode proof.",
                       "original_source_roots": list(SOURCE_ROOTS), "original_build_files": list(BUILD_FILES),
                       "original_bindings_sha256": digest(canonical(selection["original_inputs"])),
                       "original_bindings_count": len(selection["original_inputs"]), "current_source_fallback": False},
            "provider": {"id": "detekt", "version": provider_version, "artifact_identities": artifacts},
            "context": context, "context_digest": context_digest, "run_evidence": selection_ref,
            "settled_inputs": inputs, "start_witness": sidecar["start_witness"], "cohort_controls": sidecar["cohort_controls"],
            "checked_budget": sidecar["checked_budget"], "progress_reference_list_sha256": digest(canonical(sidecar["progress_evidence"])),
            "evidence_read_set": {"files": len(read_pins), "bytes": reader.total, "sha256": digest(canonical(read_pins)),
                                  "scope": "Pinned explicit retained bytes only; opaque process/SARIF/source/image bodies were hashed, not reinterpreted."},
            "summary": {"fixture_execution_checked_rules": len(selected), "not_run_rules": len(not_run),
                        "full_corpus_state": "not_run", "approved_corpus_rule_count": 16, "approved_corpus_analysis_count": 145,
                        "checked_invocations": len(schedule), "checked_by_kind": counts, "actual_native_confirmations": native,
                        "actual_runtime_callbacks": callbacks, "progress_events": len(events),
                        "shared_cli_probe_count": 1, "shared_cli_probe_is_not_a_per_rule_analysis": True},
            "not_run_rule_ids": not_run, "rules": projected,
            "limitations": ["Hashes bind selected bytes, not an independent signature or trust in producer assertions.",
                            "Authored anticipated FP/FN and policy controls are separate from real-world error rates.",
                            "Configuration-disabled provider execution does not attest an enabled rule descriptor.",
                            "The shared CLI probe, authorization refusals and target abstentions never count as detector analyses.",
                            "No full sixteen-rule/145-analysis run, provider qualification, activation change or global tested credit.",
                            "No full JDK/toolchain closure, reproducible loaded-bytecode proof or portable native-backend qualification."]}


def write_report(reader, output, report):
    output = absolute(output)
    parent, name = output.rsplit("/", 1)
    need(parent != "", "output directory required")
    directory, ancestors = open_directory(parent)
    fd = None
    created = None
    try:
        need(not set(ancestors).intersection(reader.directories.values()), "output overlaps input evidence directories")
        data = json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False, allow_nan=False).encode("utf-8") + b"\n"
        need(len(data) <= MAX_OUTPUT, "report output limit")
        fd = os.open(name, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
        created = identity(os.fstat(fd))
        offset = 0
        while offset < len(data):
            written = os.write(fd, data[offset:])
            need(written > 0, "short report write")
            offset += written
        os.fsync(fd)
        os.lseek(fd, 0, os.SEEK_SET)
        chunks, length = [], 0
        while length <= MAX_OUTPUT:
            block = os.read(fd, min(65_536, MAX_OUTPUT + 1 - length))
            if not block:
                break
            chunks.append(block)
            length += len(block)
        need(b"".join(chunks) == data, "report readback mismatch")
        info = os.stat(name, dir_fd=directory, follow_symlinks=False)
        need(identity(info) == created and info.st_nlink == 1, "report output rebound/aliased")
        reader.recheck()
        parent_check, _ = open_directory(parent)
        try:
            same(identity(os.fstat(parent_check)), identity(os.fstat(directory)), "output parent rebound")
        finally:
            os.close(parent_check)
    except BaseException:
        if created is not None:
            try:
                if identity(os.stat(name, dir_fd=directory, follow_symlinks=False)) == created:
                    os.unlink(name, dir_fd=directory)  # Only this newly created, still-owned output.
            except OSError:
                pass
        raise
    finally:
        if fd is not None:
            os.close(fd)
        os.close(directory)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("input-root", "run-evidence", "expect-run-evidence-sha256", "expect-source-digest", "expect-context-digest",
                 "expect-provider-version", "current-source-digest", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args(argv)
    reader = None
    try:
        reader = ReadSet(args.input_root)
        report = create_report(reader, args.run_evidence, args.expect_run_evidence_sha256, args.expect_source_digest,
                               args.expect_context_digest, args.expect_provider_version, args.current_source_digest)
        write_report(reader, args.output, report)
    except (ReportError, OSError, KeyError, TypeError, ValueError, OverflowError, RecursionError) as error:
        message = str(error) if isinstance(error, ReportError) else type(error).__name__
        print("fixture-execution report rejected: " + message, file=sys.stderr)
        return 2
    finally:
        if reader is not None:
            reader.close()
    print("Fixture-execution report written; unqualified, activation and global ledger unchanged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
