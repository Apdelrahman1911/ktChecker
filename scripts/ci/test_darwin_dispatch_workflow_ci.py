"""Inert source/AST controls for the opt-in indexed Darwin dispatch entrypoint.

No runner import, shell, native build, model call or hosted dispatch occurs here.
The closed authored-text readers are not YAML/Actions/Bash interpreters. Source
pins use universal-newline text so a Windows checkout does not change the pins;
the private source-review packet separately proves byte-identical old stanzas.
"""

import ast
import hashlib
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
PLATFORM = ".github/workflows/platform-validation.yml"
RUNNER = "scripts/ci/run_darwin_private_state.py"
STANDALONE = ".github/workflows/darwin-private-state-validation.yml"
PROFILE = "darwin-private-state-components"
BASE_PLATFORM_SHA256 = "cf5c4b24a70574ac8f31ed092e00a0724f8c3c80b9add16c9491def7955e1f45"
BASE_RUNNER_SHA256 = "4fd8f5a8e31b1863950018e7cc3dee0bcaa4303d773cf001d01acff7a7ba8a16"
STANDALONE_SHA256 = "e380dae85bc35531abbd59634fc7b8fa03fe8b5cc148dc33ab8f70f26f5a9bcf"
OLD_OPTIONS = "        options: [all, desktop, android, ios]\n"
NEW_OPTIONS = "        options: [all, desktop, android, ios, darwin-private-state-components]\n"
NEW_INPUTS = """      confirm_policy:
        description: Authorize fresh ordinary-user native components plus isolated sudo-chown fixture; no model call
        required: false
        default: false
        type: boolean
      expected_commit:
        description: Full reviewed source/workflow SHA for Darwin components; retries require a fresh dispatch
        required: false
        default: ''
        type: string
"""
NATIVE_SELECTOR = "${{ github.event_name == 'workflow_dispatch' && inputs.profile == 'darwin-private-state-components' && inputs.confirm_policy == true }}"
OLD_SELECTORS = {
    "desktop-jvm": "github.event_name != 'workflow_dispatch' || inputs.profile == 'all' || inputs.profile == 'desktop'",
    "android": "github.event_name != 'workflow_dispatch' || inputs.profile == 'all' || inputs.profile == 'android'",
    "ios-native": "github.event_name != 'workflow_dispatch' || inputs.profile == 'all' || inputs.profile == 'ios'",
}
STEP_NAMES = (
    "Bind fresh consent to exact source and workflow commit",
    "Check out reviewed source without persisted credentials",
    "Select bounded evidence runtime",
    "Select exact bridge Node runtime (binary bytes separately checked)",
    "Build target-native addon and execute sixteen explicit component controls",
    "Retain sanitized unqualified component summary only",
)
PREFLIGHT = (
    "set -euo pipefail",
    '[[ "$CONFIRM_POLICY" == \'true\' ]]',
    '[[ "$EXPECTED_COMMIT" =~ ^[0-9a-f]{40}$ ]]',
    '[[ "$EXPECTED_COMMIT" == "$EVENT_COMMIT" ]]',
    '[[ "$EXPECTED_COMMIT" == "$WORKFLOW_COMMIT" ]]',
    '[[ "$GITHUB_RUN_ATTEMPT" == \'1\' ]]',
    '[[ "$SDK_VERSION" == \'26.4\' ]]',
)
COMMAND = (
    "python scripts/ci/run_darwin_private_state.py",
    "--policy darwin-private-state-components-v1",
    '--expected-commit "$EXPECTED_COMMIT"',
    '--sdk-version "$SDK_VERSION"',
)
RUNNER_ADDITION = ",\n                 '.github/workflows/platform-validation.yml'"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def source_sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def authored_fields(block, indent):
    """Closed indentation reader; duplicate/unknown structures fail closed."""
    block = block.rstrip("\n") + "\n"
    rows = list(re.finditer(r"(?m)^" + " " * indent +
                           r"([A-Za-z_][A-Za-z0-9_-]*):(?: ([^\n]*))?\n", block))
    require(bool(rows) and rows[0].start() == 0, "Unexpected authored mapping")
    result = {}
    for index, row in enumerate(rows):
        key, scalar = row[1], row[2] or ""
        require(key not in result, "Duplicate authored field")
        end = rows[index + 1].start() if index + 1 < len(rows) else len(block)
        body = block[row.end():end]
        if scalar not in ("", "|", ">-"):
            require(not body.strip(), "Scalar has unexpected child source")
        result[key] = (scalar, body)
    return result


def scalar_map(block, indent):
    fields = authored_fields(block, indent)
    require(all(value and not body.strip() for value, body in fields.values()),
            "Only literal scalar fields are admitted")
    return {key: value for key, (value, _) in fields.items()}


def authored_steps(block):
    rows = list(re.finditer(r"(?m)^      - name: ([^\n]+)\n", block))
    require(bool(rows) and rows[0].start() == 0, "Unexpected authored steps")
    require(tuple(row[1] for row in rows) == STEP_NAMES, "Step roster/order changed")
    result = []
    for index, row in enumerate(rows):
        end = rows[index + 1].start() if index + 1 < len(rows) else len(block)
        result.append(authored_fields(block[row.end():end], 8))
    return result


def check_step(fields, scalars=None, mappings=None, command=None):
    scalars, mappings = scalars or {}, mappings or {}
    expected = set(scalars) | set(mappings) | ({"run"} if command else set())
    require(set(fields) == expected, "Step fields changed")
    for key, value in scalars.items():
        require(fields[key][0] == value and not fields[key][1].strip(), "Step scalar changed")
    for key, values in mappings.items():
        require(fields[key][0] == "" and scalar_map(fields[key][1], 10) == values,
                "Step mapping changed")
    if command:
        style, lines = command
        require(fields["run"][0] == style, "Command scalar style changed")
        require(fields["run"][1].rstrip("\n") == "\n".join("          " + line for line in lines),
                "Fixed authored command changed")
        require("${{" not in fields["run"][1], "Input interpolation in command source")


def check_workflow_source(document):
    marker = "\n  " + PROFILE + ":\n"
    require(document.count(marker) == 1, "Exactly one opted-in job is required")
    ordinary, native = document.split(marker, 1)
    require(ordinary.count(NEW_OPTIONS) == 1 and ordinary.count(NEW_INPUTS) == 1,
            "Optional typed Darwin dispatch inputs changed")
    baseline = ordinary.replace(NEW_OPTIONS, OLD_OPTIONS, 1).replace(NEW_INPUTS, "", 1)
    require(source_sha(baseline) == BASE_PLATFORM_SHA256,
            "Ordinary jobs, events, permissions or concurrency changed")
    header = authored_fields(native, 4)
    require(set(header) == {"if", "runs-on", "timeout-minutes", "env", "steps"},
            "Native job has unexpected fields or dependencies")
    for key, value in {"if": NATIVE_SELECTOR, "runs-on": "macos-26", "timeout-minutes": "25"}.items():
        require(header[key][0] == value and not header[key][1].strip(), "Native job gate/pin changed")
    require(header["env"][0] == "" and scalar_map(header["env"][1], 6) == {
        "DEVELOPER_DIR": "/Applications/Xcode_26.4.1.app/Contents/Developer",
    }, "Job environment broadened")
    require(header["steps"][0] == "", "Unexpected steps scalar")
    steps = authored_steps(header["steps"][1])
    check_step(steps[0], mappings={"env": {
        "CONFIRM_POLICY": "${{ inputs.confirm_policy }}",
        "EXPECTED_COMMIT": "${{ inputs.expected_commit }}",
        "EVENT_COMMIT": "${{ github.sha }}",
        "WORKFLOW_COMMIT": "${{ github.workflow_sha }}",
        "SDK_VERSION": "'26.4'",
    }}, command=("|", PREFLIGHT))
    check_step(steps[1], {"uses": "actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5"}, {"with": {
        "ref": "${{ github.workflow_sha }}", "persist-credentials": "false", "fetch-depth": "1",
    }})
    check_step(steps[2], {"uses": "actions/setup-python@ece7cb06caefa5fff74198d8649806c4678c61a1 # v6"},
               {"with": {"python-version": "'3.12'"}})
    check_step(steps[3], {"uses": "actions/setup-node@249970729cb0ef3589644e2896645e5dc5ba9c38 # v6"},
               {"with": {"node-version": "'24.20.0'", "check-latest": "false"}})
    check_step(steps[4], {"id": "native_components", "timeout-minutes": "18"}, {"env": {
        "KTC_DARWIN_NATIVE_COMPONENT_CONSENT": "'1'",
        "KTC_DARWIN_WORKFLOW_SHA": "${{ github.workflow_sha }}",
        "EXPECTED_COMMIT": "${{ inputs.expected_commit }}",
        "SDK_VERSION": "'26.4'",
    }}, (">-", COMMAND))
    check_step(steps[5], {
        "if": "${{ always() && steps.native_components.outputs.summary_created == 'true' }}",
        "uses": "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4",
    }, {"with": {
        "name": "darwin-private-state-components-${{ github.run_id }}-${{ github.run_attempt }}",
        "path": "build/ci-evidence/darwin-private-state.json",
        "if-no-files-found": "error", "retention-days": "14",
    }})
    return steps


def selected_jobs(document, event, profile=None, consent=None):
    """Finite declared-input truth table, never evaluate Actions expressions."""
    check_workflow_source(document)
    jobs = document.split("\njobs:\n", 1)[1]
    rows = list(re.finditer(r"(?m)^  ([a-z][a-z0-9-]*):\n", jobs))
    require(tuple(row[1] for row in rows) == (*OLD_SELECTORS, PROFILE), "Job roster changed")
    # The unchanged declared manual profile default is all, not the new lane.
    if event == "workflow_dispatch" and profile is None:
        profile = "all"
    context = {"github.event_name": event, "inputs.profile": profile, "inputs.confirm_policy": consent}
    selected = []
    for index, row in enumerate(rows):
        end = rows[index + 1].start() if index + 1 < len(rows) else len(jobs)
        selectors = re.findall(r"(?m)^    if: ([^\n]+)$", jobs[row.end():end])
        require(selectors == [OLD_SELECTORS.get(row[1], NATIVE_SELECTOR)], "Job selector changed")
        expression = selectors[0]
        if expression.startswith("${{ ") and expression.endswith(" }}"):
            expression = expression[4:-3]
        clauses = []
        for clause in expression.split(" || "):
            terms = []
            for term in clause.split(" && "):
                match = re.fullmatch(r"(github\.event_name|inputs\.profile|inputs\.confirm_policy) (==|!=) ('[^']*'|true)", term)
                require(match is not None, "Unknown selector form")
                expected = True if match[3] == "true" else match[3][1:-1]
                actual = context[match[1]]
                equal = type(actual) is type(expected) and actual == expected
                terms.append(equal if match[2] == "==" else not equal)
            clauses.append(all(terms))
        if any(clauses):
            selected.append(row[1])
    return tuple(selected)


def preflight_admits(document, values):
    """Model only the fixed source guards with synthetic strings; no shell/env."""
    steps = check_workflow_source(document)
    lines = tuple(line[10:] for line in steps[0]["run"][1].splitlines())
    require(lines == PREFLIGHT, "Unknown preflight source")
    outcomes = []
    for line in lines[1:]:
        match = re.fullmatch(r'\[\[ "\$([A-Z_]+)" (==|=~) (.+) \]\]', line)
        require(match is not None, "Unknown preflight guard")
        actual = values.get(match[1])
        if match[2] == "=~":
            require(match[3] == "^[0-9a-f]{40}$", "Unknown SHA guard")
            outcomes.append(isinstance(actual, str) and re.fullmatch(r"[0-9a-f]{40}", actual) is not None)
        else:
            variable = re.fullmatch(r'"\$([A-Z_]+)"', match[3])
            expected = values.get(variable[1]) if variable else match[3][1:-1]
            outcomes.append(isinstance(actual, str) and isinstance(expected, str) and actual == expected)
    return all(outcomes)


def check_runner_source(source):
    require(source.count(RUNNER_ADDITION) == 1, "One literal entrypoint addition is required")
    baseline = source.replace(RUNNER_ADDITION, "", 1)
    require(source_sha(baseline) == BASE_RUNNER_SHA256, "Runner changed outside the fixed source roster")
    tree = ast.parse(source)
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "source_digest"]
    require(len(functions) == 1, "One fixed source digest function is required")
    calls = [node for node in ast.walk(functions[0]) if isinstance(node, ast.Call)
             and isinstance(node.func, ast.Name) and node.func.id == "run"]
    require(len(calls) == 1 and len(calls[0].args) == 3, "Fixed source command changed")
    call = calls[0]
    require(isinstance(call.args[0], ast.List), "Dynamic source roster")
    require(ast.literal_eval(call.args[0]) == [
        "git", "ls-files", "-z", "--", "bridges/mcp", "bridges/reference-host", RUNNER, STANDALONE, PLATFORM,
    ], "Fixed literal source enrollment changed")
    del call.args[0].elts[-1]
    require(ast.dump(tree, include_attributes=False) == ast.dump(ast.parse(baseline), include_attributes=False),
            "Runner logic AST changed outside the literal source addition")


class DarwinDispatchWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.document = (ROOT / PLATFORM).read_text(encoding="utf-8")
        self.runner = (ROOT / RUNNER).read_text(encoding="utf-8")

    def reject_workflow_mutations(self, mutations, native_only=False):
        prefix, source = "", self.document
        if native_only:
            marker = "\n  " + PROFILE + ":\n"
            prefix, source = self.document.split(marker, 1)
            prefix += marker
        for label, old, new in mutations:
            with self.subTest(mutation=label):
                self.assertIn(old, source)
                changed = prefix + source.replace(old, new, 1)
                self.assertNotEqual(self.document, changed)
                with self.assertRaises(AssertionError):
                    check_workflow_source(changed)

    def test_exact_reviewed_recipe_has_only_one_independent_opt_in_job(self):
        check_workflow_source(self.document)

    def test_original_standalone_workflow_is_unchanged(self):
        self.assertEqual(STANDALONE_SHA256, source_sha((ROOT / STANDALONE).read_text(encoding="utf-8")))

    def test_original_manual_profiles_need_no_darwin_metadata(self):
        for profile, expected in {"all": tuple(OLD_SELECTORS), "desktop": ("desktop-jvm",),
                                  "android": ("android",), "ios": ("ios-native",)}.items():
            for consent in (None, False, True):
                with self.subTest(profile=profile, consent=consent):
                    self.assertEqual(expected, selected_jobs(self.document, "workflow_dispatch", profile, consent))

    def test_only_new_profile_and_true_consent_select_the_new_job(self):
        for consent, expected in ((None, ()), (False, ()), (True, (PROFILE,))):
            with self.subTest(consent=consent):
                self.assertEqual(expected, selected_jobs(self.document, "workflow_dispatch", PROFILE, consent))
        self.assertEqual(tuple(OLD_SELECTORS), selected_jobs(self.document, "workflow_dispatch", None, True))

    def test_non_dispatch_events_keep_only_the_original_jobs(self):
        for event in ("push", "pull_request", "schedule"):
            for profile in (None, "all", PROFILE):
                for consent in (None, False, True):
                    with self.subTest(event=event, profile=profile, consent=consent):
                        self.assertEqual(tuple(OLD_SELECTORS), selected_jobs(self.document, event, profile, consent))

    def test_synthetic_fresh_exact_metadata_is_required_by_every_guard(self):
        values = {"CONFIRM_POLICY": "true", "EXPECTED_COMMIT": "a" * 40, "EVENT_COMMIT": "a" * 40,
                  "WORKFLOW_COMMIT": "a" * 40, "GITHUB_RUN_ATTEMPT": "1", "SDK_VERSION": "26.4"}
        self.assertTrue(preflight_admits(self.document, values))
        for key in values:
            with self.subTest(missing=key):
                self.assertFalse(preflight_admits(self.document, {k: v for k, v in values.items() if k != key}))
        for key, wrong in (("CONFIRM_POLICY", "false"), ("EXPECTED_COMMIT", "A" * 40),
                           ("EXPECTED_COMMIT", "g" * 40), ("EXPECTED_COMMIT", "a" * 39),
                           ("EXPECTED_COMMIT", "a" * 41), ("EXPECTED_COMMIT", "a" * 40 + "\n"),
                           ("EVENT_COMMIT", "b" * 40), ("WORKFLOW_COMMIT", "b" * 40),
                           ("GITHUB_RUN_ATTEMPT", "2"), ("SDK_VERSION", "26.3"), ("SDK_VERSION", "26.4.1")):
            with self.subTest(field=key, wrong=wrong):
                self.assertFalse(preflight_admits(self.document, {**values, key: wrong}))

    def test_omitted_or_broadened_event_profile_consent_gate_is_rejected(self):
        self.reject_workflow_mutations([
            ("event", "github.event_name == 'workflow_dispatch' && ", ""),
            ("profile", "inputs.profile == 'darwin-private-state-components' && ", ""),
            ("consent", " && inputs.confirm_policy == true", ""),
            ("string-consent", "inputs.confirm_policy == true", "inputs.confirm_policy == 'true'"),
            ("or-consent", " && inputs.confirm_policy == true", " || inputs.confirm_policy == true"),
        ], native_only=True)

    def test_optional_default_false_inputs_cannot_be_broadened_or_required_globally(self):
        self.reject_workflow_mutations([
            ("default-true", "        default: false\n", "        default: true\n"),
            ("required-consent", "        required: false\n", "        required: true\n"),
            ("untyped-consent", "        type: boolean\n", "        type: string\n"),
            ("required-sha", "        required: false\n        default: ''", "        required: true\n        default: ''"),
            ("default-sha", "        default: ''\n", "        default: main\n"),
            ("sha-type", "        default: ''\n        type: string", "        default: ''\n        type: boolean"),
            ("global-sdk", "\npermissions:\n", "      sdk_version:\n        type: string\n\npermissions:\n"),
        ])

    def test_removing_any_precheckout_guard_is_rejected(self):
        self.reject_workflow_mutations([(f"guard-{index}", "          " + line + "\n", "")
                                        for index, line in enumerate(PREFLIGHT)], native_only=True)
        self.reject_workflow_mutations([("weak-sha", "^[0-9a-f]{40}$", "^[0-9a-f]+$"),
                                        ("retry", '"$GITHUB_RUN_ATTEMPT" == \'1\'', '"$GITHUB_RUN_ATTEMPT" == \'2\'')], native_only=True)

    def test_exact_checkout_action_ref_and_no_persisted_credentials_are_required(self):
        self.reject_workflow_mutations([
            ("ref", "          ref: ${{ github.workflow_sha }}\n", ""),
            ("ref-to-event", "          ref: ${{ github.workflow_sha }}", "          ref: ${{ github.sha }}"),
            ("shallow", "          fetch-depth: 1\n", "          fetch-depth: 0\n"),
            ("credentials", "          ref: ${{ github.workflow_sha }}\n          persist-credentials: false",
             "          ref: ${{ github.workflow_sha }}\n          persist-credentials: true"),
            ("checkout-pin", "actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5", "actions/checkout@v5"),
            ("suppressed-preflight", "      - name: " + STEP_NAMES[0] + "\n", "      - name: " + STEP_NAMES[0] + "\n        continue-on-error: true\n"),
        ], native_only=True)

        marker = "\n  " + PROFILE + ":\n"
        prefix, native = self.document.split(marker, 1)
        first, second, third = (native.index("      - name: " + name) for name in STEP_NAMES[:3])
        reordered = native[:first] + native[second:third] + native[first:second] + native[third:]
        with self.subTest(mutation="checkout-before-consent"):
            with self.assertRaises(AssertionError):
                check_workflow_source(prefix + marker + reordered)

    def test_runtime_sdk_xcode_and_duration_drift_are_rejected(self):
        self.reject_workflow_mutations([
            ("host", "    runs-on: macos-26\n    timeout-minutes: 25", "    runs-on: macos-latest\n    timeout-minutes: 25"),
            ("job-bound", "    timeout-minutes: 25\n    env:\n", "    timeout-minutes: 26\n    env:\n"),
            ("step-bound", "        timeout-minutes: 18", "        timeout-minutes: 19"),
            ("xcode", "/Applications/Xcode_26.4.1.app/Contents/Developer", "/Applications/Xcode.app/Contents/Developer"),
            ("python", "          python-version: '3.12'", "          python-version: '3.13'"),
            ("node", "          node-version: '24.20.0'", "          node-version: '24.21.0'"),
            ("node-latest", "          check-latest: false", "          check-latest: true"),
            ("sdk", "          SDK_VERSION: '26.4'", "          SDK_VERSION: '26.3'"),
        ], native_only=True)

    def test_native_command_policy_consent_and_exact_sdk_binding_are_required(self):
        self.reject_workflow_mutations([
            ("policy", "--policy darwin-private-state-components-v1", "--policy darwin-private-state-components-v2"),
            ("expected", '--expected-commit "$EXPECTED_COMMIT"', "--expected-commit main"),
            ("sdk-argument", '--sdk-version "$SDK_VERSION"', "--sdk-version 26.3"),
            ("native-consent", "KTC_DARWIN_NATIVE_COMPONENT_CONSENT: '1'", "KTC_DARWIN_NATIVE_COMPONENT_CONSENT: '0'"),
            ("workflow-binding", "KTC_DARWIN_WORKFLOW_SHA: ${{ github.workflow_sha }}", "KTC_DARWIN_WORKFLOW_SHA: ${{ github.sha }}"),
            ("expected-binding", "EXPECTED_COMMIT: ${{ inputs.expected_commit }}", "EXPECTED_COMMIT: ${{ github.sha }}"),
            ("native-sdk", "KTC_DARWIN_NATIVE_COMPONENT_CONSENT: '1'\n          KTC_DARWIN_WORKFLOW_SHA: ${{ github.workflow_sha }}\n          EXPECTED_COMMIT: ${{ inputs.expected_commit }}\n          SDK_VERSION: '26.4'",
             "KTC_DARWIN_NATIVE_COMPONENT_CONSENT: '1'\n          KTC_DARWIN_WORKFLOW_SHA: ${{ github.workflow_sha }}\n          EXPECTED_COMMIT: ${{ inputs.expected_commit }}\n          SDK_VERSION: '26.3'"),
        ], native_only=True)

    def test_direct_input_interpolation_or_unquoted_commit_command_is_rejected(self):
        self.reject_workflow_mutations([
            ("interpolation", '--expected-commit "$EXPECTED_COMMIT"', '--expected-commit "${{ inputs.expected_commit }}"'),
            ("unquoted", '--expected-commit "$EXPECTED_COMMIT"', "--expected-commit $EXPECTED_COMMIT"),
            ("preflight-interpolation", '[[ "$EXPECTED_COMMIT" == "$EVENT_COMMIT" ]]', '[[ "${{ inputs.expected_commit }}" == "$EVENT_COMMIT" ]]'),
        ], native_only=True)

    def test_summary_upload_must_be_new_exact_sanitized_and_attempt_bound(self):
        self.reject_workflow_mutations([
            ("wildcard", "path: build/ci-evidence/darwin-private-state.json", "path: build/ci-evidence/*.json"),
            ("raw", "path: build/ci-evidence/darwin-private-state.json", "path: build/ci-evidence/raw/**"),
            ("creation-gate", "${{ always() && steps.native_components.outputs.summary_created == 'true' }}", "${{ always() }}"),
            ("artifact-attempt", "name: darwin-private-state-components-${{ github.run_id }}-${{ github.run_attempt }}", "name: darwin-private-state-components-${{ github.run_id }}"),
            ("retention", "path: build/ci-evidence/darwin-private-state.json\n          if-no-files-found: error\n          retention-days: 14", "path: build/ci-evidence/darwin-private-state.json\n          if-no-files-found: error\n          retention-days: 90"),
            ("missing-summary", "path: build/ci-evidence/darwin-private-state.json\n          if-no-files-found: error", "path: build/ci-evidence/darwin-private-state.json\n          if-no-files-found: warn"),
        ], native_only=True)

    def test_all_new_job_action_references_are_immutable(self):
        marker = "\n  " + PROFILE + ":\n"
        prefix, native = self.document.split(marker, 1)
        uses = re.findall(r"(?m)^        uses: ([^\n]+)$", native)
        self.assertEqual(4, len(uses))
        for reference in uses:
            with self.subTest(action=reference.split("@", 1)[0]):
                changed = prefix + marker + native.replace(reference, reference.split("@", 1)[0] + "@latest", 1)
                with self.assertRaises(AssertionError):
                    check_workflow_source(changed)

    def test_ordinary_jobs_triggers_permissions_and_concurrency_are_immutable(self):
        self.reject_workflow_mutations([
            ("selector", OLD_SELECTORS["ios-native"], "true"),
            ("permission", "  contents: read", "  contents: write"),
            ("extra-permission", "  contents: read", "  contents: read\n  id-token: write"),
            ("schedule", "    - cron: '0 3 * * 1'", "    - cron: '0 3 * * *'"),
            ("pull-request", "  pull_request:\n", "  pull_request_target:\n"),
            ("concurrency", "  group: platform-", "  group: changed-platform-"),
            ("cancel", "  cancel-in-progress: ${{ github.event_name == 'pull_request' }}", "  cancel-in-progress: true"),
        ])

    def test_no_global_enablement_extra_job_or_held_apple_dependency_is_admitted(self):
        self.reject_workflow_mutations([
            ("global-consent", "\njobs:\n", "\nenv:\n  KTC_DARWIN_NATIVE_COMPONENT_CONSENT: '1'\n\njobs:\n"),
            ("old-job-runner", "run: python scripts/ci/run_platform_checks.py --profile desktop", "run: python scripts/ci/run_darwin_private_state.py --profile desktop"),
            ("held-apple-needs", "    if: " + NATIVE_SELECTOR, "    needs: ios-native\n    if: " + NATIVE_SELECTOR),
            ("unguarded-job", "\n  " + PROFILE + ":\n", "\n  unguarded-native:\n    runs-on: macos-26\n    steps: []\n\n  " + PROFILE + ":\n"),
            ("extra-native-step", "      - name: " + STEP_NAMES[-1], "      - name: Unexpected native execution\n        run: python scripts/ci/run_darwin_private_state.py\n      - name: " + STEP_NAMES[-1]),
        ])

    def test_runner_adds_only_literal_active_entrypoint_to_the_source_roster(self):
        check_runner_source(self.runner)

    def test_runner_roster_or_ordinary_user_sixteen_case_logic_mutations_are_rejected(self):
        mutations = (
            ("missing-entrypoint", RUNNER_ADDITION, ""),
            ("dynamic-entrypoint", "'.github/workflows/platform-validation.yml'", "env['ENTRYPOINT']"),
            ("duplicate-entrypoint", RUNNER_ADDITION, RUNNER_ADDITION + RUNNER_ADDITION),
            ("missing-standalone", "'.github/workflows/darwin-private-state-validation.yml'", "'.github/workflows/other.yml'"),
            ("missing-bridge", "'bridges/reference-host'", "'bridges/other'"),
            ("ordinary-user", "os.getuid() == 0", "False"),
            ("denominator", "'tests': 16, 'pass': 16", "'tests': 15, 'pass': 15"),
            ("attempt", "env.get('GITHUB_RUN_ATTEMPT') != '1'", "env.get('GITHUB_RUN_ATTEMPT') != '2'"),
        )
        for label, old, new in mutations:
            with self.subTest(mutation=label):
                self.assertIn(old, self.runner)
                with self.assertRaises(AssertionError):
                    check_runner_source(self.runner.replace(old, new, 1))

    def test_controls_import_only_stdlib_and_never_import_or_invoke_native_runner(self):
        tree = ast.parse(Path(__file__).read_text(encoding="utf-8"))
        modules = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                modules.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                self.assertEqual(0, node.level)
                modules.add(node.module)
            elif isinstance(node, ast.Call):
                name = node.func.id if isinstance(node.func, ast.Name) else getattr(node.func, "attr", "")
                self.assertNotIn(name, {"eval", "exec", "compile", "__import__", "Popen", "system", "run"})
        self.assertEqual({"ast", "hashlib", "pathlib", "re", "unittest"}, modules)


if __name__ == "__main__":
    unittest.main()
