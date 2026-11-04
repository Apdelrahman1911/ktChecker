package dev.ktchecker.providers.lint

import dev.ktchecker.providers.runtime.ExecutionProblem
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class XmlLintLocation(val file: String, val line: Int?, val column: Int?, val endLine: Int?, val endColumn: Int?)
data class XmlLintIssue(
    val upstreamId: String,
    val severity: String,
    val message: String,
    val explanation: String,
    val locations: List<XmlLintLocation>,
    val includedVariants: Set<String> = emptySet(),
    val excludedVariants: Set<String> = emptySet(),
)
data class ParsedLintReport(val by: String, val issues: List<XmlLintIssue>)

/** Maintained Lint XML report format 6, not baselines/partial persistence or arbitrary XML. */
object LintXmlParser {
    fun parse(bytes: ByteArray, expectedClientRevision: String? = null): ParsedLintReport {
        if (bytes.isEmpty()) throw ExecutionProblem("lint_report_empty", "Empty provider output is not checked coverage")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = try {
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
            bytes.inputStream().use { builder.parse(it) }
        } catch (_: Exception) {
            throw ExecutionProblem("lint_report_invalid_xml", "Malformed/unsafe Lint report")
        }
        val root = document.documentElement
        if (root.tagName != "issues" || root.getAttribute("format") != "6" ||
            root.getAttribute("type") !in setOf("", "report", "report_with_fixes")) {
            throw ExecutionProblem("lint_report_schema_invalid", "Unsupported Lint report schema/type")
        }
        val by = root.getAttribute("by")
        if (!by.startsWith("lint ") || by.removePrefix("lint ").isBlank()) {
            throw ExecutionProblem("lint_report_version_missing", "Report lacks provider client revision")
        }
        if (expectedClientRevision != null && by.removePrefix("lint ").trim().substringBefore(' ') != expectedClientRevision) {
            throw ExecutionProblem("lint_report_version_mismatch", "Report revision differs from the pinned command")
        }
        val issues = elements(root).map { element ->
            if (element.tagName != "issue") throw ExecutionProblem("lint_report_schema_invalid", "Unexpected report element")
            val children = elements(element)
            if (children.any { it.tagName !in setOf("location", "fix") }) {
                throw ExecutionProblem("lint_report_schema_invalid", "Unexpected issue element")
            }
            XmlLintIssue(
                required(element, "id"), normalizeSeverity(required(element, "severity")), required(element, "message"),
                element.getAttribute("explanation"), children.filter { it.tagName == "location" }.map { location ->
                    XmlLintLocation(required(location, "file"), number(location, "line"),
                        number(location, "column")?.minus(1), number(location, "endLine"), number(location, "endColumn")?.minus(1))
                }, csv(element.getAttribute("includedVariants")), csv(element.getAttribute("excludedVariants")),
            )
        }
        return ParsedLintReport(by, issues)
    }

    private fun number(element: Element, name: String): Int? {
        if (!element.hasAttribute(name)) return null
        val value = element.getAttribute(name).toIntOrNull()
        if (value == null || value < 1) throw ExecutionProblem("lint_report_location_invalid", "Invalid one-based provider position")
        return value
    }
    private fun required(element: Element, name: String): String = element.getAttribute(name).takeIf { it.isNotBlank() }
        ?: throw ExecutionProblem("lint_report_attribute_missing:$name", "Required provider attribute is absent")
    private fun csv(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val values = text.split(',').map { it.trim() }
        if (values.any { it.isEmpty() }) throw ExecutionProblem("lint_report_variant_invalid", "Empty report variant")
        return values.toSet()
    }
    private fun elements(parent: Element): List<Element> = (0 until parent.childNodes.length).mapNotNull {
        val node = parent.childNodes.item(it)
        if (node.nodeType == Node.ELEMENT_NODE) node as Element else {
            if (node.nodeType == Node.TEXT_NODE && !node.textContent.isBlank()) {
                throw ExecutionProblem("lint_report_schema_invalid", "Unexpected non-whitespace report text")
            }
            null
        }
    }

    fun normalizeSeverity(severity: String): String = when (severity.lowercase()) {
        "fatal", "error" -> "error"
        "warning" -> "warning"
        "information", "informational", "info" -> "info"
        else -> throw ExecutionProblem("lint_report_severity_invalid", "Unknown provider severity")
    }
}
