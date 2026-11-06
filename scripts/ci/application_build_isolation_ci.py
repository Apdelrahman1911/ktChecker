"""Actual Gradle build-data gate in an owner-prepared disposable clone.

No provider/native fixture, application worker, installed CLI, or compiler job is
executed. Source assertion tests are separate and are not run by this gate.
Jar cache reuse is exercised with an explicitly labelled private init override;
it does not establish production cache enablement. Each invocation is serial.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import zipfile

from ci_common import (digest_file, parse_gradle_tasks, read_bounded, run_logged,
                       source_identity, utc_now, wrapper_argv, write_json)


APPLICATION_TASK = ":provider-compiler:compilerApplicationWorkerJar"
REPORT_TASK = ":provider-compiler:applicationBuildIsolationReport"
INIT_FILE = Path(__file__).with_name("application_build_isolation.init.gradle")
WIRE_CLASSES = ("dev/ktchecker/workerprotocol/ApplicationWire",
                "dev/ktchecker/workerprotocol/CompilerJobWire")
WORKER_CLASSES = ("dev/ktchecker/compilerworker/live/CompilerApplicationMain",
                  "dev/ktchecker/compilerworker/live/PinnedKotlinCompilerAdapter")
MAIN_CLASS = "dev.ktchecker.compilerworker.live.CompilerApplicationMain"
EXTRA_ENTRY = "gate-forbidden-worker-resource.txt"
EXTRA_BYTES = b"ktChecker private build-input negative control\n"
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
ALLOWED_TASKS = {
    ":worker-protocol:compileJava", ":worker-protocol:processResources",
    ":worker-protocol:classes", ":worker-protocol:jar",
    ":provider-compiler:checkKotlinGradlePluginConfigurationErrors",
    ":provider-compiler:compileWorkerKotlin", ":provider-compiler:compileWorkerJava",
    ":provider-compiler:processWorkerResources", ":provider-compiler:workerClasses",
    APPLICATION_TASK, REPORT_TASK,
}


class GateFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise GateFailure(message)


def within(path: Path, parent: Path) -> bool:
    return path == parent or parent in path.parents


def class_allowed(name: str, families: tuple[str, ...]) -> bool:
    return any(name == family + ".class" or
               (name.startswith(family + "$") and name.endswith(".class"))
               for family in families)


def archive_data(path: Path, families: tuple[str, ...], main_class: str | None) -> dict:
    require(path.is_file() and not path.is_symlink(), "Expected a local regular JAR")
    require(path.stat().st_size <= MAX_ARCHIVE_BYTES, "JAR exceeds gate size bound")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        require(len(names) == len(set(names)), "Duplicate JAR entries")
        require(sum(entry.file_size for entry in entries) <= MAX_ARCHIVE_BYTES,
                "Expanded JAR exceeds gate size bound")
        require("META-INF/MANIFEST.MF" in names, "JAR manifest is absent")
        classes = []
        for entry in entries:
            name = entry.filename
            require(not name.startswith("/") and ".." not in name.split("/") and "\\" not in name,
                    "Unsafe JAR entry path")
            if entry.is_dir() or name == "META-INF/MANIFEST.MF":
                continue
            require(class_allowed(name, families), "Unallowlisted packaged file: " + name)
            with archive.open(entry) as handle:
                header = handle.read(8)
            require(len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe",
                    "Packaged class lacks a class-file header: " + name)
            require(struct.unpack(">H", header[6:8])[0] == 65,
                    "Packaged class is not Java21 bytecode: " + name)
            classes.append(name)
        require(all(family + ".class" in classes for family in families),
                "Required reviewed entry class is absent")
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        # Unfold standard manifest continuation lines before checking attributes.
        unfolded = manifest.replace("\r\n", "\n").replace("\n ", "")
        headers = {}
        for line in unfolded.split("\n\n", 1)[0].splitlines():
            require(": " in line, "Malformed manifest header")
            key, value = line.split(": ", 1)
            key = key.lower()
            require(key not in headers, "Duplicate manifest header")
            headers[key] = value
        require("class-path" not in headers, "Manifest adds an external Class-Path")
        require(headers.get("main-class") == main_class, "Unexpected manifest Main-Class")
    return {"digest": digest_file(path), "bytes": path.stat().st_size,
            "class_entries": sorted(classes), "entry_count": len(entries),
            "main_class": main_class, "class_file_major_version": 65}


class BuildGate:
    def __init__(self, run_root: Path, reference_root: Path, timeout: int):
        self.root = run_root.resolve(strict=True)
        self.reference = reference_root.resolve(strict=True)
        require(self.root.name.startswith("ktc-application-build-isolation-run-"),
                "Use an explicitly named ktc-application-build-isolation-run-* directory")
        require(not within(self.root, self.reference) and not within(self.reference, self.root),
                "Disposable run root must be separate from the reference workspace")
        self.checkout = self.root / "checkout"
        self.evidence = self.root / "evidence"
        self.user_home = self.root / "gradle-user-home"
        self.cache = self.evidence / "local-build-cache"
        self.timeout = timeout
        require(self.checkout.is_dir() and not self.checkout.is_symlink() and
                self.checkout.resolve() == self.checkout,
                "Owner-prepared link-free checkout directory is required")
        require((self.checkout / ".git").is_dir() and not (self.checkout / ".git").is_symlink(),
                "Use a disposable clone with its own .git directory")
        for module in ("worker-protocol", "provider-compiler"):
            module_dir = self.checkout / "modules" / module
            require(module_dir.resolve() == module_dir, "Module path must stay in the disposable clone")
            require(not (module_dir / "build").exists(), "Build outputs must be absent at gate start")
        require(self.user_home.is_dir() and not self.user_home.is_symlink() and
                self.user_home.resolve() == self.user_home,
                "Owner-prepared private Gradle user home is required")
        # Seed by independent copies, never links into the common Gradle cache.
        for path in self.user_home.rglob("*"):
            require(within(path.resolve(), self.user_home), "Gradle home contains an external symlink")
            if path.is_file():
                require(path.stat().st_nlink == 1, "Gradle home must not hard-link common cache files")
        require(not self.evidence.exists(), "Evidence directory already exists; refusing to overwrite")
        self.evidence.mkdir()
        self.cache.mkdir()
        self.environment = dict(os.environ, GRADLE_USER_HOME=str(self.user_home))
        self.command = wrapper_argv(self.checkout) + [
            "--no-daemon", "--console=plain", "--stacktrace", "--max-workers=1", "--no-parallel",
            "--no-configuration-cache", "--offline", "--gradle-user-home", str(self.user_home),
            "--init-script", str(INIT_FILE.resolve()),
        ]
        self.receipt = {
            "schema": "ktchecker.application-build-isolation/v1",
            "started_at": utc_now(), "state": "started", "steps": [],
            "scope": "Actual Gradle build/classpath/archive input controls only",
            "compiler_provider_execution": False, "native_execution": False,
            "runtime_or_native_qualification": False,
            "source_assertions_executed_by_this_gate": False,
            "production_cache_enablement_assessed": False,
            "test_cache_description": "Private init-script cacheIf override on application JAR only",
            "raw_logs_uploaded": False,
            "cleanup_description": "Disposable build-output restoration only; no native descendant-closure claim",
            "source": source_identity(self.checkout),
            "runner_digest": digest_file(Path(__file__)), "init_digest": digest_file(INIT_FILE),
            "ci_common_digest": digest_file(Path(__file__).with_name("ci_common.py")),
        }
        self.initial_artifacts = None
        self.active_mutation = None
        self.producer_tasks = ()
        self.save()

    def save(self) -> None:
        write_json(self.evidence / "receipt.json", self.receipt)

    def local_output(self, name: str, module: str) -> Path:
        path = Path(name)
        expected = self.checkout / "modules" / module / "build"
        require(path.is_absolute() and path.resolve() == path and within(path, expected),
                "Reported output escaped its disposable module build directory")
        return path

    def inspect_closure(self, document: dict, cache_override: bool) -> dict:
        require(document.get("schema") == "ktchecker.application-build-isolation-closure/v1",
                "Wrong closure report schema")
        require(document.get("test_only_jar_cache_override") is cache_override and
                document.get("production_cache_enablement_assessed") is False,
                "Cache instrumentation was not explicitly labelled")
        graph = set(document["task_graph"])
        require(graph <= ALLOWED_TASKS and {APPLICATION_TASK, REPORT_TASK} <= graph,
                "Closure report task graph escaped the finite build gate")
        require(document["protocol_has_kotlin_plugin"] is False,
                "JDK-only protocol unexpectedly applies the Kotlin plugin")
        protocol = document["protocol_main"]
        worker = document["worker"]
        protocol_jar = self.local_output(document["protocol_jar"], "worker-protocol")
        application_jar = self.local_output(document["application_jar"], "provider-compiler")
        for data, module in ((protocol, "worker-protocol"), (worker, "provider-compiler")):
            for path in data["output"] + data["class_directories"]:
                self.local_output(path, module)
            require(not data["kotlin_sources"], "JDK-only bridge source set contains Kotlin sources")
        require(protocol["compile_classpath"] == [] and
                document["protocol_resolved_compile_files"] == [] and
                document["protocol_resolved_runtime_files"] == [] and
                document["protocol_java_compile"]["classpath"] == [],
                "Protocol has an external production compile/runtime dependency")
        require(set(protocol["runtime_classpath"]) == set(protocol["output"]),
                "Protocol runtime classpath is not exactly its own output")
        wire = document["wire_bridge"]
        require(wire["transitive"] is False and wire["files"] == [str(protocol_jar)],
                "Worker wire bridge is not one nontransitive protocol JAR")
        require(len(wire["artifacts"]) == 1 and
                wire["artifacts"][0]["file"] == str(protocol_jar) and
                wire["artifacts"][0]["project_path"] == ":worker-protocol",
                "Resolved bridge artifact is not exclusively the protocol project")
        require(worker["compile_classpath"] == [str(protocol_jar)],
                "Worker SourceSet compile classpath is not wire-only")
        require(set(worker["runtime_classpath"]) == set(worker["output"]) | {str(protocol_jar)},
                "Worker runtime classpath is not exactly own output plus wire")
        # KGP may add its own worker output directory to JavaCompile. Such a self
        # directory is allowed; every external compile entry must be the wire JAR.
        for task_files in (document["worker_java_compile"]["classpath"],
                           document["worker_kotlin_libraries"]):
            require(str(protocol_jar) in task_files and
                    set(task_files) <= set(worker["output"]) | {str(protocol_jar)},
                    "Actual worker compile task includes a host/Kotlin/third-party library")
        require(document["protocol_java_compile"]["language_version"] == 21 and
                document["worker_java_compile"]["language_version"] == 21,
                "Bridge Java compilation did not select JDK21")
        artifacts = {
            "protocol": archive_data(protocol_jar, WIRE_CLASSES, None),
            "application": archive_data(application_jar, WIRE_CLASSES + WORKER_CLASSES, MAIN_CLASS),
        }
        if self.initial_artifacts is not None:
            require(all(artifacts[key]["digest"] == self.initial_artifacts[key]["digest"]
                        for key in artifacts), "Restored bridge archive bytes changed")
        return artifacts

    def step(self, name: str, *, cache_override: bool = False,
             outcomes: dict[str, tuple[str, ...]] | None = None,
             negative_kind: str | None = None) -> dict:
        require(not (self.evidence / (name + ".json")).exists(), "Stage receipt already exists")
        closure_path = self.evidence / (name + ".closure.json")
        command = self.command + [
            "--build-cache" if cache_override else "--no-build-cache",
            "-PapplicationBuildIsolationReport=" + str(closure_path),
            "-PapplicationBuildIsolationLocalCache=" + str(self.cache),
            "-PapplicationBuildIsolationEnableTestCache=" + str(cache_override).lower(),
        ]
        if negative_kind:
            for task in self.producer_tasks:
                command += ["--exclude-task", task]
        command += [REPORT_TASK]
        mutation_before = None
        if self.active_mutation is not None:
            mutation_before = {key: str(value) if isinstance(value, Path) else value
                               for key, value in self.active_mutation.items()}
        target_path = getattr(self, "application_jar", self.checkout / "modules/provider-compiler/build"
                              / "worker-application/ktchecker-compiler-application-preview-1.jar")
        output_existed = target_path.exists()
        result, output, _ = run_logged(command, cwd=self.checkout,
                                      log_base=self.evidence / name, timeout=self.timeout,
                                      env=self.environment)
        result["name"] = name
        result["execution_state"] = result["state"]
        result["tasks"] = parse_gradle_tasks(output)
        result["test_only_jar_cache_override"] = cache_override
        result["expected_application_outcomes"] = list((outcomes or {}).get(APPLICATION_TASK, ()))
        result["negative_control"] = negative_kind
        result["input_mutation_before_invocation"] = mutation_before
        result["application_output_existed_before_invocation"] = output_existed
        result["excluded_producer_tasks"] = list(self.producer_tasks) if negative_kind else []
        errors = []
        try:
            require(result["state"] == "completed", "Gradle command did not complete within harness limits")
            require(set(result["tasks"]) <= ALLOWED_TASKS, "Observed task output escaped the finite gate")
            if negative_kind:
                require(result["return_code"] != 0 and
                        result["tasks"].get(APPLICATION_TASK) == "FAILED",
                        "Negative control did not fail the application JAR task")
                diagnostic = "Unallowlisted " + negative_kind + " output: " + EXTRA_ENTRY
                require(diagnostic in output, "Negative control lacked the exact extra-output diagnostic")
                mutation = self.active_mutation
                require(mutation is not None and mutation["path"].is_file() and
                        digest_file(mutation["path"]) == mutation["mutated_digest"],
                        "Producer reran or changed the injected raw output")
                result["mutation"] = {key: str(value) if isinstance(value, Path) else value
                                      for key, value in mutation.items()}
                result["matching_diagnostic_observed"] = True
            else:
                require(result["return_code"] == 0, "Positive build control failed")
                for task, expected in (outcomes or {}).items():
                    require(result["tasks"].get(task) in expected,
                            "Required task outcome absent: " + task + " " + str(expected))
                require(result["tasks"].get(REPORT_TASK) == "EXECUTED", "Closure report did not execute")
                document = json.loads(read_bounded(closure_path))
                result["artifacts"] = self.inspect_closure(document, cache_override)
                result["closure_digest"] = digest_file(closure_path)
                result["closure"] = document
        except (GateFailure, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
            errors.append(str(error))
        result["errors"] = errors
        result["state"] = "passed" if not errors else "failed"
        write_json(self.evidence / (name + ".json"), result)
        self.receipt["steps"].append({"name": name, "state": result["state"],
                                      "receipt": name + ".json",
                                      "receipt_digest": digest_file(self.evidence / (name + ".json"))})
        self.save()
        require(not errors, name + ": " + "; ".join(errors))
        return result

    def inject(self, kind: str) -> None:
        require(self.active_mutation is None, "Previous negative input has not been restored")
        require(kind in ("worker", "wire"), "Unknown negative-control kind")
        temporary = self.evidence / ("negative-" + kind + "-input.tmp")
        require(not temporary.exists(), "Negative input temporary already exists")
        try:
            if kind == "worker":
                path = self.worker_directory / EXTRA_ENTRY
                require(not path.exists(), "Negative worker fixture would overwrite an output")
                temporary.write_bytes(EXTRA_BYTES)
                original_digest = None
            else:
                path = self.protocol_jar
                original_digest = digest_file(path)
                require(original_digest == self.initial_artifacts["protocol"]["digest"],
                        "Wire input is not the restored original archive")
                temporary.write_bytes(self.original_wire_bytes)
                with zipfile.ZipFile(temporary, "a") as archive:
                    require(EXTRA_ENTRY not in archive.namelist(), "Negative wire fixture already exists")
                    entry = zipfile.ZipInfo(EXTRA_ENTRY, date_time=(1980, 1, 1, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_STORED
                    archive.writestr(entry, EXTRA_BYTES)
            mutation = {"kind": kind, "path": path, "entry_name": EXTRA_ENTRY,
                        "original_digest": original_digest, "mutated_digest": digest_file(temporary)}
            temporary.replace(path)
            self.active_mutation = mutation
        finally:
            temporary.unlink(missing_ok=True)

    def restore_input(self) -> None:
        if self.active_mutation is None:
            return
        mutation = self.active_mutation
        if mutation["kind"] == "worker":
            require(mutation["path"].is_file() and
                    digest_file(mutation["path"]) == mutation["mutated_digest"],
                    "Refusing to remove an unexpectedly changed worker fixture")
            mutation["path"].unlink()
        else:
            mutation["path"].write_bytes(self.original_wire_bytes)
            require(digest_file(mutation["path"]) == self.initial_artifacts["protocol"]["digest"],
                    "Wire archive restoration was not byte-exact")
        self.active_mutation = None

    def remove_application_output(self) -> None:
        # Removing only this private output forces a real cache lookup. It never
        # clears the common Gradle cache or any installed CLI/distribution tree.
        if self.application_jar.exists():
            require(self.application_jar.is_file() and not self.application_jar.is_symlink(),
                    "Application output is not a regular disposable file")
            self.application_jar.unlink()

    def negative(self, name: str, kind: str, cache_override: bool) -> None:
        self.inject(kind)
        try:
            if cache_override:
                self.remove_application_output()
            self.step(name, cache_override=cache_override, negative_kind=kind)
        finally:
            self.restore_input()

    def run(self) -> int:
        target = lambda outcome: {APPLICATION_TASK: (outcome,)}
        try:
            baseline = self.step("00-baseline", outcomes={
                APPLICATION_TASK: ("EXECUTED",), ":worker-protocol:compileJava": ("EXECUTED",),
                ":provider-compiler:compileWorkerJava": ("EXECUTED",),
                ":provider-compiler:compileWorkerKotlin": ("NO-SOURCE",),
            })
            document = baseline["closure"]
            self.initial_artifacts = baseline["artifacts"]
            self.protocol_jar = self.local_output(document["protocol_jar"], "worker-protocol")
            self.application_jar = self.local_output(document["application_jar"], "provider-compiler")
            candidates = [self.local_output(path, "provider-compiler")
                          for path in document["worker"]["class_directories"]]
            self.worker_directory = next((path for path in candidates
                                          if path.is_dir() and any(path.rglob("*.class"))), None)
            require(self.worker_directory is not None, "No actual worker class output directory")
            self.original_wire_bytes = self.protocol_jar.read_bytes()
            (self.evidence / "original-wire.jar.private-backup").write_bytes(self.original_wire_bytes)
            self.producer_tasks = tuple(sorted(set(document["task_graph"]) - {APPLICATION_TASK, REPORT_TASK}))

            self.step("01-product-worker-up-to-date", outcomes=target("UP-TO-DATE"))
            self.negative("02-product-worker-extra-output", "worker", False)
            self.step("03-product-repair", outcomes={APPLICATION_TASK: ("EXECUTED", "UP-TO-DATE")})
            self.step("04-product-wire-up-to-date", outcomes=target("UP-TO-DATE"))
            self.negative("05-product-wire-extra-output", "wire", False)
            self.step("06-product-repair", outcomes={APPLICATION_TASK: ("EXECUTED", "UP-TO-DATE")})
            self.step("07-product-final-up-to-date", outcomes=target("UP-TO-DATE"))

            self.remove_application_output()
            self.step("08-test-cache-seed", cache_override=True, outcomes=target("EXECUTED"))
            self.remove_application_output()
            self.step("09-test-cache-worker-from-cache", cache_override=True, outcomes=target("FROM-CACHE"))
            self.negative("10-test-cache-worker-extra-output", "worker", True)
            self.remove_application_output()
            self.step("11-test-cache-wire-from-cache", cache_override=True, outcomes=target("FROM-CACHE"))
            self.negative("12-test-cache-wire-extra-output", "wire", True)
            self.remove_application_output()
            self.step("13-test-cache-final-from-cache", cache_override=True, outcomes=target("FROM-CACHE"))
            final_source = source_identity(self.checkout)
            self.receipt["final_source"] = final_source
            require(final_source["commit"] == self.receipt["source"]["commit"] and
                    final_source["working_tree_digest"] == self.receipt["source"]["working_tree_digest"],
                    "Source bytes changed during the build gate")
            self.receipt.update(state="passed", finished_at=utc_now(),
                                original_archive_bytes_restored=True)
        except (GateFailure, OSError, ValueError, KeyError, TypeError,
                subprocess.SubprocessError, zipfile.BadZipFile) as error:
            self.receipt.update(state="failed", finished_at=utc_now(), error=str(error))
        finally:
            try:
                self.restore_input()
            except (GateFailure, OSError) as error:
                self.receipt.update(state="failed", restoration_error=str(error))
            self.save()
        return 0 if self.receipt["state"] == "passed" else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-root", required=True, type=Path)
    parser.add_argument("--reference-root", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=900, help="Per serial Gradle invocation, seconds")
    arguments = parser.parse_args()
    try:
        require(1 <= arguments.timeout <= 1800, "Per-step timeout must be 1..1800 seconds")
        gate = BuildGate(arguments.run_root, arguments.reference_root, arguments.timeout)
        code = gate.run()
    except (GateFailure, OSError, ValueError, subprocess.SubprocessError) as error:
        print(json.dumps({"state": "failed", "precondition_error": str(error)}))
        return 1
    print(json.dumps({"state": gate.receipt["state"], "receipt": str(gate.evidence / "receipt.json"),
                      "compiler_provider_execution": False, "native_execution": False,
                      "production_cache_enablement_assessed": False}))
    return code


if __name__ == "__main__":
    sys.exit(main())
