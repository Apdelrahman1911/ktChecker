#!/usr/bin/env python3
"""Original synthetic compiler inputs, not executed proof or upstream conformance."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / 'providers/compiler-fixtures'
rules = {r['upstream_id']: r for r in json.loads((ROOT / 'data/rules-core.json').read_text()) if r['id'].startswith('ktc.compiler.')}
CASES = {
    'UNRESOLVED_REFERENCE': ('fun broken() = absentSymbol()', 'fun absentSymbol() = 1\nfun broken() = absentSymbol()'),
    'UNRESOLVED_IMPORT': ('import absent.packageName.Missing\nfun ok() = 1', 'import kotlin.collections.List\nfun size(xs: List<Int>) = xs.size'),
    'RETURN_TYPE_MISMATCH': ('fun broken(): Int = "text"', 'fun broken(): Int = 1'),
    'INITIALIZER_TYPE_MISMATCH': ('val value: Int = "text"', 'val value: Int = 1'),
    'ASSIGNMENT_TYPE_MISMATCH': ('fun assign(): Int { var x = 1; x = "text"; return x }', 'fun assign(): Int { var x = 1; x = 2; return x }'),
    'ARGUMENT_TYPE_MISMATCH': ('fun needInt(x: Int) = x\nval result = needInt("text")', 'fun needInt(x: Int) = x\nval result = needInt(1)'),
    'NULL_FOR_NONNULL_TYPE': ('fun needString(x: String) = x\nval result = needString(null)', 'fun needString(x: String) = x\nval result = needString("text")'),
    'UNSAFE_CALL': ('fun length(x: String?) = x.length', 'fun length(x: String?) = x?.length'),
    'NO_VALUE_FOR_PARAMETER': ('fun needInt(x: Int) = x\nval result = needInt()', 'fun needInt(x: Int) = x\nval result = needInt(1)'),
    'TOO_MANY_ARGUMENTS': ('fun zero() = 1\nval result = zero(2)', 'fun zero() = 1\nval result = zero()'),
    'NAMED_PARAMETER_NOT_FOUND': ('fun use(x: Int = 0) = x\nval result = use(z = 1)', 'fun use(x: Int = 0) = x\nval result = use(x = 1)'),
    'ARGUMENT_PASSED_TWICE': ('fun use(x: Int) = x\nval result = use(1, x = 2)', 'fun use(x: Int) = x\nval result = use(x = 1)'),
    'NON_VARARG_SPREAD': ('fun use(xs: IntArray) = xs.size\nval result = use(*intArrayOf(1))', 'fun use(xs: IntArray) = xs.size\nval result = use(intArrayOf(1))'),
    'SPREAD_OF_NULLABLE': ('fun use(vararg xs: Int) = xs.size\nfun broken(xs: IntArray?) = use(*xs)', 'fun use(vararg xs: Int) = xs.size\nfun fixed(xs: IntArray?) = if (xs == null) 0 else use(*xs)'),
    'VAL_REASSIGNMENT': ('fun broken(): Int { val x = 1; x = 2; return x }', 'fun fixed(): Int { var x = 1; x = 2; return x }'),
    'UNINITIALIZED_VARIABLE': ('fun broken(): Int { val x: Int; return x }', 'fun fixed(): Int { val x: Int = 1; return x }'),
    'NO_ELSE_IN_WHEN': ('fun broken(x: Boolean): Int = when (x) { true -> 1 }', 'fun fixed(x: Boolean): Int = when (x) { true -> 1; false -> 0 }'),
    'WRONG_ANNOTATION_TARGET': ('@Target(AnnotationTarget.FUNCTION) annotation class OnlyFunction\n@OnlyFunction class Wrong', '@Target(AnnotationTarget.FUNCTION) annotation class OnlyFunction\n@OnlyFunction fun correct() = 1'),
    'EXPOSED_FUNCTION_RETURN_TYPE': ('private class Hidden\nfun exposed() = Hidden()', 'private class Hidden\nprivate fun hidden() = Hidden()'),
    'UPPER_BOUND_VIOLATED': ('class Holder<T : CharSequence>\nval broken = Holder<Int>()', 'class Holder<T : CharSequence>\nval fixed = Holder<String>()'),
    'DIVISION_BY_ZERO': ('val result = 1 / 0', 'val result = 1 / 1'),
    'UNREACHABLE_CODE': ('fun broken(): Int { return 1; println("scaffolding") }', 'fun reachable(): Int { println("before return"); return 1 }'),
    'CAST_NEVER_SUCCEEDS': ('fun deliberatelyNull(x: String) = x as? Int', 'fun maybeInt(x: Any) = x as? Int'),
    'USELESS_CAST': ('fun same(x: String) = x as String', 'fun uncertain(x: Any) = x as String'),
    'UNNECESSARY_SAFE_CALL': ('fun length(x: String) = x?.length', 'fun length(x: String?) = x?.length'),
    'UNNECESSARY_NOT_NULL_ASSERTION': ('fun length(x: String) = x!!.length', 'fun failFastLength(x: String?) = x!!.length'),
    'DEPRECATION': ('@Deprecated("Use another") fun old() = 1\nval result = old()', 'fun current() = 1\nval result = current()'),
    # Char literals avoid integer-literal folding/type changes; the pinned checker compares literal receivers/arguments.
    'EMPTY_RANGE': ("val emptySentinel = 'q'..'g'", "val usableRange = 'g'..'q'"),
    'JVM_STATIC_NOT_IN_OBJECT_OR_COMPANION': ('class Plain { @JvmStatic fun call() = 1 }', 'object Holder { @JvmStatic fun call() = 1 }'),
    'JVM_INLINE_WITHOUT_VALUE_CLASS': ('@JvmInline class Wrapper(val value: Int)', '@JvmInline value class Wrapper(val value: Int)'),
    'VALUE_CLASS_WITHOUT_JVM_INLINE_ANNOTATION': ('value class Wrapper(val value: Int)', '@JvmInline value class Wrapper(val value: Int)'),
    'INAPPLICABLE_JVM_FIELD': ('object Holder { @JvmField private val result: Int = 1 }', 'object Holder { @JvmField val result: Int = 1 }'),
    'OVERLOADS_WITHOUT_DEFAULT_ARGUMENTS': ('@JvmOverloads fun use(x: Int) = x', '@JvmOverloads fun use(x: Int = 1) = x'),
    'OVERLOADS_INTERFACE': ('interface Api { @JvmOverloads fun use(x: Int = 1): Int }', 'class Api { @JvmOverloads fun use(x: Int = 1): Int = x }'),
    'OVERLOADS_ABSTRACT': ('abstract class Api { @JvmOverloads abstract fun use(x: Int = 1): Int }', 'class Api { @JvmOverloads fun use(x: Int = 1): Int = x }'),
    'SYNCHRONIZED_ON_SUSPEND_ERROR': ('@Synchronized suspend fun use() = 1', '@Synchronized fun use() = 1'),
    'SYNCHRONIZED_ON_INLINE': ('@Synchronized inline fun use(action: () -> Unit) { action() }', '@Synchronized fun use(action: () -> Unit) { action() }'),
    'SYNCHRONIZED_ON_ABSTRACT': ('abstract class Api { @Synchronized abstract fun use() }', 'class Api { @Synchronized fun use() {} }'),
    'EXTERNAL_DECLARATION_CANNOT_HAVE_BODY': ('external fun use() {}', 'external fun use()'),
    'NON_DATA_CLASS_JVM_RECORD': ('@JvmRecord class Record(val value: Int)', '@JvmRecord data class Record(val value: Int)'),
    'JVM_RECORD_NOT_VAL_PARAMETER': ('@JvmRecord data class Record(var value: Int)', '@JvmRecord data class Record(val value: Int)'),
    'FIELD_IN_JVM_RECORD': ('@JvmRecord data class Record(val value: Int) { val extra = 1 }', '@JvmRecord data class Record(val value: Int) { val extra get() = 1 }'),
}
manifest = []
for name, (positive, negative) in CASES.items():
    rule = rules[name]
    folder = FIXTURES / 'cases' / name.lower()
    folder.mkdir(parents=True, exist_ok=True)
    package = 'package fixtures.' + name.lower() + '\n\n'
    for kind, text in [('positive', positive), ('negative', negative)]:
        (folder / (kind + '.kt')).write_text(package + text + '\n')
    manifest.append({'rule_id': rule['id'], 'upstream_id': name,
                     'positive': str((folder / 'positive.kt').relative_to(FIXTURES)),
                     'negative': str((folder / 'negative.kt').relative_to(FIXTURES)),
                     'declared_severity': rule['compiler_factory']['declaration_severity'].lower(),
                     'flags': ['-jvm-target', '17'] + (['-Wextra'] if name == 'UNREACHABLE_CODE' else []) +
                              (['-Xuse-fir-experimental-checkers'] if name == 'EMPTY_RANGE' else []),
                     'source': rule['sources'][0], 'status': 'unrun_synthetic_input'})
(FIXTURES / 'manifest.json').write_text(json.dumps({'schema_version': 1, 'kotlin_version': '2.4.21', 'fixtures': manifest}, indent=2) + '\n')
print('Wrote', len(manifest), 'original per-ID input pairs; no execution or qualification claimed')
