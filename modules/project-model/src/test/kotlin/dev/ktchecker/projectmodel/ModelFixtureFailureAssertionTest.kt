package dev.ktchecker.projectmodel

import java.nio.file.Path
import kotlin.test.*
import org.opentest4j.TestAbortedException

/**
 * Inert assertion-boundary controls only. Every applicability probe and import action is injected;
 * no filesystem capability, model ingress, compiler or host/platform support is exercised here.
 * Real importer controls retain the existing anchored-read assumption and abort when unavailable.
 */
class ModelFixtureFailureAssertionTest {
    private val path = Path.of("synthetic-owned-fixture-root")

    @Test fun unsupportedAnchoredReadsAbortBeforeAnyModelAssertionOrImport() {
        val unavailable = TestAbortedException("Synthetic anchored-read unavailability; not a host result")
        var probes = 0
        var imports = 0
        val caught = assertFailsWith<TestAbortedException> {
            assertOwnerFixtureModelFailure(path, assumeAnchoredReads = { actual ->
                probes++
                assertEquals(path, actual)
                throw unavailable
            }) {
                imports++
                throw ProjectModelException("synthetic_model_rejection", "Must never enter this action")
            }
        }
        assertSame(unavailable, caught)
        assertEquals(1, probes)
        assertEquals(0, imports, "A missing fixture capability must abort before the domain assertion or import")
    }

    @Test fun availableAnchoredReadsKeepTheExactDomainFailureAndProbeOrder() {
        val order = mutableListOf<String>()
        val expected = ProjectModelException("synthetic_model_rejection", "Inert exact-domain failure")
        val caught = assertOwnerFixtureModelFailure(path, assumeAnchoredReads = { actual ->
            order += "probe"
            assertEquals(path, actual)
        }) {
            order += "import"
            throw expected
        }
        assertSame(expected, caught)
        assertEquals("synthetic_model_rejection", caught.code)
        assertEquals(listOf("probe", "import"), order)
    }

    @Test fun anUnexpectedSuccessfulImportIsStillAnAssertionFailure() {
        var probes = 0
        var imports = 0
        assertFailsWith<AssertionError> {
            assertOwnerFixtureModelFailure(path, assumeAnchoredReads = { probes++ }) {
                imports++
            }
        }
        assertEquals(1, probes)
        assertEquals(1, imports, "A successful action cannot satisfy the expected model rejection")
    }

    @Test fun anUnexpectedNonModelExceptionIsStillAnAssertionFailure() {
        val unexpected = IllegalArgumentException("Inert wrong exception, not a model rejection")
        var probes = 0
        var imports = 0
        assertFailsWith<AssertionError> {
            assertOwnerFixtureModelFailure(path, assumeAnchoredReads = { probes++ }) {
                imports++
                throw unexpected
            }
        }
        assertEquals(1, probes)
        assertEquals(1, imports, "The actual wrong exception must be tested, not replaced by a synthetic rejection")
    }

    @Test fun unexpectedApplicabilityErrorsPropagateWithoutEnteringImport() {
        val unexpected = IllegalStateException("Inert probe failure, not an unavailable-capability assumption")
        var probes = 0
        var imports = 0
        val caught = assertFailsWith<IllegalStateException> {
            assertOwnerFixtureModelFailure(path, assumeAnchoredReads = { actual ->
                probes++
                assertEquals(path, actual)
                throw unexpected
            }) {
                imports++
                throw ProjectModelException("synthetic_model_rejection", "Must never enter this action")
            }
        }
        assertSame(unexpected, caught)
        assertEquals(1, probes)
        assertEquals(0, imports, "An unexpected probe error must propagate, not become a skip or domain rejection")
    }
}
