package dev.ktchecker.compiler

/** No response files, shell, plugins, extra source roots, output paths or -J escape. */
object CompilerFlags {
    private val standalone = setOf("-Werror", "-nowarn", "-progressive", "-Wextra", "-Xmulti-platform", "-Xexpect-actual-classes",
        "-Xuse-fir-experimental-checkers")
    private val paired = setOf("-language-version", "-api-version", "-jvm-target", "-module-name")
    private val extended = listOf(
        Regex("-Xexplicit-api(?:=(strict|warning|disable))?"),
        Regex("-Xreturn-value-checker=(disable|check|full)"),
        Regex("-Xwarning-level=[A-Z][A-Z0-9_]*:(disabled|warning|error)"),
        Regex("-opt-in=[A-Za-z_][A-Za-z0-9_.$]*"),
        Regex("-Xjsr305=(ignore|warn|strict)"),
        Regex("-Xjspecify-annotations=(ignore|warn|strict)"),
        Regex("-Xjvm-default=(all|all-compatibility|disable|enable|no-compatibility)"),
        Regex("-Xassertions=(always-enable|always-disable|jvm|legacy)"),
        Regex("-Xcontext-(parameters|receivers)"),
        Regex("-XXLanguage:[+-][A-Za-z][A-Za-z0-9]+"),
    )

    fun validate(arguments: List<String>): List<String> {
        val copied = arguments.toList()
        var i = 0
        while (i < copied.size) {
            val argument = copied[i]
            require(argument.isNotBlank() && argument.none { it == '\u0000' || it == '\n' || it == '\r' }) {
                "Invalid compiler argument"
            }
            when {
                argument in standalone -> i++
                argument in paired -> {
                    val value = copied.getOrNull(i + 1) ?: error("Missing $argument value")
                    require(value.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && !value.startsWith("-")) {
                        "Invalid $argument value"
                    }
                    i += 2
                }
                extended.any { it.matches(argument) } -> i++
                else -> error("Unsupported compiler argument: $argument; cannot silently ignore settings")
            }
        }
        return copied
    }

    /** Disabled or unenabled checks cannot discharge a rule merely because compilation succeeded. */
    internal fun activationGap(mapping: CompilerRuleMapping, arguments: List<String>): String? {
        if (mapping.declaredSeverity == "WARNING" && ("-nowarn" in arguments ||
                "-Xwarning-level=${mapping.upstreamId}:disabled" in arguments)) {
            return "compiler_warning_disabled_under_selected_configuration"
        }
        // Factory-file sections are not executable registration. Kotlin2.4.21 registers
        // EMPTY_RANGE separately in ExperimentalExpressionCheckers, and UNREACHABLE_CODE
        // in ExtraDeclarationCheckers despite its ordinary control-flow factory section.
        if (mapping.upstreamId == "EMPTY_RANGE" && "-Xuse-fir-experimental-checkers" !in arguments) {
            return "compiler_experimental_checker_not_enabled"
        }
        if (((mapping.sourceSection.equals("Extra checkers", ignoreCase = true) && mapping.upstreamId != "EMPTY_RANGE") ||
                mapping.upstreamId == "UNREACHABLE_CODE") && "-Wextra" !in arguments) {
            return "compiler_extra_checker_not_enabled"
        }
        if ((mapping.sourceSection.contains("return value", ignoreCase = true) || mapping.upstreamId.startsWith("RETURN_VALUE_NOT_USED")) &&
            arguments.none { it in setOf("-Xreturn-value-checker=check", "-Xreturn-value-checker=full") }) {
            return "compiler_return_value_checker_not_enabled"
        }
        return null
    }
}
