"""Original authored 2026-11-04 compiler inputs; source availability, never execution proof.

Ordinary cases retain the six existing assessment kinds. KMP cases are segregated
planned common/JVM source-role graphs; do not compile them as same-module inputs
or count them as delivered until a sealed fragment/source-role capability exists.
"""

EXPANSION_CASES = {'ABSTRACT_MEMBER_NOT_IMPLEMENTED': ('interface Api { fun read(): Int }\nclass Reader : Api',
                                     'interface Api { fun read(): Int }\n'
                                     'class Reader : Api { override fun read(): Int = 1 }',
                                     'An intentionally abstract implementing class may leave the member '
                                     'abstract; invalid compile-negative examples still remain compiler '
                                     'errors.'),
 'ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED': ('abstract class Base { abstract fun read(): Int }\n'
                                           'class Reader : Base()',
                                           'abstract class Base { abstract fun read(): Int }\n'
                                           'class Reader : Base() { override fun read(): Int = 1 }',
                                           'Making the child abstract is also legitimate when it is not '
                                           'instantiated; no missing member is inferred from incomplete '
                                           'classpath data.'),
 'NOTHING_TO_OVERRIDE': ('class Reader { override fun read(): Int = 1 }',
                         'class Reader { fun read(): Int = 1 }',
                         'An inherited declaration supplied by the real dependency graph can make '
                         'override valid; a missing dependency is unsupported context, not a guessed '
                         'defect.'),
 'OVERRIDING_FINAL_MEMBER': ('open class Base { fun read(): Int = 1 }\n'
                             'class Reader : Base() { override fun read(): Int = 2 }',
                             'open class Base { open fun read(): Int = 1 }\n'
                             'class Reader : Base() { override fun read(): Int = 2 }',
                             'A plugin or different source version can change finality; only the exact '
                             'selected compiler/plugin context may decide that.'),
 'CANNOT_WEAKEN_ACCESS_PRIVILEGE': ('open class Base { open fun read(): Int = 1 }\n'
                                    'class Reader : Base() { protected override fun read(): Int = 2 }',
                                    'open class Base { open fun read(): Int = 1 }\n'
                                    'class Reader : Base() { public override fun read(): Int = 2 }',
                                    'Visibility widening is valid; a protected base API should not be '
                                    'treated as a public API copied from another module.'),
 'RETURN_TYPE_MISMATCH_ON_OVERRIDE': ('interface Api { fun read(): Number }\n'
                                      'class Reader : Api { override fun read(): String = "text" }',
                                      'interface Api { fun read(): Number }\n'
                                      'class Reader : Api { override fun read(): Int = 1 }',
                                      'Covariant subtype returns are legitimate; the corrected Int '
                                      'override deliberately exercises that allowed alternative.'),
 'PROPERTY_TYPE_MISMATCH_ON_OVERRIDE': ('interface Api { val value: Int }\n'
                                        'class Reader : Api { override val value: String = "text" }',
                                        'interface Api { val value: Int }\n'
                                        'class Reader : Api { override val value: Int = 1 }',
                                        'Read-only property type covariance may be legal, but mutable '
                                        'properties and unrelated type changes cannot be guessed '
                                        'compatible.'),
 'VAR_OVERRIDDEN_BY_VAL': ('interface Api { var value: Int }\n'
                           'class Reader : Api { override val value: Int = 1 }',
                           'interface Api { var value: Int }\n'
                           'class Reader : Api { override var value: Int = 1 }',
                           'A var implementing an expected val is an allowed capability widening; this '
                           'pair tests the prohibited opposite direction only.'),
 'VIRTUAL_MEMBER_HIDDEN': ('open class Base { open fun read(): Int = 1 }\n'
                           'class Reader : Base() { fun read(): Int = 2 }',
                           'open class Base { open fun read(): Int = 1 }\n'
                           'class Reader : Base() { override fun read(): Int = 2 }',
                           'Genuine overloads with different parameter signatures are valid; no textual '
                           'same-name hiding rule is introduced.'),
 'SUSPEND_OVERRIDDEN_BY_NON_SUSPEND': ('interface Api { suspend fun read(): Int }\n'
                                       'class Reader : Api { override fun read(): Int = 1 }',
                                       'interface Api { suspend fun read(): Int }\n'
                                       'class Reader : Api { override suspend fun read(): Int = 1 }',
                                       'A separate non-suspend overload is not an implementation of the '
                                       'suspended callable; preserve any additional actual '
                                       'diagnostics.'),
 'NON_SUSPEND_OVERRIDDEN_BY_SUSPEND': ('interface Api { fun read(): Int }\n'
                                       'class Reader : Api { override suspend fun read(): Int = 1 }',
                                       'interface Api { fun read(): Int }\n'
                                       'class Reader : Api { override fun read(): Int = 1 }',
                                       'Independent suspend APIs are legitimate when they do not claim '
                                       'to override this non-suspend declaration.'),
 'NON_FINAL_MEMBER_IN_FINAL_CLASS': ('class Reader { open fun read(): Int = 1 }',
                                     'open class Reader { open fun read(): Int = 1 }',
                                     'Open members may be deliberate for a future hierarchy or an '
                                     'all-open plugin; a genuine warning is advisory and intent does '
                                     'not require absence.'),
 'PARAMETER_NAME_CHANGED_ON_OVERRIDE': ('interface Api { fun read(input: Int): Int }\n'
                                        'class Reader : Api { override fun read(value: Int): Int = '
                                        'value }',
                                        'interface Api { fun read(input: Int): Int }\n'
                                        'class Reader : Api { override fun read(input: Int): Int = '
                                        'input }',
                                        'A deliberate descriptive parameter rename can still warn '
                                        'because named-call compatibility matters; retain the warning '
                                        'without inferring an unintended bug.'),
 'INVISIBLE_REFERENCE': ('class Vault { private fun secret(): Int = 1 }\n'
                         'fun read(): Int = Vault().secret()',
                         'class Vault { fun secret(): Int = 1 }\nfun read(): Int = Vault().secret()',
                         'Calls from an actual authorized lexical owner are valid; wrong owner/module '
                         'membership cannot be substituted by a source-name heuristic.'),
 'INVISIBLE_SETTER': ('class Vault { var value: Int = 0\n'
                      '    private set\n'
                      '}\n'
                      'fun write(vault: Vault) { vault.value = 1 }',
                      'class Vault { var value: Int = 0 }\nfun write(vault: Vault) { vault.value = 1 }',
                      'A readable public property can intentionally expose only a private setter; reads '
                      'and internal owner writes are legitimate controls.'),
 'EXPOSED_PARAMETER_TYPE': ('private class Hidden\nfun read(value: Hidden): Int = value.hashCode()',
                            'private class Hidden\n'
                            'private fun read(value: Hidden): Int = value.hashCode()',
                            'Private/internal implementation APIs need not expose a type to a broader '
                            'audience; the exact declaration visibility is authoritative.'),
 'EXPOSED_PROPERTY_TYPE': ('private class Hidden\nval exposed: Hidden = Hidden()',
                           'private class Hidden\nprivate val exposed: Hidden = Hidden()',
                           'A private property may legitimately retain an implementation-only type '
                           'without a public exposure diagnostic.'),
 'EXPOSED_SUPER_CLASS': ('private open class Hidden\nclass Reader : Hidden()',
                         'private open class Hidden\nprivate class Reader : Hidden()',
                         'A non-public subclass or an actually public resolved superclass is '
                         'legitimate; no hierarchy is inferred from missing dependency artifacts.'),
 'EXPOSED_RECEIVER_TYPE': ('private class Hidden\nfun Hidden.read(): Int = 1',
                           'private class Hidden\nprivate fun Hidden.read(): Int = 1',
                           'Private extension receivers may be implementation-only; lowering extension '
                           'visibility is a real compiler-approved counterpart.'),
 'EXPOSED_TYPE_PARAMETER_BOUND': ('private interface Hidden\nclass Reader<T : Hidden>',
                                  'private interface Hidden\nprivate class Reader<T : Hidden>',
                                  'Bounds on non-exported generic APIs are legitimate; a public '
                                  'interface with a semantically broader bound is another allowed '
                                  'design.'),
 'EXPOSED_TYPEALIAS_EXPANDED_TYPE': ('private class Hidden\ntypealias Reader = Hidden',
                                     'private class Hidden\nprivate typealias Reader = Hidden',
                                     'A private implementation typealias is legitimate; aliases are '
                                     'resolved by the compiler rather than a name-pattern check.'),
 'CONDITION_TYPE_MISMATCH': ('fun read(): Int = if (1) 1 else 2',
                             'fun read(): Int = if (true) 1 else 2',
                             'A numeric test must express its real predicate such as value != 0; no '
                             'C-style truthiness exemption is invented.'),
 'THROWABLE_TYPE_MISMATCH': ('fun fail(): Nothing { throw "failure" }',
                             'fun fail(): Nothing { throw IllegalStateException("failure") }',
                             'A real Throwable subclass is valid; deliberate invalid compile-negative '
                             'source remains a failing compiler task.'),
 'ITERATOR_MISSING': ('class Values\nfun read(values: Values) { for (value in values) println(value) }',
                      'class Values { operator fun iterator(): Iterator<Int> = listOf(1).iterator() }\n'
                      'fun read(values: Values) { for (value in values) println(value) }',
                      'A properly resolved operator extension iterator is also valid; omitted '
                      'extension/dependency files make the context incomplete, not a source-level '
                      'defect proof.'),
 'COMPONENT_FUNCTION_MISSING': ('class PairLike(val first: Int, val second: Int)\n'
                                'fun read(value: PairLike): Int { val (first, second) = value; return '
                                'first + second }',
                                'data class PairLike(val first: Int, val second: Int)\n'
                                'fun read(value: PairLike): Int { val (first, second) = value; return '
                                'first + second }',
                                'User-defined component operators or unused underscore components can '
                                'be valid; only actual compiler resolution determines needed component '
                                'functions.'),
 'NO_GET_METHOD': ('class Values\nfun read(values: Values): Int = values[0]',
                   'class Values { operator fun get(index: Int): Int = index }\n'
                   'fun read(values: Values): Int = values[0]',
                   'An applicable operator extension or a normal method call is a legitimate '
                   'alternative; missing source-set imports cannot be treated as a compiler exemption.'),
 'NO_SET_METHOD': ('class Values { operator fun get(index: Int): Int = index }\n'
                   'fun write(values: Values) { values[0] = 1 }',
                   'class Values { operator fun get(index: Int): Int = index\n'
                   '    operator fun set(index: Int, value: Int) { println(index + value) }\n'
                   '}\n'
                   'fun write(values: Values) { values[0] = 1 }',
                   'A read-only indexed API intentionally has no setter; reads are legitimate and a '
                   'deliberate attempted write still stays an actual compiler error.'),
 'UNCHECKED_CAST': ('fun strings(value: Any): List<String> = value as List<String>',
                    'fun values(value: Any): List<*> = value as List<*>',
                    'An externally validated generic invariant can make the cast intentional, but '
                    'erasure still genuinely warns; retain advisory intent rather than require no '
                    'warning.'),
 'USELESS_IS_CHECK': ('fun isText(value: String): Boolean = value is String',
                      'fun isText(value: Any): Boolean = value is String',
                      'Uniform generic scaffolding can intentionally retain an always-true is check; '
                      'actual warning transport does not prove an unintended behavior change.'),
 'USELESS_ELVIS': ('fun text(value: String): String = value ?: "fallback"',
                   'fun text(value: String?): String = value ?: "fallback"',
                   'A redundant fallback may be deliberately retained for a future nullable contract; '
                   'genuine advisory warnings remain visible.'),
 'SMARTCAST_IMPOSSIBLE': ('class Reader(var text: String?)\n'
                          'fun length(reader: Reader): Int = if (reader.text != null) '
                          'reader.text.length else 0',
                          'class Reader(var text: String?)\n'
                          'fun length(reader: Reader): Int { val text = reader.text; return if (text != '
                          'null) text.length else 0 }',
                          'A stable local snapshot is a real control; concurrent mutation or a custom '
                          'getter cannot be guessed stable from a preceding null check.'),
 'EQUALITY_NOT_APPLICABLE': ('fun same(text: String, number: Int): Boolean = text == number',
                             'fun same(first: Any, second: Any): Boolean = first == second',
                             'Explicitly widening both operands to Any permits a deliberately '
                             'heterogeneous equality contract; no universal ban on cross-type names is '
                             'introduced.'),
 'RECURSION_IN_INLINE': ('inline fun repeatAction(action: () -> Unit) { action(); repeatAction(action) '
                         '}',
                         'inline fun repeatAction(action: () -> Unit) { action() }',
                         'A non-inline recursive function is legitimate; the pair retains an actually '
                         'used lambda to avoid unrelated NOTHING_TO_INLINE output.'),
 'NON_PUBLIC_CALL_FROM_PUBLIC_INLINE': ('private fun hiddenValue(): Int = 1\n'
                                        'inline fun read(action: (Int) -> Int): Int = '
                                        'action(hiddenValue())',
                                        '@PublishedApi internal fun hiddenValue(): Int = 1\n'
                                        'inline fun read(action: (Int) -> Int): Int = '
                                        'action(hiddenValue())',
                                        'PublishedApi internal helpers or a non-public/non-inline '
                                        'caller can be legitimate; the selected public inline API must '
                                        'not expose inaccessible bytecode.'),
 'INLINE_PROPERTY_WITH_BACKING_FIELD': ('inline val answer: Int = 1',
                                        'inline val answer: Int get() = 1',
                                        'An inline computed accessor with no field is legitimate; a '
                                        'normal non-inline stored property is also an allowed design.'),
 'CYCLIC_CONSTRUCTOR_DELEGATION_CALL': ('class Reader {\n'
                                        '    constructor() : this(1)\n'
                                        '    constructor(value: Int) : this()\n'
                                        '}',
                                        'class Reader {\n'
                                        '    constructor() : this(1)\n'
                                        '    constructor(value: Int) { println(value) }\n'
                                        '}',
                                        'An acyclic constructor chain is legitimate; deliberately '
                                        'cyclic compile-negative source remains a failed task rather '
                                        'than report suppression.'),
 'SUPERTYPE_NOT_INITIALIZED': ('open class Base\nclass Reader : Base',
                               'open class Base\nclass Reader : Base()',
                               'Interfaces do not need constructor calls; secondary-constructor '
                               'initialization is another legitimate graph that must be resolved by the '
                               'actual compiler.'),
 'OPT_IN_USAGE': ('@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)\n'
                  '@Retention(AnnotationRetention.BINARY)\n'
                  '@RequiresOptIn(level = RequiresOptIn.Level.WARNING)\n'
                  'annotation class ExperimentalContract\n'
                  '@ExperimentalContract fun experimental(): Int = 1\n'
                  'fun read(): Int = experimental()',
                  '@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)\n'
                  '@Retention(AnnotationRetention.BINARY)\n'
                  '@RequiresOptIn(level = RequiresOptIn.Level.WARNING)\n'
                  'annotation class ExperimentalContract\n'
                  '@ExperimentalContract fun experimental(): Int = 1\n'
                  '@OptIn(ExperimentalContract::class) fun read(): Int = experimental()',
                  'A deliberately experimental caller can propagate the marker or explicitly opt in; an '
                  'unacknowledged warning may still be advisory and is not automatically unintended.'),
 'OPT_IN_USAGE_ERROR': ('@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)\n'
                        '@Retention(AnnotationRetention.BINARY)\n'
                        '@RequiresOptIn(level = RequiresOptIn.Level.ERROR)\n'
                        'annotation class ExperimentalContract\n'
                        '@ExperimentalContract fun experimental(): Int = 1\n'
                        'fun read(): Int = experimental()',
                        '@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)\n'
                        '@Retention(AnnotationRetention.BINARY)\n'
                        '@RequiresOptIn(level = RequiresOptIn.Level.ERROR)\n'
                        'annotation class ExperimentalContract\n'
                        '@ExperimentalContract fun experimental(): Int = 1\n'
                        '@OptIn(ExperimentalContract::class) fun read(): Int = experimental()',
                        'An explicit OptIn or marker propagation is legitimate under the selected '
                        'contract; a failing error-level usage cannot be made clean by a baseline.'),
 'OPT_IN_WITHOUT_ARGUMENTS': ('@OptIn() fun read(): Int = 1',
                              '@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)\n'
                              '@Retention(AnnotationRetention.BINARY)\n'
                              '@RequiresOptIn annotation class ExperimentalContract\n'
                              '@OptIn(ExperimentalContract::class) fun read(): Int = 1',
                              'An empty annotation may be intentional scaffolding but genuinely has no '
                              'effect; removing it is another valid no-emission control.'),
 'OPT_IN_ARGUMENT_IS_NOT_MARKER': ('annotation class Plain\n@OptIn(Plain::class) fun read(): Int = 1',
                                   '@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)\n'
                                   '@Retention(AnnotationRetention.BINARY)\n'
                                   '@RequiresOptIn annotation class Plain\n'
                                   '@OptIn(Plain::class) fun read(): Int = 1',
                                   'Ordinary annotations can be legitimate annotations but are not '
                                   'opt-in markers; use them normally or make a real RequiresOptIn '
                                   'contract.'),
 'ILLEGAL_JVM_NAME': ('@JvmName("bad/name") fun read(): Int = 1',
                      '@JvmName("validName") fun read(): Int = 1',
                      'Kotlin source backtick names and JVM symbol names have different contracts; no '
                      'naive source-identifier restriction is inferred.'),
 'INAPPLICABLE_JVM_NAME': ('open class Base { open fun read(): Int = 1 }\n'
                           'class Reader : Base() { @JvmName("renamed") override fun read(): Int = 2 }',
                           'open class Base { open fun read(): Int = 1 }\n'
                           'class Reader : Base() { override fun read(): Int = 2 }',
                           'JvmName on supported top-level/non-overriding declarations is legitimate; '
                           'overriding JVM dispatch must keep the applicable inherited name.'),
 'OVERLOADS_PRIVATE': ('@JvmOverloads private fun read(value: Int = 1): Int = value\n'
                       'fun use(): Int = read()',
                       '@JvmOverloads fun read(value: Int = 1): Int = value\nfun use(): Int = read()',
                       'A private Kotlin helper may intentionally retain a no-effect annotation; the '
                       'genuine warning is advisory, not a Java API defect inferred from intent.'),
 'OVERLOADS_LOCAL': ('fun use(): Int { @JvmOverloads fun read(value: Int = 1): Int = value; return '
                     'read() }',
                     '@JvmOverloads fun read(value: Int = 1): Int = value\nfun use(): Int = read()',
                     'A local helper with Kotlin defaults is legitimate without JvmOverloads; the '
                     'corrected counterpart instead makes a real top-level Java-facing API.'),
 'JVM_STATIC_ON_CONST_OR_JVM_FIELD': ('object Reader { @JvmStatic const val answer: Int = 1 }',
                                      'object Reader { const val answer: Int = 1 }',
                                      'Const/JvmField properties already expose fields; removing the '
                                      'redundant static annotation preserves the intended valid JVM '
                                      'API.'),
 'JVM_RECORD_EXTENDS_CLASS': ('open class Base\n@JvmRecord data class Reader(val value: Int) : Base()',
                              'interface Api\n@JvmRecord data class Reader(val value: Int) : Api',
                              'Record interface implementation is legitimate; an explicit class '
                              'supertype cannot be silently accepted even if it is Any.'),
 'DELEGATION_BY_IN_JVM_RECORD': ('interface Api { fun read(): Int }\n'
                                 '@JvmRecord data class Reader(val source: Api) : Api by source',
                                 'interface Api { fun read(): Int }\n'
                                 '@JvmRecord data class Reader(val source: Api) : Api { override fun '
                                 'read(): Int = source.read() }',
                                 'Explicit forwarding without generated delegation storage is '
                                 'legitimate; non-record classes may validly use delegation.'),
 'EXTERNAL_DECLARATION_IN_INTERFACE': ('interface Api { val value: Int\n    external get\n}',
                                       'interface Api { val value: Int }',
                                       'A bodyless ordinary interface property is legitimate; an '
                                       'external native implementation belongs to a supported '
                                       'non-interface declaration context.'),
 'CONCURRENT_HASH_MAP_CONTAINS_OPERATOR_ERROR': ('fun hasKey(values: '
                                                 'java.util.concurrent.ConcurrentHashMap<String, Int>, '
                                                 'key: String): Boolean = key in values',
                                                 'fun hasKey(values: '
                                                 'java.util.concurrent.ConcurrentHashMap<String, Int>, '
                                                 'key: String): Boolean = values.containsKey(key)',
                                                 'Explicit containsValue/contains calls can '
                                                 'intentionally test values; an actual Map-typed '
                                                 'receiver has key membership semantics. Preserve the '
                                                 'compiler contract, not an inferred policy exemption.')}

PLANNED_KMP_CASES = {'EXPECTED_DECLARATION_WITH_BODY': ('expect fun answer(): Int = 1',
                                    'actual fun answer(): Int = 1',
                                    'expect fun answer(): Int',
                                    'actual fun answer(): Int = 1',
                                    'A bodyless expect with the implementation in its actual fragment '
                                    'is valid; this is not demonstrated by placing both files in one '
                                    'module.'),
 'EXPECTED_PROPERTY_INITIALIZER': ('expect val answer: Int = 1',
                                   'actual val answer: Int = 1',
                                   'expect val answer: Int',
                                   'actual val answer: Int = 1',
                                   'An actual property initializer is legitimate; an expected common '
                                   'property describes a contract without storage.'),
 'EXPECTED_PRIVATE_DECLARATION': ('private expect fun answer(): Int',
                                  'actual fun answer(): Int = 1',
                                  'expect fun answer(): Int',
                                  'actual fun answer(): Int = 1',
                                  'A private ordinary common helper is legitimate; a cross-fragment '
                                  'expected contract uses accessible matching declarations. Preserve '
                                  'any actual positive visibility/matching diagnostics.'),
 'EXPECTED_LATEINIT_PROPERTY': ('expect lateinit var text: String',
                                'actual var text: String = "text"',
                                'expect var text: String',
                                'actual var text: String = "text"',
                                'The actual implementation may own storage/initialization; common '
                                'expect declarations must not claim lateinit storage semantics.'),
 'EXPECTED_DELEGATED_PROPERTY': ('expect val answer: Int by lazy { 1 }',
                                 'actual val answer: Int = 1',
                                 'expect val answer: Int',
                                 'actual val answer: Int = 1',
                                 'Property delegation belongs to the implementation; a bodyless common '
                                 'expect with an ordinary actual initializer is a genuine counterpart.'),
 'EXPECTED_CLASS_CONSTRUCTOR_PROPERTY_PARAMETER': ('expect class Reader(val value: Int)',
                                                   'actual class Reader actual constructor(value: Int) '
                                                   '{ actual val value: Int = value }',
                                                   'expect class Reader(value: Int) { val value: Int }',
                                                   'actual class Reader actual constructor(value: Int) '
                                                   '{ actual val value: Int = value }',
                                                   'Separate expected constructor parameters and '
                                                   'expected properties are legitimate; an actual class '
                                                   'may implement storage in its body.'),
 'SUPERTYPE_INITIALIZED_IN_EXPECTED_CLASS': ('open class Base\nexpect class Reader : Base()',
                                             'actual class Reader : Base()',
                                             'open class Base\nexpect class Reader : Base',
                                             'actual class Reader : Base()',
                                             'Superclass initialization belongs to the actual class; '
                                             'common non-expect support classes are not falsely omitted '
                                             'from the fragment graph.'),
 'IMPLEMENTATION_BY_DELEGATION_IN_EXPECT_CLASS': ('interface Api { fun read(): Int }\n'
                                                  'expect class Reader(source: Api) : Api by source',
                                                  'actual class Reader actual constructor(source: Api) '
                                                  ': Api by source',
                                                  'interface Api { fun read(): Int }\n'
                                                  'expect class Reader(source: Api) : Api',
                                                  'actual class Reader actual constructor(source: Api) '
                                                  ': Api by source',
                                                  'Delegation in the actual implementation is a '
                                                  'legitimate control; inherited/fake-override '
                                                  'actualization must be checked by the pinned matching '
                                                  'semantics.'),
 'ACTUAL_MISSING': ('expect class Reader { fun read(): Int }',
                    'class Reader { actual fun read(): Int = 1 }',
                    'expect class Reader { fun read(): Int }',
                    'actual class Reader { actual fun read(): Int = 1 }',
                    'The matching class must be actual, but legitimate inherited fake-override members '
                    'are not required to repeat actual mechanically.'),
 'ACTUAL_WITHOUT_EXPECT': ('fun commonAnchor(): Int = 0',
                           'actual fun platformOnly(): Int = 1',
                           'fun commonAnchor(): Int = 0',
                           'fun platformOnly(): Int = 1',
                           'Additional platform-only ordinary declarations are legitimate and do not '
                           'require an expect; the invalid control alone claims actual.'),
 'ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS': ('expect fun read(value: Int = 1): Int',
                                            'actual fun read(value: Int = 1): Int = value',
                                            'expect fun read(value: Int = 1): Int',
                                            'actual fun read(value: Int): Int = value\n'
                                            'fun use(): Int = read()',
                                            'Defaults owned by the expect remain usable in the platform '
                                            'compilation; actual declarations must not redeclare them.'),
 'ACTUAL_TYPE_ALIAS_TO_NULLABLE_TYPE': ('expect class Reader',
                                        'actual typealias Reader = String?',
                                        'expect class Reader',
                                        'actual typealias Reader = String',
                                        'A supported non-null actual class typealias can be valid; '
                                        'ordinary nullable aliases without an expect/actual claim '
                                        'remain legitimate.'),
 'EXPECT_ACTUAL_INCOMPATIBLE_RETURN_TYPE': ('expect fun read(): String',
                                            'actual fun read(): Int = 1',
                                            'expect fun read(): String',
                                            'actual fun read(): String = "text"',
                                            'Same-name functions with unrelated return contracts are '
                                            'not actual matches; target-specific ordinary overloads may '
                                            'still be legitimate without actual.'),
 'EXPECT_ACTUAL_INCOMPATIBLE_PARAMETER_NAMES': ('expect fun read(input: String): String',
                                                'actual fun read(text: String): String = text',
                                                'expect fun read(input: String): String',
                                                'actual fun read(input: String): String = input',
                                                'Parameter-name compatibility protects expected named '
                                                'calls; inherited/fake overrides need separately '
                                                'reviewed pinned exceptions rather than blanket name '
                                                'matching.'),
 'EXPECT_ACTUAL_INCOMPATIBLE_PROPERTY_KIND': ('expect var answer: Int',
                                              'actual val answer: Int = 1',
                                              'expect var answer: Int',
                                              'actual var answer: Int = 1',
                                              'An actual var for an expected val is legitimate '
                                              'capability widening; this candidate tests the prohibited '
                                              'read-only actual for a mutable contract.'),
 'NO_ACTUAL_FOR_EXPECT': ('expect class Reader',
                          'fun platformAnchor(): Int = 1',
                          'expect class Reader',
                          'actual class Reader',
                          'Missing actualization must be observed through FIR2IR in the selected final '
                          'JVM fragment; a common-only metadata compilation does not require every '
                          'final-target actual.')}

COHORTS = {'ABSTRACT_MEMBER_NOT_IMPLEMENTED': 'overrides',
 'ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED': 'overrides',
 'NOTHING_TO_OVERRIDE': 'overrides',
 'OVERRIDING_FINAL_MEMBER': 'overrides',
 'CANNOT_WEAKEN_ACCESS_PRIVILEGE': 'overrides',
 'RETURN_TYPE_MISMATCH_ON_OVERRIDE': 'overrides',
 'PROPERTY_TYPE_MISMATCH_ON_OVERRIDE': 'overrides',
 'VAR_OVERRIDDEN_BY_VAL': 'overrides',
 'VIRTUAL_MEMBER_HIDDEN': 'overrides',
 'SUSPEND_OVERRIDDEN_BY_NON_SUSPEND': 'overrides',
 'NON_SUSPEND_OVERRIDDEN_BY_SUSPEND': 'overrides',
 'NON_FINAL_MEMBER_IN_FINAL_CLASS': 'overrides',
 'PARAMETER_NAME_CHANGED_ON_OVERRIDE': 'overrides',
 'INVISIBLE_REFERENCE': 'visibility',
 'INVISIBLE_SETTER': 'visibility',
 'EXPOSED_PARAMETER_TYPE': 'visibility',
 'EXPOSED_PROPERTY_TYPE': 'visibility',
 'EXPOSED_SUPER_CLASS': 'visibility',
 'EXPOSED_RECEIVER_TYPE': 'visibility',
 'EXPOSED_TYPE_PARAMETER_BOUND': 'visibility',
 'EXPOSED_TYPEALIAS_EXPANDED_TYPE': 'visibility',
 'CONDITION_TYPE_MISMATCH': 'conventions_types_nullability',
 'THROWABLE_TYPE_MISMATCH': 'conventions_types_nullability',
 'ITERATOR_MISSING': 'conventions_types_nullability',
 'COMPONENT_FUNCTION_MISSING': 'conventions_types_nullability',
 'NO_GET_METHOD': 'conventions_types_nullability',
 'NO_SET_METHOD': 'conventions_types_nullability',
 'UNCHECKED_CAST': 'conventions_types_nullability',
 'USELESS_IS_CHECK': 'conventions_types_nullability',
 'USELESS_ELVIS': 'conventions_types_nullability',
 'SMARTCAST_IMPOSSIBLE': 'conventions_types_nullability',
 'EQUALITY_NOT_APPLICABLE': 'conventions_types_nullability',
 'RECURSION_IN_INLINE': 'inline_constructors',
 'NON_PUBLIC_CALL_FROM_PUBLIC_INLINE': 'inline_constructors',
 'INLINE_PROPERTY_WITH_BACKING_FIELD': 'inline_constructors',
 'CYCLIC_CONSTRUCTOR_DELEGATION_CALL': 'inline_constructors',
 'SUPERTYPE_NOT_INITIALIZED': 'inline_constructors',
 'OPT_IN_USAGE': 'opt_in',
 'OPT_IN_USAGE_ERROR': 'opt_in',
 'OPT_IN_WITHOUT_ARGUMENTS': 'opt_in',
 'OPT_IN_ARGUMENT_IS_NOT_MARKER': 'opt_in',
 'ILLEGAL_JVM_NAME': 'jvm_record_external',
 'INAPPLICABLE_JVM_NAME': 'jvm_record_external',
 'OVERLOADS_PRIVATE': 'jvm_record_external',
 'OVERLOADS_LOCAL': 'jvm_record_external',
 'JVM_STATIC_ON_CONST_OR_JVM_FIELD': 'jvm_record_external',
 'JVM_RECORD_EXTENDS_CLASS': 'jvm_record_external',
 'DELEGATION_BY_IN_JVM_RECORD': 'jvm_record_external',
 'EXTERNAL_DECLARATION_IN_INTERFACE': 'jvm_record_external',
 'CONCURRENT_HASH_MAP_CONTAINS_OPERATOR_ERROR': 'jvm_record_external',
 'EXPECTED_DECLARATION_WITH_BODY': 'kmp_declarations',
 'EXPECTED_PROPERTY_INITIALIZER': 'kmp_declarations',
 'EXPECTED_PRIVATE_DECLARATION': 'kmp_declarations',
 'EXPECTED_LATEINIT_PROPERTY': 'kmp_declarations',
 'EXPECTED_DELEGATED_PROPERTY': 'kmp_declarations',
 'EXPECTED_CLASS_CONSTRUCTOR_PROPERTY_PARAMETER': 'kmp_declarations',
 'SUPERTYPE_INITIALIZED_IN_EXPECTED_CLASS': 'kmp_declarations',
 'IMPLEMENTATION_BY_DELEGATION_IN_EXPECT_CLASS': 'kmp_declarations',
 'ACTUAL_MISSING': 'kmp_matching',
 'ACTUAL_WITHOUT_EXPECT': 'kmp_matching',
 'ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS': 'kmp_matching',
 'ACTUAL_TYPE_ALIAS_TO_NULLABLE_TYPE': 'kmp_matching',
 'EXPECT_ACTUAL_INCOMPATIBLE_RETURN_TYPE': 'kmp_matching',
 'EXPECT_ACTUAL_INCOMPATIBLE_PARAMETER_NAMES': 'kmp_matching',
 'EXPECT_ACTUAL_INCOMPATIBLE_PROPERTY_KIND': 'kmp_matching',
 'NO_ACTUAL_FOR_EXPECT': 'kmp_matching'}
