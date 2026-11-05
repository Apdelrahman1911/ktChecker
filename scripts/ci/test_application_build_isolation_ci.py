"""Authored source assertions only, not Gradle execution or build/archive evidence.

These checks read the reviewed Kotlin DSL and gate Python source and reject
selected mutations. They do not evaluate Kotlin DSL, resolve classpaths, build
a JAR, run a compiler provider/native process, or prove UP-TO-DATE/FROM-CACHE
behavior. The separate
application_build_isolation_ci.py gate is responsible for actual build-data checks.
"""

import ast
from pathlib import Path
import re
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
TAIL_MARKER = "// Persistent application bridge: JDK-only isolated sources, not the historical finite fixture."
FAMILIES = (
    "dev/ktchecker/workerprotocol/ApplicationWire",
    "dev/ktchecker/workerprotocol/CompilerJobWire",
    "dev/ktchecker/compilerworker/live/CompilerApplicationMain",
    "dev/ktchecker/compilerworker/live/PinnedKotlinCompilerAdapter",
)
RAW_WORKER_INPUT = (
    '    inputs.files(applicationWorker.output).withPropertyName("unfilteredWorkerOutput")\n'
    '        .withPathSensitivity(PathSensitivity.RELATIVE)'
)
RAW_WIRE_INPUT = (
    '    inputs.files(applicationWireBridge).withPropertyName("unfilteredWireArchive")\n'
    '        .withPathSensitivity(PathSensitivity.RELATIVE)'
)


class ApplicationBuildIsolationSourceTests(unittest.TestCase):
    def documents(self):
        return {
            "root": (REPO_ROOT / "build.gradle.kts").read_text(encoding="utf-8"),
            "protocol": (REPO_ROOT / "modules/worker-protocol/build.gradle.kts").read_text(encoding="utf-8"),
            "compiler": (REPO_ROOT / "modules/provider-compiler/build.gradle.kts").read_text(encoding="utf-8"),
        }

    def assert_source_contract(self, documents):
        root, protocol, compiler = (documents[key] for key in ("root", "protocol", "compiler"))
        # Fixed source contract, deliberately not a general Kotlin DSL parser.
        self.assertEqual(1, root.count('if (name != "worker-protocol") {'))
        exclusion = root.split('if (name != "worker-protocol") {', 1)[1]
        self.assertTrue(exclusion.startswith('\n        apply(plugin = "org.jetbrains.kotlin.jvm")\n'))
        self.assertIn('        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")', exclusion)
        self.assertIn('        dependencies {\n            add("implementation",', exclusion)
        self.assertEqual(1, protocol.count('plugins { `java-library` }'))
        self.assertIn('java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }', protocol)
        self.assertNotIn('kotlin(', protocol)
        self.assertNotIn('implementation(', protocol)
        self.assertNotIn('runtimeOnly(', protocol)
        self.assertEqual(1, compiler.count(TAIL_MARKER))
        tail = compiler.split(TAIL_MARKER, 1)[1]
        bridge = re.findall(r'^val applicationWireBridge by configurations\.creating \{\n([\s\S]*?)^\}',
                            tail, re.MULTILINE)
        self.assertEqual(1, len(bridge))
        self.assertEqual([
            '    isCanBeConsumed = false',
            '    isCanBeResolved = true',
            '    isTransitive = false',
        ], bridge[0].splitlines())
        for statement in (
            'val applicationWorker = sourceSets.create("worker")',
            'applicationWorker.compileClasspath = files(applicationWireBridge)',
            'applicationWorker.runtimeClasspath = files(applicationWorker.output, applicationWireBridge)',
            '    add(applicationWireBridge.name, project(":worker-protocol"))',
        ):
            self.assertEqual(1, tail.count(statement), statement)
        families = re.findall(r'^val applicationBridgeClasses = setOf\(\n([\s\S]*?)^\)',
                              tail, re.MULTILINE)
        self.assertEqual(1, len(families))
        self.assertEqual([f'    "{family}",' for family in FAMILIES], families[0].splitlines())
        self.assertIn(r'path == "$it.class" || (path.startsWith("$it\$") && path.endsWith(".class"))', tail)
        self.assertEqual(1, tail.count('tasks.register<Jar>("compilerApplicationWorkerJar") {'))
        jar = tail.split('tasks.register<Jar>("compilerApplicationWorkerJar") {', 1)[1]
        for statement in (
            '    dependsOn(applicationWorker.classesTaskName, applicationWireBridge)',
            '    archiveFileName.set("ktchecker-compiler-application-preview-1.jar")',
            '    destinationDirectory.set(layout.buildDirectory.dir("worker-application"))',
            '    duplicatesStrategy = DuplicatesStrategy.FAIL',
            '    manifest { attributes["Main-Class"] = "dev.ktchecker.compilerworker.live.CompilerApplicationMain" }',
            '    val wireClasses = providers.provider { zipTree(applicationWireBridge.singleFile) }',
            RAW_WORKER_INPUT, RAW_WIRE_INPUT,
            '    from(applicationWorker.output) { include { it.isDirectory || applicationBridgeClass(it.path) } }',
            '    from(wireClasses) { include { it.isDirectory || applicationBridgeClass(it.path) } }',
            '        applicationWorker.output.asFileTree.visit {',
            '                require(applicationBridgeClass(relativePath.pathString)) { "Unallowlisted worker output: ${relativePath.pathString}" }',
            '        wireClasses.get().visit {',
            '            if (!isDirectory && relativePath.pathString != "META-INF/MANIFEST.MF") {',
            '                require(applicationBridgeClass(relativePath.pathString)) { "Unallowlisted wire output: ${relativePath.pathString}" }',
            '        require(names.size == names.toSet().size) { "Duplicate bridge class" }',
            '        require(applicationBridgeClasses.all { "$it.class" in names }) { "Missing reviewed bridge entry class" }',
        ):
            self.assertEqual(1, jar.count(statement), statement)
        self.assertEqual(1, jar.count('    doFirst {'))
        for forbidden in ('sourceSets["main"]', 'sourceSets["test"]', 'compilerToolchain',
                          'runtimeClasspath', 'outputs.cacheIf', 'onlyIf {', 'JavaExec', 'commandLine('):
            self.assertNotIn(forbidden, jar)

    def assert_mutations_fail(self, mutations):
        original = self.documents()
        self.assert_source_contract(original)
        for document_key, old, new in mutations:
            with self.subTest(document=document_key, original=old, replacement=new):
                changed = dict(original)
                self.assertEqual(1, changed[document_key].count(old), "Mutation anchor is ambiguous")
                changed[document_key] = changed[document_key].replace(old, new, 1)
                with self.assertRaises(AssertionError):
                    self.assert_source_contract(changed)

    def assert_gate_stacktrace_contract(self, source):
        tree = ast.parse(source)
        assignments = [
            node for node in ast.walk(tree)
            if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name)
                    and target.value.id == "self" and target.attr == "command" for target in node.targets)
        ]
        self.assertEqual(1, len(assignments), "Common Gradle argv anchor is ambiguous")
        value = assignments[0].value
        self.assertIsInstance(value, ast.BinOp)
        self.assertIsInstance(value.op, ast.Add)
        self.assertIsInstance(value.right, ast.List)
        flags = [node.value for node in value.right.elts if isinstance(node, ast.Constant)]
        self.assertEqual(1, flags.count("--stacktrace"), "Common Gradle argv must request stacktrace")

    def test_gate_common_argv_captures_wrapped_diagnostics_and_rejects_stacktrace_removal(self):
        source = (REPO_ROOT / "scripts/ci/application_build_isolation_ci.py").read_text(encoding="utf-8")
        self.assert_gate_stacktrace_contract(source)
        self.assertEqual(1, source.count('"--stacktrace", '))
        changed = source.replace('"--stacktrace", ', '', 1)
        with self.assertRaises(AssertionError):
            self.assert_gate_stacktrace_contract(changed)

    def test_reviewed_jdk_only_source_contract(self):
        self.assert_source_contract(self.documents())

    def test_source_controls_reject_shared_kotlin_and_transitive_worker_classpaths(self):
        self.assert_mutations_fail((
            ("root", 'if (name != "worker-protocol") {', 'if (true) {'),
            ("protocol", 'plugins { `java-library` }', 'plugins { `java-library`; kotlin("jvm") }'),
            ("protocol", 'dependencies {', 'dependencies {\n    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")'),
            ("compiler", '    isTransitive = false', '    isTransitive = true'),
            ("compiler", 'applicationWorker.compileClasspath = files(applicationWireBridge)',
             'applicationWorker.compileClasspath = configurations["workerCompileClasspath"]'),
            ("compiler", 'applicationWorker.runtimeClasspath = files(applicationWorker.output, applicationWireBridge)',
             'applicationWorker.runtimeClasspath = files(applicationWorker.output, sourceSets["main"].runtimeClasspath)'),
            ("compiler", '    add(applicationWireBridge.name, project(":worker-protocol"))',
             '    add(applicationWireBridge.name, project(":provider-runtime"))'),
        ))

    def test_source_controls_reject_filtered_or_missing_raw_inputs(self):
        self.assert_mutations_fail((
            ("compiler", RAW_WORKER_INPUT, ''),
            ("compiler", RAW_WIRE_INPUT, ''),
            ("compiler", RAW_WORKER_INPUT,
             '    inputs.files(applicationWorker.output.asFileTree.matching { include("**/*.class") })'),
            ("compiler", RAW_WIRE_INPUT, '    inputs.files(wireClasses)'),
            ("compiler", 'withPropertyName("unfilteredWorkerOutput")', 'withPropertyName("filteredWorkerOutput")'),
            ("compiler", 'withPropertyName("unfilteredWireArchive")', 'withPropertyName("filteredWireArchive")'),
        ))

    def test_source_controls_reject_silent_drop_missing_classes_and_duplicate_entries(self):
        self.assert_mutations_fail((
            ("compiler", '    "dev/ktchecker/workerprotocol/CompilerJobWire",', ''),
            ("compiler", r'path.startsWith("$it\$")', r'path.startsWith("$it")'),
            ("compiler", '        applicationWorker.output.asFileTree.visit {',
             '        applicationWorker.output.asFileTree.matching { include("**/*.class") }.visit {'),
            ("compiler", '        wireClasses.get().visit {',
             '        wireClasses.get().matching { include("**/*.class") }.visit {'),
            ("compiler", '        require(names.size == names.toSet().size) { "Duplicate bridge class" }', ''),
            ("compiler", '        require(applicationBridgeClasses.all { "$it.class" in names }) { "Missing reviewed bridge entry class" }', ''),
            ("compiler", '    duplicatesStrategy = DuplicatesStrategy.FAIL\n    manifest {',
             '    duplicatesStrategy = DuplicatesStrategy.EXCLUDE\n    manifest {'),
            ("compiler", 'attributes["Main-Class"] = "dev.ktchecker.compilerworker.live.CompilerApplicationMain"',
             'attributes["Main-Class"] = "dev.ktchecker.compiler.CompilerFixtureRunner"'),
        ))


if __name__ == "__main__":
    unittest.main()
