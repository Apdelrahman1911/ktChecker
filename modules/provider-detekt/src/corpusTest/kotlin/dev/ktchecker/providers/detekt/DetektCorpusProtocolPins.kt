package dev.ktchecker.providers.detekt

import java.util.Collections

/** New protocol data identity only; no admission, execution or borrowed historical stage. */
internal object DetektCorpusProtocolPins {
    const val ROOT = "providers/detekt-fixtures/corpus/syntax-protocol-v1"
    val files: Map<String, String> = Collections.unmodifiableMap(linkedMapOf(
        "providers/detekt-fixtures/corpus/syntax-protocol-v1/README.md" to "a5d8f3f1b58d506a033fb5b746352bac72554f3bdc14f82070870bb301673eec",
        "providers/detekt-fixtures/corpus/syntax-protocol-v1/cohorts/protocol-and-catch-boundaries-v1.json" to "dd92538470b9221d71d471c9aadf632cb05084cdeba10e261c72200004e36701",
        "providers/detekt-fixtures/corpus/syntax-protocol-v1/cohorts.json" to "270761489f2299a0789309ab916c7e2893e844e5b8041623db383b65ec1817c6",
        "providers/detekt-fixtures/corpus/syntax-protocol-v1/fixtures/iterator-not-throwing-no-such-element-exception.json" to "948e040ddb8dc8ee7b4b4a03f28b5f99c286488ac067cf2b8de0bd0f9d94a421",
        "providers/detekt-fixtures/corpus/syntax-protocol-v1/fixtures/too-generic-exception-caught.json" to "e6ec648a998557360fc0f102021b174465174074ecb63f95f724cfc770e50ff9",
        "providers/detekt-fixtures/corpus/syntax-protocol-v1/fixtures/wrong-equals-type-parameter.json" to "7d61a3327c1711387d2afafe2632518930fe9e1e01ca3f6403776ca9273465e6",
        "providers/detekt-fixtures/corpus/syntax-protocol-v1/integration-contract.json" to "e99e21efc8673f4bb042fa19e9dd1b9fd0a3a7b4f8fcf9018d53cccbad46a775",
        "providers/detekt-fixtures/corpus/syntax-protocol-v1/scope.json" to "572f28d2a60acddc1624bd9cbacfee5bbaf5d397e12545a6a5121f3b2f4d45a6",
    ))
    val planIdentities: Map<String, String> = Collections.unmodifiableMap(linkedMapOf(
        "protocol-and-catch-boundaries-v1" to "{\"aggregate_context_activation\":false,\"case_matrix_sha256\":\"dd92538470b9221d71d471c9aadf632cb05084cdeba10e261c72200004e36701\",\"cohort_id\":\"protocol-and-catch-boundaries-v1\",\"integration_contract_sha256\":\"e99e21efc8673f4bb042fa19e9dd1b9fd0a3a7b4f8fcf9018d53cccbad46a775\",\"limits\":{\"aggregate_millis\":1200000,\"child_millis\":60000,\"max_invocations\":40},\"opt_in_rule_ids\":[\"ktc.kotlin.detekt.exceptions.too-generic-exception-caught\"],\"planned_invocations\":{\"changed_configuration_authorization_denial\":3,\"cli_probe\":1,\"detector_analysis\":27,\"unsupported_target_abstention\":6},\"previous_stages_accepted\":false,\"requires_explicit_owner_opt_in\":true,\"schema\":\"ktchecker/detekt-syntax-protocol-cohort-identity/1\",\"scope_sha256\":\"572f28d2a60acddc1624bd9cbacfee5bbaf5d397e12545a6a5121f3b2f4d45a6\",\"selected_rules\":[{\"analyses\":9,\"default_activation\":\"advisory\",\"fixture_path\":\"providers/detekt-fixtures/corpus/syntax-protocol-v1/fixtures/iterator-not-throwing-no-such-element-exception.json\",\"fixture_sha256\":\"948e040ddb8dc8ee7b4b4a03f28b5f99c286488ac067cf2b8de0bd0f9d94a421\",\"rule_id\":\"ktc.kotlin.detekt.potential-bugs.iterator-not-throwing-no-such-element-exception\",\"upstream_id\":\"potential-bugs:IteratorNotThrowingNoSuchElementException\"},{\"analyses\":9,\"default_activation\":\"advisory\",\"fixture_path\":\"providers/detekt-fixtures/corpus/syntax-protocol-v1/fixtures/wrong-equals-type-parameter.json\",\"fixture_sha256\":\"7d61a3327c1711387d2afafe2632518930fe9e1e01ca3f6403776ca9273465e6\",\"rule_id\":\"ktc.kotlin.detekt.potential-bugs.wrong-equals-type-parameter\",\"upstream_id\":\"potential-bugs:WrongEqualsTypeParameter\"},{\"analyses\":9,\"default_activation\":\"opt_in\",\"fixture_path\":\"providers/detekt-fixtures/corpus/syntax-protocol-v1/fixtures/too-generic-exception-caught.json\",\"fixture_sha256\":\"e6ec648a998557360fc0f102021b174465174074ecb63f95f724cfc770e50ff9\",\"rule_id\":\"ktc.kotlin.detekt.exceptions.too-generic-exception-caught\",\"upstream_id\":\"exceptions:TooGenericExceptionCaught\"}]}",
    ))
    val controlIds: Map<String, List<String>> = Collections.unmodifiableMap(linkedMapOf(
        "ktc.kotlin.detekt.potential-bugs.iterator-not-throwing-no-such-element-exception" to Collections.unmodifiableList(listOf("delegated-list-iterator-already-throws", "dormant-local-throw-does-not-enforce-exhaustion", "qualified-exhaustion-exception-is-still-correct")),
        "ktc.kotlin.detekt.potential-bugs.wrong-equals-type-parameter" to Collections.unmodifiableList(listOf("typed-overload-beside-correct-any-override", "nullable-any-typealias-is-a-valid-override", "interface-typed-overload-is-outside-visitor")),
        "ktc.kotlin.detekt.exceptions.too-generic-exception-caught" to Collections.unmodifiableList(listOf("cancellation-rethrow-does-not-waive-broad-catch-policy", "qualified-broad-exception-escapes-text-list", "configured-throwable-only-keeps-one-active-finding")),
    ))
}
