package fixture.emptyassert

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFailsWith as expectFailure

/**
 * REAL source input for a selected Kotlin2.4.21 + kotlin-test/JUnit5 compiler fixture, not a test
 * class automatically compiled by this module. These ordinary test entries intentionally FAIL
 * when actually run: each is a detector positive. The owner must select this exact test source
 * scope and genuine kotlin-test supplying artifact, not manufacture semantic facts from names.
 */
class OrdinaryEmptyTest {
    @Test fun missingAction() { assertFailsWith<IllegalArgumentException> { } }
    @Test fun commentsOnly() { assertFailsWith<IllegalArgumentException> { /* outer /* nested */ end */ } }
    @Test fun importedAlias() { expectFailure<IllegalArgumentException>() { } }
}
