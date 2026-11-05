package dev.ktchecker.providers.detekt

import java.util.Collections

/** New observability data identity only; no admission, execution or borrowed historical stage. */
internal object DetektCorpusObservabilityPins {
    const val ROOT = "providers/detekt-fixtures/corpus/syntax-exception-observability-v1"
    val files: Map<String, String> = Collections.unmodifiableMap(linkedMapOf(
        "providers/detekt-fixtures/corpus/syntax-exception-observability-v1/README.md" to "178254aada120d55128c050ef928feb40532c5c0f8caa64aadde0ddcbc887452",
        "providers/detekt-fixtures/corpus/syntax-exception-observability-v1/cohorts/exception-observability-policy-v1.json" to "fa5a6bb853a6dd6b21b4101c86d50f136aa4e1a748ff8d8e4459f661be6409c1",
        "providers/detekt-fixtures/corpus/syntax-exception-observability-v1/cohorts.json" to "e29e9f14c0cce37bd4c868b9fda91e4e3bd7170fc875e72600562f126fe95fb1",
        "providers/detekt-fixtures/corpus/syntax-exception-observability-v1/fixtures/print-stack-trace.json" to "19f00be9222d0d9f2dc7d383732f50c49f045825b86d80c9126f04f0cfe4b5e8",
        "providers/detekt-fixtures/corpus/syntax-exception-observability-v1/fixtures/rethrow-caught-exception.json" to "acbec925685ca96468e85efb333bcde2084714b219c400a857c2683cc9318209",
        "providers/detekt-fixtures/corpus/syntax-exception-observability-v1/fixtures/throwing-exceptions-without-message-or-cause.json" to "fd40be8293bb11848f72a814c77ba65dbd4010b031375cf81f0fbc689e9c3081",
        "providers/detekt-fixtures/corpus/syntax-exception-observability-v1/integration-contract.json" to "6ff3668ad97ceafd046f4a4546a616fee5be9f034240789502b95545eadf0e86",
        "providers/detekt-fixtures/corpus/syntax-exception-observability-v1/scope.json" to "796366a129447f44d0a052a01fa948fb4dc90d55ce926b6a6d730a6a63c928f7",
    ))
    val planIdentities: Map<String, String> = Collections.unmodifiableMap(linkedMapOf(
        "exception-observability-policy-v1" to "{\"aggregate_context_activation\":false,\"case_matrix_sha256\":\"fa5a6bb853a6dd6b21b4101c86d50f136aa4e1a748ff8d8e4459f661be6409c1\",\"cohort_id\":\"exception-observability-policy-v1\",\"integration_contract_sha256\":\"6ff3668ad97ceafd046f4a4546a616fee5be9f034240789502b95545eadf0e86\",\"limits\":{\"aggregate_millis\":1200000,\"child_millis\":60000,\"max_invocations\":40},\"opt_in_rule_ids\":[\"ktc.kotlin.detekt.exceptions.rethrow-caught-exception\",\"ktc.kotlin.detekt.exceptions.print-stack-trace\",\"ktc.kotlin.detekt.exceptions.throwing-exceptions-without-message-or-cause\"],\"planned_invocations\":{\"changed_configuration_authorization_denial\":3,\"cli_probe\":1,\"detector_analysis\":27,\"unsupported_target_abstention\":6},\"previous_stages_accepted\":false,\"requires_explicit_owner_opt_in\":true,\"schema\":\"ktchecker/detekt-syntax-exception-observability-cohort-identity/1\",\"scope_sha256\":\"796366a129447f44d0a052a01fa948fb4dc90d55ce926b6a6d730a6a63c928f7\",\"selected_rules\":[{\"analyses\":9,\"default_activation\":\"opt_in\",\"fixture_path\":\"providers/detekt-fixtures/corpus/syntax-exception-observability-v1/fixtures/rethrow-caught-exception.json\",\"fixture_sha256\":\"acbec925685ca96468e85efb333bcde2084714b219c400a857c2683cc9318209\",\"rule_id\":\"ktc.kotlin.detekt.exceptions.rethrow-caught-exception\",\"upstream_id\":\"exceptions:RethrowCaughtException\"},{\"analyses\":9,\"default_activation\":\"opt_in\",\"fixture_path\":\"providers/detekt-fixtures/corpus/syntax-exception-observability-v1/fixtures/print-stack-trace.json\",\"fixture_sha256\":\"19f00be9222d0d9f2dc7d383732f50c49f045825b86d80c9126f04f0cfe4b5e8\",\"rule_id\":\"ktc.kotlin.detekt.exceptions.print-stack-trace\",\"upstream_id\":\"exceptions:PrintStackTrace\"},{\"analyses\":9,\"default_activation\":\"opt_in\",\"fixture_path\":\"providers/detekt-fixtures/corpus/syntax-exception-observability-v1/fixtures/throwing-exceptions-without-message-or-cause.json\",\"fixture_sha256\":\"fd40be8293bb11848f72a814c77ba65dbd4010b031375cf81f0fbc689e9c3081\",\"rule_id\":\"ktc.kotlin.detekt.exceptions.throwing-exceptions-without-message-or-cause\",\"upstream_id\":\"exceptions:ThrowingExceptionsWithoutMessageOrCause\"}]}",
    ))
    val controlIds: Map<String, List<String>> = Collections.unmodifiableMap(linkedMapOf(
        "ktc.kotlin.detekt.exceptions.rethrow-caught-exception" to Collections.unmodifiableList(listOf("parenthesized-rethrow-escapes-exact-name", "following-work-exempts-earlier-rethrow", "earlier-work-does-not-exempt-trailing-rethrow")),
        "ktc.kotlin.detekt.exceptions.print-stack-trace" to Collections.unmodifiableList(listOf("non-jdk-thread-dumpstack-is-not-stack-printing", "caught-throwable-alias-still-prints", "qualified-jdk-thread-dumpstack-is-missed")),
        "ktc.kotlin.detekt.exceptions.throwing-exceptions-without-message-or-cause" to Collections.unmodifiableList(listOf("same-named-string-factory-is-not-an-exception", "import-aliased-no-argument-exception-is-missed", "configured-state-exception-only-keeps-one-active-finding")),
    ))
}
