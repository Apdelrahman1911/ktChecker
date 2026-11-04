package dev.ktchecker.cli

import dev.ktchecker.providers.runtime.OwnedSupervisorAvailability
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import kotlinx.serialization.json.*
import kotlin.test.*

class ContainmentAvailabilityTest {
    @Test fun matchingInstalledBytesDoNotClaimAnExecutionOrTargetPass() {
        val identity = OwnedSupervisorIdentity("ktchecker-owned-child-supervisor/preview-1", "linux-x86_64",
            "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64))
        val view = containmentAvailability(OwnedSupervisorAvailability("available", identity.target, identity))
        assertEquals("available", view.getValue("state").jsonPrimitive.content)
        assertTrue(view.getValue("bundledBytesVerified").jsonPrimitive.boolean)
        assertFalse(view.getValue("executionVerified").jsonPrimitive.boolean)
        assertFalse(view.getValue("releaseQualified").jsonPrimitive.boolean)
        assertEquals(identity.binaryDigest, view.getValue("identity").jsonObject.getValue("binaryDigest").jsonPrimitive.content)
    }

    @Test fun unsupportedHostIsAnAddressableGapNotAPassOrAFallback() {
        val view = containmentAvailability(OwnedSupervisorAvailability("unavailable", "windows-x86_64",
            reasons = listOf("process_group_containment_unavailable")))
        assertEquals("unavailable", view.getValue("state").jsonPrimitive.content)
        assertFalse(view.getValue("bundledBytesVerified").jsonPrimitive.boolean)
        assertFalse(view.getValue("executionVerified").jsonPrimitive.boolean)
        assertFalse(view.containsKey("identity"))
        assertEquals("process_group_containment_unavailable", view.getValue("reasons").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun malformedBundleFailureRemainsVisibleWithoutPrivateFilesystemPaths() {
        val view = containmentAvailability(OwnedSupervisorAvailability("unavailable", "linux-x86_64",
            reasons = listOf("process_supervisor_bundle_invalid")))
        assertEquals(listOf("process_supervisor_bundle_invalid"), view.getValue("reasons").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(view.getValue("bundledBytesVerified").jsonPrimitive.boolean)
        assertFalse(view.containsKey("identity"))
    }
}
