#!/usr/bin/env python3
"""Validate authored corpus inputs only; never invoke Detekt or claim integration."""

from __future__ import annotations

import json
from pathlib import Path, PurePosixPath
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "providers/detekt-fixtures/corpus/syntax-v1"
EXPECTED_IDS = {
    "ktc.kotlin.detekt.exceptions.throwing-exception-from-finally",
    "ktc.kotlin.detekt.exceptions.swallowed-exception",
    "ktc.kotlin.detekt.exceptions.not-implemented-declaration",
    "ktc.kotlin.detekt.potential-bugs.equals-always-returns-true-or-false",
    "ktc.kotlin.detekt.potential-bugs.invalid-range",
    "ktc.kotlin.detekt.potential-bugs.iterator-has-next-calls-next-method",
    "ktc.kotlin.detekt.potential-bugs.unconditional-jump-statement-in-loop",
    "ktc.kotlin.detekt.potential-bugs.useless-postfix-expression",
    "ktc.kotlin.detekt.style.data-class-should-be-immutable",
    "ktc.maintainability.detekt.libraries.library-entities-should-not-be-public",
}
CASE_KINDS = {
    "positive", "negative", "configuration", "version", "suppression", "regression",
}


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs)


def locate(text, offset):
    prefix = text[:offset]
    return prefix.count("\n") + 1, len(prefix.rsplit("\n", 1)[-1])


def validate_case(case):
    sources = case["sources"]
    if not sources or not isinstance(case["intent"], str) or not case["intent"]:
        raise ValueError("case requires captured sources and explicit intent")
    for path, source in sources.items():
        relative = PurePosixPath(path)
        if (relative.is_absolute() or "\\" in path or
                any(part in ("", ".", "..") for part in path.split("/")) or
                relative.suffix != ".kt"):
            raise ValueError("unsafe/non-Kotlin fixture path")
        if not isinstance(source, str) or not source or not source.endswith("\n"):
            raise ValueError("source must preserve nonempty UTF-8 text and its final newline")
        source.encode("utf-8", errors="strict")
    seen = set()
    for expected in case["expected"]:
        source = sources[expected["path"]]
        span = expected["span"]
        if not span:
            raise ValueError("expected range must identify a nonempty independent source span")
        offset = -len(span)
        for _ in range(expected["occurrence"] + 1):
            offset = source.find(span, offset + len(span))
            if offset < 0:
                raise ValueError("expected source span does not occur")
        start, end = locate(source, offset), locate(source, offset + len(span))
        if start != (expected["line"], expected["column"]) or end != (
                expected["end_line"], expected["end_column"]):
            raise ValueError("authored start/end coordinates do not match exact source bytes")
        identity = expected["path"], *start, *end
        if identity in seen:
            raise ValueError("duplicate expected diagnostic range")
        seen.add(identity)


class SyntaxCorpusAuthoringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifests = [read(path) for path in sorted(CORPUS.glob("*.json"))]
        cls.by_id = {manifest["rule_id"]: manifest for manifest in cls.manifests}
        mapping = read(ROOT / "modules/provider-detekt/src/main/resources/detekt-rule-map.json")
        cls.mapping = {row["rule_id"]: row for row in mapping["mappings"]}

    def test_exact_ten_ids_and_activation_are_not_count_expansion(self):
        self.assertEqual(10, len(self.manifests))
        self.assertEqual(EXPECTED_IDS, self.by_id.keys())
        self.assertEqual(8, sum(m["default_activation"] == "advisory" for m in self.manifests))
        self.assertEqual(2, sum(m["default_activation"] == "opt_in" for m in self.manifests))

    def test_manifest_is_a_current_exact_syntax_mapping_not_execution(self):
        for manifest in self.manifests:
            with self.subTest(rule=manifest["rule_id"]):
                row = self.mapping[manifest["rule_id"]]
                self.assertEqual(1, manifest["schema_version"])
                self.assertEqual("unexecuted", manifest["authoring_state"])
                self.assertEqual("2.0.0-alpha.6", manifest["provider_version"])
                self.assertEqual("2.4.10", manifest["embedded_kotlin_version"])
                for key in ("upstream_id", "relation", "depth", "default_activation", "catalog_record_sha256"):
                    self.assertEqual(row[key], manifest[key])
                self.assertEqual("exact", manifest["relation"])
                self.assertEqual("syntax", manifest["depth"])
                self.assertTrue(CASE_KINDS == manifest["six_case_plan"].keys())
                self.assertTrue(manifest["limitations"])
                self.assertNotIn("passed", manifest)
                self.assertNotIn("tested", manifest)
                self.assertNotIn("qualified", manifest)

    def test_every_expected_range_is_independent_exact_source_text(self):
        for manifest in self.manifests:
            cases = [manifest[kind] for kind in ("positive", "negative", "suppression")]
            cases += manifest["controls"]
            for index, case in enumerate(cases):
                with self.subTest(rule=manifest["rule_id"], case=index):
                    validate_case(case)
            self.assertTrue(manifest["positive"]["expected"])
            self.assertEqual([], manifest["negative"]["expected"])

    def test_suppression_keeps_all_original_neighbors_and_uses_upstream_id(self):
        for manifest in self.manifests:
            positive, suppression = manifest["positive"], manifest["suppression"]
            with self.subTest(rule=manifest["rule_id"]):
                self.assertEqual(positive["expected"], suppression["expected"])
                self.assertEqual(2 * len(positive["sources"]), len(suppression["sources"]))
                for path, source in positive["sources"].items():
                    self.assertEqual(source, suppression["sources"][path])
                    copy = suppression["sources"]["Suppressed" + path]
                    name = manifest["upstream_id"].split(":")[1]
                    self.assertTrue(copy.startswith(f'@file:Suppress("{name}")\n'))
                    self.assertNotIn('Suppress("ktc.', copy)
                    self.assertEqual(source, copy.split("\n\n", 1)[1].replace("package suppressed.", "package ", 1))

    def test_primary_citations_are_pinned_research_not_integration_artifacts(self):
        for manifest in self.manifests:
            citation = manifest["source_citation"]
            self.assertTrue(citation["research_only"])
            self.assertTrue(citation["url"].startswith("https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/"))
            self.assertEqual(64, len(citation["source_file_sha256"]))
            self.assertTrue(all(ch in "0123456789abcdef" for ch in citation["source_file_sha256"]))
            self.assertTrue(citation["predicate_line_numbers"])
            self.assertTrue(all(isinstance(line, int) and line > 0 for line in citation["predicate_line_numbers"]))

    def test_valid_alternatives_and_anticipated_gaps_stay_explicit(self):
        controls = {
            control["id"]: control
            for manifest in self.manifests for control in manifest["controls"]
        }
        for name in ("shadowed-todo-symbol", "shadowed-error-type", "reading-a-different-iterator"):
            self.assertEqual("anticipated_upstream_false_positive", controls[name]["assessment"])
            self.assertTrue(controls[name]["expected"])
        for name in ("cause-through-local-alias", "helper-based-legal-iterator", "elvis-continue-return"):
            self.assertEqual([], controls[name]["expected"])
        self.assertEqual("opt_in_policy_limit", controls["deliberate-immutable-public-api"]["assessment"])
        self.assertTrue(controls["deliberate-immutable-public-api"]["expected"])
        self.assertEqual("anticipated_upstream_false_negative", controls["long-and-negative-literal-gaps"]["assessment"])
        self.assertEqual([], controls["long-and-negative-literal-gaps"]["expected"])

    def test_real_configuration_options_have_their_own_controls(self):
        swallowed = self.by_id["ktc.kotlin.detekt.exceptions.swallowed-exception"]
        controls = [control for control in swallowed["controls"] if control["case_kind"] == "configuration"]
        self.assertEqual(2, len(controls))
        keys = {key for control in controls for key in control["options"]}
        self.assertEqual({"ignoredExceptionTypes", "allowedExceptionNameRegex"}, keys)
        self.assertTrue(all(not control["expected"] for control in controls))

    def test_validator_rejects_drift_and_duplicate_coordinates(self):
        case = json.loads(json.dumps(self.manifests[0]["positive"]))
        case["expected"][0]["column"] += 1
        with self.assertRaisesRegex(ValueError, "coordinates"):
            validate_case(case)
        case = json.loads(json.dumps(self.manifests[0]["positive"]))
        case["expected"].append(case["expected"][0].copy())
        with self.assertRaisesRegex(ValueError, "duplicate"):
            validate_case(case)
        with self.assertRaisesRegex(ValueError, "duplicate JSON"):
            json.loads('{"sources":{},"sources":{}}', object_pairs_hook=unique_pairs)

    def test_selected_exception_cohort_is_exactly_38_calls_not_a_ten_rule_run(self):
        selected = {
            "ktc.kotlin.detekt.exceptions.throwing-exception-from-finally": 8,
            "ktc.kotlin.detekt.exceptions.swallowed-exception": 11,
            "ktc.kotlin.detekt.exceptions.not-implemented-declaration": 9,
        }
        self.assertEqual(28, sum(selected.values()))
        self.assertEqual(38, sum(selected.values()) + 1 + 6 + 3)
        self.assertEqual(91, sum(6 + len(manifest["controls"]) for manifest in self.manifests))
        self.assertEqual(7, len(EXPECTED_IDS - selected.keys()))
        for rule, count in selected.items():
            self.assertEqual(count, 6 + len(self.by_id[rule]["controls"]))
        for manifest in self.manifests:
            ids = [control["id"] for control in manifest["controls"]]
            self.assertEqual(len(ids), len(set(ids)))
            self.assertFalse(CASE_KINDS.intersection(ids))
            self.assertEqual("unexecuted", manifest["authoring_state"])

    def test_source_only_driver_requires_cohort_and_reserves_every_call(self):
        base = ROOT / "modules/provider-detekt/src/corpusTest/kotlin/dev/ktchecker/providers/detekt"
        driver = (base / "DetektSyntaxCorpusIntegrationTest.kt").read_text()
        controls = (base / "DetektCorpusControls.kt").read_text()
        build = (ROOT / "modules/provider-detekt/build.gradle.kts").read_text()
        self.assertIn('const val NAME = "exceptions-core-v1"', controls)
        self.assertIn("require(cohort == DetektCorpusCohort.NAME)", controls)
        self.assertIn("val corpus = DetektCorpusCohort.analysesByRule.keys.map", driver)
        self.assertIn('"not_run_rule_ids"', driver)
        self.assertIn('put("full_corpus_state", "not_run")', driver)
        self.assertIn('put("scope_exclusions", JsonArray(emptyList()))', driver)
        self.assertEqual(3, len(re.findall(r"\.analyze\([^\n]+limits\.cancellation\)", driver)))
        self.assertEqual(1, len(re.findall(r"\.run\(probeInput, authority, limits\.cancellation\)", driver)))
        self.assertNotIn("CancellationToken.NONE", driver)
        self.assertIn("limits.reserve(CorpusInvocationKind.ANALYSIS", driver)
        self.assertIn("limits.reserve(CorpusInvocationKind.CLI_PROBE", driver)
        self.assertIn("limits.reserve(CorpusInvocationKind.AUTHORIZATION_DENIAL", driver)
        self.assertIn("limits.reserve(CorpusInvocationKind.ABSTENTION", driver)
        for name in ("detektCorpusCohort", "detektCorpusMaxInvocations", "detektCorpusAggregateMillis", "detektCorpusChildMillis"):
            self.assertIn(name, build)
        self.assertIn('== "exceptions-core-v1"', build)

    def test_source_only_budget_and_evidence_guards_are_fixed_and_isolated(self):
        base = ROOT / "modules/provider-detekt/src/corpusTest/kotlin/dev/ktchecker/providers/detekt"
        controls = (base / "DetektCorpusControls.kt").read_text()
        evidence = (base / "DetektCorpusEvidence.kt").read_text()
        driver = (base / "DetektSyntaxCorpusIntegrationTest.kt").read_text()
        for guard in ("MAX_INVOCATIONS = 40", "MAX_AGGREGATE_MILLIS = 1_200_000L", "MAX_CHILD_MILLIS = 60_000L",
                      "active == null", "actual.reasons.isEmpty()", "actual.executionConfirmed", "signal != expected",
                      "confirmedExecutions == 29", "observedRuntimeCallbacks == 32", "Duplicate invocation is a forbidden retry"):
            self.assertIn(guard, controls)
        self.assertIn("System::nanoTime", controls)
        self.assertIn("minOf(settings.childMillis, remainingMillis())", controls)
        self.assertIn("CREATE_NEW, WRITE, NOFOLLOW_LINKS", evidence)
        self.assertIn("SecureDirectoryStream", evidence)
        self.assertIn("key(attrs) == expected", evidence)
        self.assertIn("requireSingleLink(path)", evidence)
        self.assertIn("path == root.resolve(liveRelative)", evidence)
        self.assertNotIn("TRUNCATE_EXISTING", evidence)
        self.assertNotIn("StandardOpenOption.APPEND", evidence)
        for unsafe in ("ProcessHandle", ".destroy(", ".destroyForcibly(", "Files.deleteIfExists", "CancellationToken.NONE"):
            self.assertNotIn(unsafe, driver)
        self.assertTrue((base / "DetektCorpusControlsTest.kt").is_file())
        self.assertTrue((base / "DetektCorpusEvidenceTest.kt").is_file())

    def test_source_only_uncertain_jobs_are_retained_and_completion_is_candidate_first(self):
        base = ROOT / "modules/provider-detekt/src/corpusTest/kotlin/dev/ktchecker/providers/detekt"
        driver = (base / "DetektSyntaxCorpusIntegrationTest.kt").read_text()
        evidence = (base / "DetektCorpusEvidence.kt").read_text()
        controls = (base / "DetektCorpusControls.kt").read_text()
        self.assertNotIn("@TempDir", driver)
        self.assertNotIn("@AfterEach", driver)
        self.assertNotIn("setWritable", driver)
        self.assertNotIn("walkFileTree", driver)
        self.assertIn("temporary = owned.createRetainedScratch()", driver)
        self.assertIn('put("scratch_quarantined", true)', driver)
        self.assertIn('"rwx------"', evidence)
        self.assertIn('put("run_complete", false)', driver)
        self.assertIn('put("run_complete", false)', controls)
        self.assertIn('artifact(run, "candidate-sidecar.json"', driver)
        self.assertNotIn('artifact(run, "verified-sidecar.json"', driver)
        self.assertIn("owned.publish(assertNotNull(pendingSidecar), limits::requireWithinDeadline)", driver)
        self.assertIn("try { withdrawCohort() } catch", evidence)
        self.assertIn("Evidence-bound/serialization/checkpoint errors must never skip withdrawal", driver)
        self.assertIn("Every I/O handle is closed inside the caught operation", evidence)


if __name__ == "__main__":
    unittest.main()
