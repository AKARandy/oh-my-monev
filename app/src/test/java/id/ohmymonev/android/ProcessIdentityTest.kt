package id.ohmymonev.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessIdentityTest {
    private val noName: () -> String? = { null }

    @Test fun api26UsesProcNameThenActivityManagerPidFallback() {
        assertEquals("id.ohmymonev.android", ProcessIdentity.lookup(
            sdk = 26,
            processName = noName,
            procCmdline = { "id.ohmymonev.android" },
            processForPid = { "wrong" },
        ))
        assertEquals("id.ohmymonev.android", ProcessIdentity.lookup(
            sdk = 26,
            processName = noName,
            procCmdline = { throw SecurityException() },
            processForPid = { "id.ohmymonev.android" },
        ))
    }

    @Test fun api27FailureLeavesProcessUnknownAndSkipsApplicationAutomation() {
        val name = ProcessIdentity.lookup(
            sdk = 27,
            processName = noName,
            procCmdline = { throw SecurityException() },
            processForPid = { throw SecurityException() },
        )
        assertNull(name)
        assertFalse(ProcessIdentity.isMain(name, "id.ohmymonev.android"))
        assertTrue(ProcessIdentity.isMain(name, "id.ohmymonev.android", allowUnknown = true))
    }

    @Test fun diagnosticsAndOtherProcessesNeverStartAutomation() {
        val diagnostics = "id.ohmymonev.android:diagnostics"
        assertTrue(ProcessIdentity.isDiagnostics(diagnostics))
        assertFalse(ProcessIdentity.isMain(diagnostics, "id.ohmymonev.android"))
        assertFalse(ProcessIdentity.isMain(diagnostics, "id.ohmymonev.android", allowUnknown = true))
        assertFalse(ProcessIdentity.isMain("id.ohmymonev.android:other", "id.ohmymonev.android", allowUnknown = true))
    }

    @Test fun api28UsesThePlatformProcessName() {
        assertEquals("id.ohmymonev.android", ProcessIdentity.lookup(
            sdk = 28,
            processName = { "id.ohmymonev.android" },
            procCmdline = { throw AssertionError("legacy lookup must not run") },
            processForPid = { throw AssertionError("legacy lookup must not run") },
        ))
    }
}
