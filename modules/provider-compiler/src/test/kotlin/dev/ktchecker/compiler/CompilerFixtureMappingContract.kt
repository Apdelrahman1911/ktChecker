package dev.ktchecker.compiler

/**
 * All existing fixture selectors describe legacy443 oracles, not a runtime mapping choice.
 * A successor provider must not reinterpret their raw companions or inherit their receipts.
 * This pure check grants no execution authority, and no legacy registry is installed here.
 */
internal object CompilerFixtureMappingContract {
    const val REFUSAL = "compiler_fixture_mapping_contract_mismatch"
    val LEGACY443 = CompilerMappingIdentity("kotlin-compiler-2.4.21-common443-legacy-v1",
        "sha256:f05c525e450c35cd7a7f701361fdb6467e00e7afd82a1c7cb4cfb60d728841e5")

    fun requireLegacy(actual: CompilerMappingIdentity) = requireExact(LEGACY443, actual)

    /** Metadata equality only. The fixed requireLegacy caller cannot accept an owner override. */
    internal fun requireExact(expected: CompilerMappingIdentity?, actual: CompilerMappingIdentity?) {
        check(expected != null && actual != null && expected == actual) { REFUSAL }
    }
}
