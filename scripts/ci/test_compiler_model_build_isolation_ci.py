"""Scoped model build source assertions and fabricated JAR-envelope controls.

These controls never evaluate Kotlin/Groovy DSL, resolve Gradle dependencies,
compile Java, run Gradle or execute a provider/native/compiler application. The
archive controls fabricate only eight-byte class headers; they are not valid
compiled Java or actual model bridge artifacts. Importing the gate exposes pure
archive helpers but does not instantiate BuildGate or launch commands.

The fixed source contract deliberately preserves the original two build-file
preimages and the M0 task tail. It is not a general Kotlin/Groovy parser or a
claim about an independently executed complete integrated suite. The separate
compiler_model_build_isolation_ci.py gate performs actual build-data inspection.
"""

import ast
import hashlib
from pathlib import Path
import re
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

from compiler_model_build_isolation_ci import (
    ALLOWED_TASKS, APPLICATION_TASK, GateFailure, MAIN_CLASS, MAX_ARCHIVE_BYTES,
    MODEL_BRIDGE_CLASSES, MODEL_WIRE_CLASSES, MODEL_WORKER_CLASSES,
    M0_APPLICATION_TASK, M0_MAIN_CLASS, M0_WORKER_CLASSES, ORIGINAL_WIRE_CLASSES,
    REPORT_TASK, archive_data, class_allowed,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
PROTOCOL_MARKER = "// Additive model DTO profile. The original main JAR and its raw inventory stay unchanged."
PROVIDER_MARKER = "// Additive model application profile. The persistent M0 block below remains byte-for-byte unchanged."
M0_MARKER = "// Persistent application bridge: JDK-only isolated sources, not the historical finite fixture."
HOST_DEPENDENCY = '    implementation(project(path = ":worker-protocol", configuration = "modelWireElements"))\n'
MODEL_ZIP_IMPORT = 'import java.util.zip.ZipFile\n'
PROTOCOL_PREIMAGE_SHA256 = "909c7750dd4f8fb367b24cfbc87d38062b746e04b902defecb9fe37ae5b02a29"
PROVIDER_PREIMAGE_SHA256 = "a76951ef47fbba99009a600f7d74804ed479ccad42b28302a98b0cb42375d48e"
ROOT_BUILD_SHA256 = "fffb175bd0e39003f6750b3a48fa71255a3be7f52401070618764d412c2f3e7b"
RAW_INPUTS = (
    '    inputs.files(applicationModelWorker.output).withPropertyName("unfilteredModelWorkerOutput")\n'
    '        .withPathSensitivity(PathSensitivity.RELATIVE)',
    '    inputs.files(applicationModelOriginalWireBridge).withPropertyName("unfilteredOriginalWireArchive")\n'
    '        .withPathSensitivity(PathSensitivity.RELATIVE)',
    '    inputs.files(applicationModelWireBridge).withPropertyName("unfilteredModelWireArchive")\n'
    '        .withPathSensitivity(PathSensitivity.RELATIVE)',
)
STAGES = (
    "00-baseline", "01-product-model-worker-up-to-date", "02-product-model-worker-extra-output",
    "03-product-model-worker-repair", "04-product-model-wire-up-to-date", "05-product-model-wire-extra-output",
    "06-product-model-wire-repair", "07-product-original-wire-up-to-date", "08-product-original-wire-extra-output",
    "09-product-original-wire-repair", "10-product-final-up-to-date", "11-test-cache-seed",
    "12-test-cache-model-worker-from-cache", "13-test-cache-model-worker-extra-output",
    "14-test-cache-model-wire-from-cache", "15-test-cache-model-wire-extra-output",
    "16-test-cache-original-wire-from-cache", "17-test-cache-original-wire-extra-output",
    "18-test-cache-final-from-cache",
)


def sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class CompilerModelBuildIsolationSourceTests(unittest.TestCase):
    def documents(self):
        return {key: (REPO_ROOT / path).read_text(encoding="utf-8") for key, path in (
            ("root", "build.gradle.kts"),
            ("protocol", "modules/worker-protocol/build.gradle.kts"),
            ("provider", "modules/provider-compiler/build.gradle.kts"),
            ("runner", "scripts/ci/compiler_model_build_isolation_ci.py"),
            ("init", "scripts/ci/compiler_model_build_isolation.init.gradle"),
        )}

    def assert_source_contract(self, docs):
        protocol, provider = docs["protocol"], docs["provider"]
        self.assertEqual(ROOT_BUILD_SHA256, sha256(docs["root"]), "Root build conventions changed")
        self.assertEqual(1, protocol.count(PROTOCOL_MARKER))
        protocol_before, protocol_model = protocol.split(PROTOCOL_MARKER, 1)
        self.assertEqual(PROTOCOL_PREIMAGE_SHA256, sha256(protocol_before.removesuffix("\n")))
        self.assertEqual(1, provider.count(PROVIDER_MARKER))
        self.assertEqual(1, provider.count(M0_MARKER))
        before, rest = provider.split(PROVIDER_MARKER, 1)
        model, m0 = rest.split(M0_MARKER, 1)
        self.assertEqual(1, before.count(HOST_DEPENDENCY), "Host model dependency must be explicit and unique")
        self.assertEqual(1, before.count(MODEL_ZIP_IMPORT), "Only the declared model ZipFile import is added")
        inherited = before.replace(HOST_DEPENDENCY, "", 1).replace(MODEL_ZIP_IMPORT, "", 1) + M0_MARKER + m0
        self.assertEqual(PROVIDER_PREIMAGE_SHA256, sha256(inherited), "Original M0/main/fixture wiring changed")
        for forbidden in ('kotlin(', 'implementation(', 'runtimeOnly('):
            self.assertNotIn(forbidden, protocol)
        for statement in (
            'val compilerModelWire = sourceSets.create("modelWire")',
            'val compilerModelWireTest = sourceSets.create("modelWireTest")',
            'val compilerModelOriginalWireJar = tasks.named<Jar>("jar")',
            'compilerModelWire.compileClasspath = files(compilerModelOriginalWireJar.flatMap { it.archiveFile })',
            'compilerModelWire.runtimeClasspath = files(compilerModelWire.output, compilerModelOriginalWireJar.flatMap { it.archiveFile })',
            'val compilerModelWireTestLibraries = configurations.create("compilerModelWireTestLibraries") {\n'
            '    isCanBeConsumed = false; isCanBeResolved = true\n}',
            'compilerModelWireTest.compileClasspath = files(\n'
            '    compilerModelWire.output, compilerModelOriginalWireJar.flatMap { it.archiveFile }, compilerModelWireTestLibraries,\n)',
            'compilerModelWireTest.runtimeClasspath = files(compilerModelWireTest.output, compilerModelWireTest.compileClasspath)',
            'val compilerModelWireControls = tasks.register<Test>("modelWireTest") {',
            '    useJUnitPlatform()',
            '    testClassesDirs = compilerModelWireTest.output.classesDirs',
            '    classpath = compilerModelWireTest.runtimeClasspath',
            '    dependsOn(compilerModelWire.classesTaskName, compilerModelWireTest.classesTaskName)',
            'tasks.named("test") { dependsOn(compilerModelWireControls) }',
            'tasks.named("check") { dependsOn(compilerModelWireControls) }',
            'val compilerModelWireFamily = "dev/ktchecker/workerprotocol/CompilerModelWire"',
            'val compilerModelWireJar = tasks.register<Jar>("modelWireJar") {',
            '    inputs.files(compilerModelWire.output).withPropertyName("unfilteredModelWireOutput")\n'
            '        .withPathSensitivity(PathSensitivity.RELATIVE)',
            '    from(compilerModelWire.output) { include { it.isDirectory || compilerModelWireClass(it.path) } }',
            '        compilerModelWire.output.asFileTree.visit {',
            '                require(compilerModelWireClass(relativePath.pathString)) {',
            '        require(names.size == names.toSet().size) { "Duplicate model wire class" }',
            '        require("$compilerModelWireFamily.class" in names) { "Missing reviewed model wire entry class" }',
            'val modelWireElements = configurations.create("modelWireElements") {\n'
            '    isCanBeConsumed = true; isCanBeResolved = false; isTransitive = false\n}',
            'artifacts.add(modelWireElements.name, compilerModelWireJar)',
        ):
            self.assertEqual(1, protocol_model.count(statement), statement)
        self.assertIn('path.indexOf(\'/\', compilerModelWireFamily.length) == -1', protocol_model)
        self.assertIn('path.indexOf(\'\\\\\', compilerModelWireFamily.length) == -1', protocol_model)
        for prefix in ('compilerModelWireTestLibraries',):
            for coordinate in ('org.junit.jupiter:junit-jupiter-api:5.11.4',
                               'org.junit.jupiter:junit-jupiter-engine:5.11.4',
                               'org.junit.platform:junit-platform-launcher:1.11.4'):
                self.assertEqual(1, protocol_model.count(f'dependencies.add({prefix}.name, "{coordinate}")'))
        for statement in (
            'dependencies.add("testImplementation", project(":review-controller"))',
            'tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processTestResources") {\n'
            '    from(rootProject.file("data")) {\n'
            '        include("rules-core.json", "rules-libraries.json", "rules-platform.json", "rules-ecosystem.json")\n'
            '        into("model-live-control-catalog")\n'
            '        duplicatesStrategy = DuplicatesStrategy.FAIL\n    }\n}',
            'val applicationModelWorker = sourceSets.create("modelWorker")',
            'val applicationModelWorkerTest = sourceSets.create("modelWorkerTest")',
            'val applicationModelOriginalWireBridge = configurations.create("applicationModelOriginalWireBridge") {\n'
            '    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false\n}',
            'val applicationModelWireBridge = configurations.create("applicationModelWireBridge") {\n'
            '    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false\n}',
            'val applicationModelWorkerTestLibraries = configurations.create("applicationModelWorkerTestLibraries") {\n'
            '    isCanBeConsumed = false; isCanBeResolved = true\n}',
            'dependencies.add(applicationModelOriginalWireBridge.name, project(":worker-protocol"))',
            'dependencies.add(applicationModelWireBridge.name, dependencies.project(mapOf("path" to ":worker-protocol", "configuration" to "modelWireElements")))',
            'applicationModelWorker.compileClasspath = files(applicationModelOriginalWireBridge, applicationModelWireBridge)',
            'applicationModelWorker.runtimeClasspath = files(applicationModelWorker.output, applicationModelWorker.compileClasspath)',
            'applicationModelWorkerTest.compileClasspath = files(\n'
            '    applicationModelWorker.output, applicationModelOriginalWireBridge, applicationModelWireBridge, applicationModelWorkerTestLibraries,\n)',
            'applicationModelWorkerTest.runtimeClasspath = files(applicationModelWorkerTest.output, applicationModelWorkerTest.compileClasspath)',
            'kotlin.sourceSets.named(applicationModelWorker.name) { kotlin.setSrcDirs(emptyList<String>()) }',
            'kotlin.sourceSets.named(applicationModelWorkerTest.name) { kotlin.setSrcDirs(emptyList<String>()) }',
            'tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileModelWorkerKotlin") {\n'
            '    enabled = false\n    libraries.setFrom(applicationModelWorker.compileClasspath)\n}',
            'tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileModelWorkerTestKotlin") {\n'
            '    enabled = false\n    libraries.setFrom(applicationModelWorkerTest.compileClasspath)\n}',
            'tasks.named<org.gradle.api.tasks.compile.JavaCompile>(applicationModelWorker.compileJavaTaskName) {\n'
            '    classpath = applicationModelWorker.compileClasspath\n}',
            'tasks.named<org.gradle.api.tasks.compile.JavaCompile>(applicationModelWorkerTest.compileJavaTaskName) {\n'
            '    classpath = applicationModelWorkerTest.compileClasspath\n}',
            'val applicationModelWorkerControls = tasks.register<Test>("modelWorkerTest") {',
            '    useJUnitPlatform()',
            '    testClassesDirs = applicationModelWorkerTest.output.classesDirs',
            '    classpath = applicationModelWorkerTest.runtimeClasspath',
            '    dependsOn(applicationModelWorker.classesTaskName, applicationModelWorkerTest.classesTaskName)',
            'tasks.named("test") { dependsOn(applicationModelWorkerControls) }',
            'tasks.named("check") { dependsOn(applicationModelWorkerControls) }',
            'val compilerModelOriginalWireFamilies = setOf("dev/ktchecker/workerprotocol/ApplicationWire", "dev/ktchecker/workerprotocol/CompilerJobWire")',
            'val compilerModelIncludedOriginalWireFamilies = setOf("dev/ktchecker/workerprotocol/ApplicationWire")',
            'val compilerModelWireFamilies = setOf("dev/ktchecker/workerprotocol/CompilerModelWire")',
            'val compilerModelWorkerFamilies = setOf(\n'
            '    "dev/ktchecker/compilerworker/live/CompilerModelApplicationMain",\n'
            '    "dev/ktchecker/compilerworker/live/PinnedKotlinModelCompilerAdapter",\n)',
            'val compilerModelBridgeFamilies = compilerModelIncludedOriginalWireFamilies + compilerModelWireFamilies + compilerModelWorkerFamilies',
            'path.indexOf(\'/\', family.length) == -1 && path.indexOf(\'\\\\\', family.length) == -1',
            '            require(name.isNotEmpty() && !name.startsWith("/") && \'\\\\\' !in name &&\n'
            '                name.removeSuffix("/").split(\'/\').all { it.isNotEmpty() && it != "." && it != ".." }) {',
            '            require(seen.add(name)) { "Duplicate $label archive entry: $name" }',
            '            if (!entry.isDirectory && name != "META-INF/MANIFEST.MF") {',
            '                require(compilerModelBridgeClass(name, families)) { "Unallowlisted $label output: $name" }',
            '    require("META-INF/MANIFEST.MF" in seen) { "Missing $label manifest" }',
            '    require(families.all { "${it}.class" in names }) { "Missing reviewed $label entry class" }',
        ):
            self.assertEqual(1, model.count(statement), statement)
        self.assertEqual(1, model.splitlines().count('    ZipFile(archive).use { zip ->'),
                         "Model archive validation opener must be a standalone exact line")
        for coordinate in ('org.junit.jupiter:junit-jupiter-api:5.11.4',
                           'org.junit.jupiter:junit-jupiter-engine:5.11.4',
                           'org.junit.platform:junit-platform-launcher:1.11.4'):
            self.assertEqual(1, model.count(f'dependencies.add(applicationModelWorkerTestLibraries.name, "{coordinate}")'))
        self.assertEqual(1, model.count('tasks.register<Jar>("compilerModelApplicationWorkerJar") {'))
        jar = model.split('tasks.register<Jar>("compilerModelApplicationWorkerJar") {', 1)[1]
        for statement in RAW_INPUTS + (
            '    dependsOn(applicationModelWorker.classesTaskName, applicationModelOriginalWireBridge, applicationModelWireBridge)',
            '    archiveFileName.set("ktchecker-compiler-model-application-preview-1.jar")',
            '    destinationDirectory.set(layout.buildDirectory.dir("model-worker-application"))',
            '    manifest { attributes["Main-Class"] = "dev.ktchecker.compilerworker.live.CompilerModelApplicationMain" }',
            '    duplicatesStrategy = DuplicatesStrategy.FAIL',
            '    val originalWireClasses = providers.provider { zipTree(applicationModelOriginalWireBridge.singleFile) }',
            '    val modelWireClasses = providers.provider { zipTree(applicationModelWireBridge.singleFile) }',
            '    from(applicationModelWorker.output) { include { it.isDirectory || compilerModelBridgeClass(it.path, compilerModelWorkerFamilies) } }',
            '    from(originalWireClasses) { include { it.isDirectory || compilerModelBridgeClass(it.path, compilerModelIncludedOriginalWireFamilies) } }',
            '    from(modelWireClasses) { include { it.isDirectory || compilerModelBridgeClass(it.path, compilerModelWireFamilies) } }',
            '        applicationModelWorker.output.asFileTree.visit {',
            '                require(compilerModelBridgeClass(relativePath.pathString, compilerModelWorkerFamilies)) {',
            '        require(compilerModelWorkerFamilies.all { "${it}.class" in names }) { "Missing reviewed model worker entry class" }',
            '        val originalNames = compilerModelArchiveClasses(\n'
            '            applicationModelOriginalWireBridge.singleFile, compilerModelOriginalWireFamilies, "original wire",\n        )',
            '        names += originalNames.filter { compilerModelBridgeClass(it, compilerModelIncludedOriginalWireFamilies) }',
            '        names += compilerModelArchiveClasses(applicationModelWireBridge.singleFile, compilerModelWireFamilies, "model wire")',
            '        require(names.size == names.toSet().size) { "Duplicate model bridge class" }',
            '        require(compilerModelBridgeFamilies.all { "${it}.class" in names }) { "Missing reviewed model bridge entry class" }',
        ):
            self.assertEqual(1, jar.count(statement), statement)
        for forbidden in ('sourceSets["main"]', 'sourceSets["test"]', 'compilerToolchain',
                          'runtimeClasspath', 'outputs.cacheIf', 'onlyIf {', 'JavaExec', 'commandLine(', 'matching {'):
            self.assertNotIn(forbidden, jar)
        self.assertNotIn('extendsFrom', protocol_model + model)
        self.assertNotIn('associateWith', model)

    def assert_mutations_fail(self, mutations):
        original = self.documents()
        self.assert_source_contract(original)
        for key, old, new in mutations:
            with self.subTest(document=key, old=old, replacement=new):
                docs = dict(original)
                self.assertEqual(1, docs[key].count(old), "Ambiguous mutation anchor")
                docs[key] = docs[key].replace(old, new, 1)
                with self.assertRaises(AssertionError):
                    self.assert_source_contract(docs)

    def test_fixed_additive_source_contract_preserves_complete_preimages(self):
        self.assert_source_contract(self.documents())

    def test_rejects_transitive_main_kotlin_and_test_classpath_leaks(self):
        self.assert_mutations_fail((
            ("provider", HOST_DEPENDENCY, ''),
            ("provider", MODEL_ZIP_IMPORT, ''),
            ("provider", 'dependencies.add(applicationModelWireBridge.name, dependencies.project(mapOf("path" to ":worker-protocol", "configuration" to "modelWireElements")))', 'dependencies.add(applicationModelWireBridge.name, project(path = ":worker-protocol", configuration = "modelWireElements"))'),
            ("provider", 'val applicationModelWireBridge = configurations.create("applicationModelWireBridge") {\n'
             '    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false\n}',
             'val applicationModelWireBridge = configurations.create("applicationModelWireBridge") {\n'
             '    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = true\n}'),
            ("provider", 'applicationModelWorker.compileClasspath = files(applicationModelOriginalWireBridge, applicationModelWireBridge)',
             'applicationModelWorker.compileClasspath = sourceSets["main"].runtimeClasspath'),
            ("provider", 'applicationModelWorker.runtimeClasspath = files(applicationModelWorker.output, applicationModelWorker.compileClasspath)',
             'applicationModelWorker.runtimeClasspath = sourceSets["test"].runtimeClasspath'),
            ("provider", 'kotlin.sourceSets.named(applicationModelWorker.name) { kotlin.setSrcDirs(emptyList<String>()) }',
             'kotlin.sourceSets.named(applicationModelWorker.name) { kotlin.srcDir("src/main/kotlin") }'),
            ("provider", 'tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileModelWorkerKotlin") {\n    enabled = false',
             'tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileModelWorkerKotlin") {\n    enabled = true'),
            ("provider", '    libraries.setFrom(applicationModelWorker.compileClasspath)',
             '    libraries.setFrom(sourceSets["main"].runtimeClasspath)'),
            ("protocol", 'compilerModelWire.compileClasspath = files(compilerModelOriginalWireJar.flatMap { it.archiveFile })',
             'compilerModelWire.compileClasspath = configurations["testRuntimeClasspath"]'),
            ("protocol", '    isCanBeConsumed = true; isCanBeResolved = false; isTransitive = false',
             '    isCanBeConsumed = true; isCanBeResolved = false; isTransitive = true'),
        ))

    def test_rejects_missing_or_filtered_complete_raw_inputs(self):
        self.assert_mutations_fail(tuple(("provider", raw, '') for raw in RAW_INPUTS) + (
            ("provider", RAW_INPUTS[0], '    inputs.files(applicationModelWorker.output.asFileTree.matching { include("**/*.class") })'),
            ("provider", RAW_INPUTS[1], '    inputs.files(originalWireClasses)'),
            ("provider", RAW_INPUTS[2], '    inputs.files(modelWireClasses)'),
            ("protocol", '    inputs.files(compilerModelWire.output).withPropertyName("unfilteredModelWireOutput")\n'
             '        .withPathSensitivity(PathSensitivity.RELATIVE)', ''),
        ))

    def test_rejects_peer_leaks_and_incomplete_original_archive_validation(self):
        self.assert_mutations_fail((
            ("provider", 'val compilerModelIncludedOriginalWireFamilies = setOf("dev/ktchecker/workerprotocol/ApplicationWire")',
             'val compilerModelIncludedOriginalWireFamilies = compilerModelOriginalWireFamilies'),
            ("provider", '            applicationModelOriginalWireBridge.singleFile, compilerModelOriginalWireFamilies, "original wire",',
             '            applicationModelOriginalWireBridge.singleFile, compilerModelIncludedOriginalWireFamilies, "original wire",'),
            ("provider", '    ZipFile(archive).use { zip ->',
             '    ZipFile(archive).use { zip -> return emptyList()'),
            ("provider", '            require(seen.add(name)) { "Duplicate $label archive entry: $name" }', ''),
            ("provider", '                require(compilerModelBridgeClass(name, families)) { "Unallowlisted $label output: $name" }', ''),
            ("provider", '    require(families.all { "${it}.class" in names }) { "Missing reviewed $label entry class" }', ''),
            ("provider", '        require(names.size == names.toSet().size) { "Duplicate model bridge class" }', ''),
            ("provider", '        require(compilerModelBridgeFamilies.all { "${it}.class" in names }) { "Missing reviewed model bridge entry class" }', ''),
            ("provider", 'manifest { attributes["Main-Class"] = "dev.ktchecker.compilerworker.live.CompilerModelApplicationMain" }',
             'manifest { attributes["Main-Class"] = "dev.ktchecker.compilerworker.live.CompilerApplicationMain" }'),
        ))

    def assert_gate_contract(self, source, init):
        tree = ast.parse(source)
        run_class = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'BuildGate')
        initializer = next(n for n in run_class.body if isinstance(n, ast.FunctionDef) and n.name == '__init__')
        commands = [n for n in ast.walk(initializer) if isinstance(n, ast.Assign) and any(
            isinstance(t, ast.Attribute) and isinstance(t.value, ast.Name) and t.value.id == 'self' and t.attr == 'command'
            for t in n.targets)]
        self.assertEqual(1, len(commands))
        flags = [n.value for n in commands[0].value.right.elts if isinstance(n, ast.Constant)]
        for flag in ('--no-daemon', '--console=plain', '--stacktrace', '--max-workers=1', '--no-parallel',
                     '--no-configuration-cache', '--offline', '--gradle-user-home', '--init-script'):
            self.assertEqual(1, flags.count(flag), flag)
        run = next(n for n in run_class.body if isinstance(n, ast.FunctionDef) and n.name == 'run')
        stages = sorted([n for n in ast.walk(run) if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute)
                         and n.func.attr in ('step', 'negative')], key=lambda n:n.lineno)
        self.assertEqual(STAGES, tuple(n.args[0].value for n in stages))
        negative_calls = tuple((n.args[0].value, n.args[1].value, n.args[2].value)
                               for n in stages if n.func.attr == 'negative')
        self.assertEqual((
            ('02-product-model-worker-extra-output', 'model worker', False),
            ('05-product-model-wire-extra-output', 'model wire', False),
            ('08-product-original-wire-extra-output', 'original wire', False),
            ('13-test-cache-model-worker-extra-output', 'model worker', True),
            ('15-test-cache-model-wire-extra-output', 'model wire', True),
            ('17-test-cache-original-wire-extra-output', 'original wire', True),
        ), negative_calls)
        constants = {n.targets[0].id: n.value for n in tree.body if isinstance(n, ast.Assign)
                     and len(n.targets)==1 and isinstance(n.targets[0], ast.Name)}
        names = {key: node.value for key,node in constants.items() if isinstance(node, ast.Constant)}
        actual_tasks = {n.value if isinstance(n, ast.Constant) else names[n.id]
                        for n in constants['ALLOWED_TASKS'].elts}
        self.assertEqual(ALLOWED_TASKS, actual_tasks)
        self.assertEqual(MODEL_BRIDGE_CLASSES, ORIGINAL_WIRE_CLASSES[:1] + MODEL_WIRE_CLASSES + MODEL_WORKER_CLASSES)
        self.assertEqual('ORIGINAL_WIRE_CLASSES[:1] + MODEL_WIRE_CLASSES + MODEL_WORKER_CLASSES',
                         ast.unparse(constants['MODEL_BRIDGE_CLASSES']))
        for statement in (
            'require(result["state"] == "completed", "Gradle command did not complete within harness limits")',
            'require(set(result["tasks"]) <= ALLOWED_TASKS, "Observed task output escaped the finite gate")',
            'require(result["return_code"] != 0 and\n                        result["tasks"].get(APPLICATION_TASK) == "FAILED",',
            'diagnostic = "Unallowlisted " + negative_kind + " output: " + EXTRA_ENTRY',
            'require(diagnostic in output, "Negative control lacked the exact extra-output diagnostic")',
            'require(mutation is not None and mutation["path"].is_file() and\n'
            '                        digest_file(mutation["path"]) == mutation["mutated_digest"]',
            'set(document["task_graph"]) - {APPLICATION_TASK, REPORT_TASK}',
            '"m0_application": archive_data(m0_jar, ORIGINAL_WIRE_CLASSES + M0_WORKER_CLASSES, M0_MAIN_CLASS)',
            '"model_application": archive_data(model_jar, MODEL_BRIDGE_CLASSES, MAIN_CLASS)',
            'require(document["model_worker_kotlin"]["enabled"] is False,',
            'original_archive_bytes_restored=True, m0_inventory_unchanged=True',
        ):
            self.assertEqual(1, source.count(statement), statement)
        for statement in (
            "def allowedTasks = [", "gradle.taskGraph.whenReady { graph ->",
            "if (configuration.name == 'compilerToolchain') {", "configuration.incoming.beforeResolve {",
            "Model build isolation must not resolve compilerToolchain",
            "settings.buildCache.remote.enabled = false",
            "task.outputs.cacheIf('Private model build gate cache exercise only') { true }",
            "reportTask.dependsOn(modelApplicationJar, oldApplicationJar, protocolJar, modelWireJar)",
            "test_only_model_jar_cache_override: testCacheEnabled", "m0_jar_cache_override: false",
        ):
            self.assertEqual(1, init.count(statement), statement)
        self.assertEqual(1, len(re.findall(r'\.\s*outputs\s*\.\s*cacheIf\b', init)),
                         "Only the reviewed model task receiver may have the single cache override")
        for task in ALLOWED_TASKS - {REPORT_TASK}:
            self.assertEqual(1, init.count("'" + task + "'"), task)
        for forbidden in ('JavaExec', 'commandLine(', 'compilerFixtures', 'compilerWarmGeneration', ':provider-compiler:test'):
            self.assertNotIn(forbidden, init)

    def test_finite_serial_gate_requires_exact_diagnostics_and_m0_checks(self):
        docs=self.documents()
        self.assert_gate_contract(docs['runner'],docs['init'])

    def test_gate_contract_rejects_flags_task_graph_and_receipt_check_removal(self):
        docs=self.documents()
        self.assert_gate_contract(docs['runner'],docs['init'])
        changes=(
            ('runner','"--stacktrace", ',''),
            ('runner','"--offline", ',''),
            ('runner','"--max-workers=1", ',''),
            ('runner','require(diagnostic in output, "Negative control lacked the exact extra-output diagnostic")',
             'require(True, "Negative control lacked the exact extra-output diagnostic")'),
            ('runner','result["tasks"].get(APPLICATION_TASK) == "FAILED"','result["return_code"] != 0'),
            ('runner','set(document["task_graph"]) - {APPLICATION_TASK, REPORT_TASK}',
             'set(document["task_graph"]) - {APPLICATION_TASK, M0_APPLICATION_TASK, REPORT_TASK}'),
            ('runner','"original wire", False)','"model wire", False)'),
            ('init',"task.outputs.cacheIf('Private model build gate cache exercise only') { true }",
             "task.outputs.cacheIf('Private model build gate cache exercise only') { true }; oldApplicationJar.configure { it.outputs.cacheIf { true } }"),
        )
        for key,old,new in changes:
            with self.subTest(document=key,original=old,replacement=new):
                changed=dict(docs)
                self.assertEqual(1, changed[key].count(old))
                changed[key]=changed[key].replace(old,new,1)
                with self.assertRaises(AssertionError):
                    self.assert_gate_contract(changed['runner'],changed['init'])


class CompilerModelArchiveEnvelopeTests(unittest.TestCase):
    # Eight header bytes alone are deliberately not a complete or executable class.
    CLASS_HEADER = b'\xca\xfe\xba\xbe' + struct.pack('>HH', 0, 65)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='ktc-model-archive-envelope-')
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'fabricated-envelope.jar'

    def make_archive(self, families=MODEL_BRIDGE_CLASSES, main=MAIN_CLASS, extras=(), manifest=None, major=65, compression=zipfile.ZIP_STORED):
        lines=['Manifest-Version: 1.0']
        if main is not None:
            lines.append('Main-Class: ' + main)
        body=manifest if manifest is not None else '\r\n'.join(lines)+'\r\n\r\n'
        entries=[('META-INF/MANIFEST.MF',body.encode('utf-8'))]
        header=b'\xca\xfe\xba\xbe'+struct.pack('>HH',0,major)
        entries += [(name+'.class',header) for name in families]
        entries += list(extras)
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            with zipfile.ZipFile(self.path,'w') as archive:
                for name,data in entries:
                    info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0))
                    # ZipInfo replaces backslashes on Windows and truncates NUL.
                    # Host-independent hostile fixtures must retain literal bytes.
                    info.filename=name
                    info.compress_type=compression
                    archive.writestr(info,data)
        return self.path

    def test_four_separate_fabricated_archive_envelopes(self):
        for families,main in ((ORIGINAL_WIRE_CLASSES,None),(MODEL_WIRE_CLASSES,None),
                              (ORIGINAL_WIRE_CLASSES+M0_WORKER_CLASSES,M0_MAIN_CLASS),
                              (MODEL_BRIDGE_CLASSES,MAIN_CLASS)):
            with self.subTest(families=families,main=main):
                result=archive_data(self.make_archive(families,main),families,main)
                self.assertEqual(sorted(name+'.class' for name in families),result['class_entries'])
                self.assertEqual(65,result['class_file_major_version'])

    def test_known_original_peer_is_required_original_but_forbidden_in_model(self):
        path=self.make_archive(extras=((ORIGINAL_WIRE_CLASSES[1]+'.class',self.CLASS_HEADER),))
        with self.assertRaises(GateFailure): archive_data(path,MODEL_BRIDGE_CLASSES,MAIN_CLASS)
        path=self.make_archive(ORIGINAL_WIRE_CLASSES[:1],None)
        with self.assertRaises(GateFailure): archive_data(path,ORIGINAL_WIRE_CLASSES,None)

    def test_unknown_resources_and_cross_role_families_reject(self):
        for name in ('unknown.txt','META-INF/services/foreign.Service','META-INF/extra.kotlin_module',
                     M0_WORKER_CLASSES[0]+'.class',M0_WORKER_CLASSES[1]+'.class'):
            with self.subTest(name=name):
                path=self.make_archive(extras=((name,self.CLASS_HEADER),))
                with self.assertRaises(GateFailure): archive_data(path,MODEL_BRIDGE_CLASSES,MAIN_CLASS)
        path=self.make_archive(ORIGINAL_WIRE_CLASSES,None,extras=((MODEL_WIRE_CLASSES[0]+'.class',self.CLASS_HEADER),))
        with self.assertRaises(GateFailure): archive_data(path,ORIGINAL_WIRE_CLASSES,None)
        path=self.make_archive(MODEL_WIRE_CLASSES,None,extras=((ORIGINAL_WIRE_CLASSES[0]+'.class',self.CLASS_HEADER),))
        with self.assertRaises(GateFailure): archive_data(path,MODEL_WIRE_CLASSES,None)

    def test_missing_each_required_model_top_level_rejects(self):
        for family in MODEL_BRIDGE_CLASSES:
            with self.subTest(missing=family):
                path=self.make_archive(tuple(n for n in MODEL_BRIDGE_CLASSES if n!=family))
                with self.assertRaises(GateFailure): archive_data(path,MODEL_BRIDGE_CLASSES,MAIN_CLASS)

    def test_duplicate_manifest_or_class_entries_reject(self):
        for name,data in (('META-INF/MANIFEST.MF',b'Manifest-Version: 1.0\r\n\r\n'),
                          (MODEL_WIRE_CLASSES[0]+'.class',self.CLASS_HEADER)):
            with self.subTest(duplicate=name):
                with self.assertRaises(GateFailure):
                    archive_data(self.make_archive(extras=((name,data),)),MODEL_BRIDGE_CLASSES,MAIN_CLASS)

    def test_family_prefix_and_nested_subdirectory_do_not_expand_allowlist(self):
        family=MODEL_WIRE_CLASSES[0]
        self.assertTrue(class_allowed(family+'$Record.class',MODEL_WIRE_CLASSES))
        for name in (family+'Peer.class',family+'$/foreign.class',family+'$inner/foreign.class',
                     family+'$inner\\foreign.class',family+'.class/foreign.class'):
            with self.subTest(name=name):
                self.assertFalse(class_allowed(name,MODEL_WIRE_CLASSES))
                with self.assertRaises(GateFailure):
                    archive_data(self.make_archive(extras=((name,self.CLASS_HEADER),)),MODEL_BRIDGE_CLASSES,MAIN_CLASS)

    def test_unsafe_zip_names_reject_even_for_directories(self):
        for name in ('/absolute/', '../outside/', 'x/../outside/', './alias/', 'x//alias/',
                     'x\\alias/', 'x\x00alias/', 'C:/absolute/', 'C:relative/'):
            with self.subTest(name=name):
                with self.assertRaises(GateFailure):
                    archive_data(self.make_archive(extras=((name,b''),)),MODEL_BRIDGE_CLASSES,MAIN_CLASS)

    def test_hostile_zip_fixture_preserves_literal_central_directory_names(self):
        names=('x\\alias/', 'x\x00alias/', 'C:/absolute/', 'C:relative/')
        path=self.make_archive(extras=tuple((name,b'') for name in names))
        with zipfile.ZipFile(path) as archive:
            self.assertEqual(list(names),[entry.orig_filename for entry in archive.infolist()[-len(names):]])

    def test_windows_zip_reader_normalization_does_not_admit_a_raw_directory_alias(self):
        path=self.make_archive(extras=(('x\\alias/',b''),))
        original_info=zipfile.ZipInfo

        class WindowsNormalizedZipInfo(original_info):
            """Simulated Windows decoding only; never patch global OS/path APIs."""
            def __init__(self,*args,**kwargs):
                super().__init__(*args,**kwargs)
                self.filename=self.filename.replace('\\','/')

        with patch.object(zipfile,'ZipInfo',WindowsNormalizedZipInfo):
            with zipfile.ZipFile(path) as archive:
                entry=archive.infolist()[-1]
                self.assertEqual('x\\alias/',entry.orig_filename)
                self.assertEqual('x/alias/',entry.filename)
                self.assertTrue(entry.is_dir())
            with self.assertRaises(GateFailure):
                archive_data(path,MODEL_BRIDGE_CLASSES,MAIN_CLASS)

    def test_safe_directory_and_nested_class_entries_remain_accepted(self):
        nested=MODEL_WIRE_CLASSES[0]+'$Nested.class'
        path=self.make_archive(extras=(('dev/',b''),('META-INF/',b''),(nested,self.CLASS_HEADER)))
        result=archive_data(path,MODEL_BRIDGE_CLASSES,MAIN_CLASS)
        self.assertEqual(sorted([name+'.class' for name in MODEL_BRIDGE_CLASSES]+[nested]),result['class_entries'])

    def test_wrong_manifest_main_or_origin_expansion_rejects(self):
        for manifest in ('Manifest-Version: 1.0\r\nMain-Class: '+M0_MAIN_CLASS+'\r\n\r\n',
                         'Manifest-Version: 1.0\r\nMain-Class: '+MAIN_CLASS+'\r\nClass-Path: foreign.jar\r\n\r\n',
                         'Manifest-Version: 1.0\r\nMain-Class: '+MAIN_CLASS+'\r\nMain-Class: '+MAIN_CLASS+'\r\n\r\n'):
            with self.subTest(manifest=manifest):
                with self.assertRaises(GateFailure):
                    archive_data(self.make_archive(manifest=manifest),MODEL_BRIDGE_CLASSES,MAIN_CLASS)

    def test_non_jdk21_or_invalid_class_header_rejects(self):
        for major in (52,61,64,66):
            with self.subTest(major=major):
                with self.assertRaises(GateFailure):
                    archive_data(self.make_archive(major=major),MODEL_BRIDGE_CLASSES,MAIN_CLASS)
        path=self.make_archive(tuple(n for n in MODEL_BRIDGE_CLASSES if n!=MODEL_WIRE_CLASSES[0]),
                               extras=((MODEL_WIRE_CLASSES[0]+'.class',b'notclass'),))
        with self.assertRaises(GateFailure): archive_data(path,MODEL_BRIDGE_CLASSES,MAIN_CLASS)

    def test_expanded_archive_envelope_bound_is_not_ignored(self):
        name=MODEL_WIRE_CLASSES[0]+'$Bound.class'
        # Bounded positive: compression alone is not a rejection reason. These
        # padded pseudo-class headers are not compiled/executable Java bytes.
        path=self.make_archive(extras=((name,self.CLASS_HEADER+b'\x00'*4096),),
                               compression=zipfile.ZIP_DEFLATED)
        self.assertLess(path.stat().st_size,MAX_ARCHIVE_BYTES)
        with zipfile.ZipFile(path) as archive:
            self.assertLessEqual(sum(entry.file_size for entry in archive.infolist()),MAX_ARCHIVE_BYTES)
            self.assertTrue(all(entry.compress_type==zipfile.ZIP_DEFLATED for entry in archive.infolist()))
        self.assertIn(name,archive_data(path,MODEL_BRIDGE_CLASSES,MAIN_CLASS)['class_entries'])

        # Raw ZIP stays below the raw-size guard, while expanded inventory is
        # above it. R1 used ZIP_STORED, which masked this branch with raw size.
        # The exact reason makes removing the expanded guard fail this control;
        # that sensitivity is a source deduction until independently executed.
        path=self.make_archive(extras=((name,self.CLASS_HEADER+b'\x00'*MAX_ARCHIVE_BYTES),),
                               compression=zipfile.ZIP_DEFLATED)
        self.assertLess(path.stat().st_size,MAX_ARCHIVE_BYTES)
        with zipfile.ZipFile(path) as archive:
            self.assertGreater(sum(entry.file_size for entry in archive.infolist()),MAX_ARCHIVE_BYTES)
            self.assertTrue(all(entry.compress_type==zipfile.ZIP_DEFLATED for entry in archive.infolist()))
        with self.assertRaises(GateFailure) as failure:
            archive_data(path,MODEL_BRIDGE_CLASSES,MAIN_CLASS)
        self.assertEqual('Expanded JAR exceeds gate size bound',str(failure.exception))


if __name__ == '__main__':
    unittest.main()
