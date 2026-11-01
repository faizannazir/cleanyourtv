package com.faizannazir.cleanyourtv.tv

import com.faizannazir.cleanyourtv.tv.engine.AppOpsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOpsSnapshotTest {

    @Test
    fun testParsesAllFiveModesAndStripsMetadata() {
        val parsed = AppOpsSnapshot.parse(
            "RUN_IN_BACKGROUND: allow; time=+1m2s ago\n" +
                    "WAKE_LOCK: deny\n" +
                    "POST_NOTIFICATION: ignore\n" +
                    "CAMERA: default; rejectTime=+3s ago\n" +
                    "RECORD_AUDIO: foreground (running)\n"
        )

        assertTrue(parsed.captured)
        assertEquals(
            mapOf(
                "RUN_IN_BACKGROUND" to "allow",
                "WAKE_LOCK" to "deny",
                "POST_NOTIFICATION" to "ignore",
                "CAMERA" to "default",
                "RECORD_AUDIO" to "foreground"
            ),
            parsed.states
        )
    }

    @Test
    fun testStripsTheAdbSuccessPrefix() {
        val parsed = AppOpsSnapshot.parse("Success: RUN_IN_BACKGROUND: deny\nWAKE_LOCK: ignore")

        assertTrue(parsed.captured)
        assertEquals("deny", parsed.states["RUN_IN_BACKGROUND"])
        assertEquals("ignore", parsed.states["WAKE_LOCK"])
    }

    @Test
    fun testNoOperationsIsACompleteEmptyCapture() {
        val parsed = AppOpsSnapshot.parse("No operations.")

        assertTrue(parsed.captured)
        assertTrue(parsed.states.isEmpty())
    }

    @Test
    fun testNoOperationsWithDefaultModeIsACompleteEmptyCapture() {
        val parsed = AppOpsSnapshot.parse("No operations.\nDefault mode: deny")

        assertTrue(parsed.captured)
        assertTrue(parsed.states.isEmpty())
    }

    @Test
    fun testSingleUidModeIsInheritedAndNotAPackageOverride() {
        val parsed = AppOpsSnapshot.parse("Uid mode: RUN_IN_BACKGROUND: allow")

        assertTrue(parsed.captured)
        assertTrue(parsed.states.isEmpty())
    }

    @Test
    fun testMixedUidAndPackageLinesAreAmbiguousAndRefused() {
        val parsed = AppOpsSnapshot.parse(
            "Uid mode: RUN_IN_BACKGROUND: allow\nWAKE_LOCK: deny"
        )

        assertFalse(parsed.captured)
        assertTrue(parsed.states.isEmpty())
    }

    @Test
    fun testEmptyOutputIsRefused() {
        listOf("", "   ", "\n\n", "Command transmitted to TV ADB daemon.").forEach { raw ->
            val parsed = AppOpsSnapshot.parse(raw)
            assertFalse("unexpected capture for '$raw'", parsed.captured)
            assertTrue(parsed.states.isEmpty())
        }
    }

    @Test
    fun testCommandFailureIsNotTreatedAsAnEmptyCapture() {
        listOf(
            "Error: java.lang.SecurityException: permission denial",
            "Exception occurred while executing 'get'",
            "Error: Unknown command: appops"
        ).forEach { raw ->
            val parsed = AppOpsSnapshot.parse(raw)
            assertFalse("unexpected capture for '$raw'", parsed.captured)
        }
    }

    @Test
    fun testUnknownOrMalformedModesAreRefused() {
        listOf(
            "RUN_IN_BACKGROUND: allowed",
            "RUN_IN_BACKGROUND: errored",
            "RUN_IN_BACKGROUND: ignored",
            "RUN_IN_BACKGROUND:",
            "RUN_IN_BACKGROUND: allow garbage",
            "RUN_IN_BACKGROUND allow"
        ).forEach { raw ->
            val parsed = AppOpsSnapshot.parse(raw)
            assertFalse("unexpected capture for '$raw'", parsed.captured)
            assertTrue(parsed.states.isEmpty())
        }
    }

    @Test
    fun testUnrecognisedOperationNamesAreRefused() {
        listOf(
            "Unknown(7): allow",
            "android:read_contacts: allow",
            "Package com.test.app:"
        ).forEach { raw ->
            val parsed = AppOpsSnapshot.parse(raw)
            assertFalse("unexpected capture for '$raw'", parsed.captured)
            assertTrue(parsed.states.isEmpty())
        }
    }

    @Test
    fun testConflictingDuplicateModesAreAmbiguousAndRefused() {
        val parsed = AppOpsSnapshot.parse("RUN_IN_BACKGROUND: allow\nRUN_IN_BACKGROUND: deny")

        assertFalse(parsed.captured)
        assertTrue(parsed.states.isEmpty())
    }

    @Test
    fun testContradictoryNoOperationsOutputIsRefused() {
        val parsed = AppOpsSnapshot.parse("No operations.\nRUN_IN_BACKGROUND: allow")

        assertFalse(parsed.captured)
        assertTrue(parsed.states.isEmpty())
    }

    @Test
    fun testEmptyModeMapIsNeverReportedAsCaptured() {
        // A response with only ignorable lines is not a snapshot.
        val parsed = AppOpsSnapshot.parse("Default mode: allow")

        assertFalse(parsed.captured)
        assertTrue(parsed.states.isEmpty())
    }
}
