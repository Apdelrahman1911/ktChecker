"""Authored Gradle input/reuse wiring controls, not Gradle or native execution."""

from pathlib import Path
import re
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]


class LiveRuntimeBuildWiringTests(unittest.TestCase):
    gate = "ownedLiveIntegrationOptIn"
    probe = "ownedLiveProbePath"
    cache_reason = "Enabled live integration uses unmodeled external probe/toolchain inputs"

    def document(self):
        return (REPO_ROOT / "modules/provider-runtime/build.gradle.kts").read_text(encoding="utf-8")

    def assert_wiring(self, document):
        # Fixed authored source reading only, not a Kotlin DSL parser/evaluator.
        # Empty defaults keep disabled/unsupported hosts off; exact "1" alone
        # enables real execution; all other values disable only the JUnit opt-in,
        # not this complete Test task. A path or cached XML is not that execution.
        for variable, environment in ((self.gate, "KTC_RUN_OWNED_LIVE_INTEGRATION"),
                                      (self.probe, "KTC_OWNED_LIVE_PROBE")):
            expected = f'val {variable} = providers.environmentVariable("{environment}").orElse("")'
            self.assertEqual([expected], re.findall(r"^val " + re.escape(variable) + r"\b[^\n]*$",
                                                   document, re.MULTILINE))
            self.assertEqual(1, document.count(f'providers.environmentVariable("{environment}")'))
        blocks = re.findall(r'^tasks\.named<Test>\("test"\) \{\n([\s\S]*?)^\}\n?',
                            document, re.MULTILINE)
        self.assertEqual(1, len(blocks), "Only the existing complete runtime Test is extended")
        self.assertEqual([
            f'    inputs.property("{self.gate}", {self.gate})',
            f'    inputs.property("{self.probe}", {self.probe})',
            f'    outputs.upToDateWhen {{ {self.gate}.get() != "1" }}',
            f'    outputs.doNotCacheIf("{self.cache_reason}") {{',
            f'        {self.gate}.get() == "1"',
            '    }',
        ], blocks[0].splitlines())

    def assert_mutations_fail(self, mutations):
        document = self.document()
        marker = "val ownedLiveIntegrationOptIn = "
        self.assertEqual(1, document.count(marker))
        prefix, tail = document.split(marker, 1)
        tail = marker + tail  # Mutate only the new wiring, never a v1/v2 builder.
        for old, new in mutations:
            with self.subTest(mutation=old, replacement=new):
                changed = tail.replace(old, new, 1)
                self.assertNotEqual(tail, changed)
                with self.assertRaises(AssertionError):
                    self.assert_wiring(prefix + changed)

    def test_exact_environment_gate_and_probe_path_are_inputs_of_complete_runtime_test(self):
        self.assert_wiring(self.document())

    def test_input_controls_reject_untracked_defaults_wrong_environment_and_fixed_probe_path(self):
        self.assert_mutations_fail((
            ('inputs.property("ownedLiveIntegrationOptIn", ownedLiveIntegrationOptIn)', ''),
            ('inputs.property("ownedLiveProbePath", ownedLiveProbePath)', ''),
            ('environmentVariable("KTC_RUN_OWNED_LIVE_INTEGRATION").orElse("")',
             'environmentVariable("KTC_RUN_OWNED_LIVE_INTEGRATION").orElse("1")'),
            ('environmentVariable("KTC_OWNED_LIVE_PROBE")', 'environmentVariable("OTHER_PROBE")'),
            ('environmentVariable("KTC_RUN_OWNED_LIVE_INTEGRATION").orElse("")',
             'environmentVariable("KTC_RUN_OWNED_LIVE_INTEGRATION").orElse("").map { it.trim() }'),
            ('environmentVariable("KTC_OWNED_LIVE_PROBE").orElse("")',
             'environmentVariable("KTC_OWNED_LIVE_PROBE").orElse("").map { it.trim() }'),
            ('inputs.property("ownedLiveProbePath", ownedLiveProbePath)',
             'inputs.property("ownedLiveProbePath", "/fixed/probe")'),
        ))

    def test_enabled_live_controls_reject_up_to_date_and_build_cache_reuse(self):
        self.assert_mutations_fail((
            ('outputs.upToDateWhen { ownedLiveIntegrationOptIn.get() != "1" }', 'outputs.upToDateWhen { true }'),
            ('outputs.upToDateWhen { ownedLiveIntegrationOptIn.get() != "1" }', 'outputs.upToDateWhen { false }'),
            ('outputs.upToDateWhen { ownedLiveIntegrationOptIn.get() != "1" }', ''),
            ('ownedLiveIntegrationOptIn.get() != "1"', 'ownedLiveIntegrationOptIn.get() == "1"'),
            ('outputs.doNotCacheIf', 'outputs.cacheIf'),
            ('ownedLiveIntegrationOptIn.get() == "1"', 'false'),
            ('ownedLiveIntegrationOptIn.get() == "1"', 'true'),
            ('ownedLiveIntegrationOptIn.get() == "1"', 'ownedLiveIntegrationOptIn.get() == "true"'),
        ))

    def test_test_scope_controls_reject_suite_filters_opt_out_and_new_process_actions(self):
        self.assert_mutations_fail((
            ('tasks.named<Test>("test")', 'tasks.named<Test>("liveOnlyTest")'),
            ('    inputs.property("ownedLiveIntegrationOptIn", ownedLiveIntegrationOptIn)',
             '    onlyIf { false }\n    inputs.property("ownedLiveIntegrationOptIn", ownedLiveIntegrationOptIn)'),
            ('    inputs.property("ownedLiveProbePath", ownedLiveProbePath)',
             '    filter { includeTestsMatching("*OwnedProcessSessionIntegrationTest") }'),
            ('    inputs.property("ownedLiveProbePath", ownedLiveProbePath)',
             '    useJUnitPlatform { excludeTags("owned-live-integration") }'),
            ('    inputs.property("ownedLiveProbePath", ownedLiveProbePath)',
             '    doFirst { exec { commandLine("/fixed/probe") } }'),
        ))


if __name__ == "__main__":
    unittest.main()
