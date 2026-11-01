package com.faizannazir.cleanyourtv.tv

import com.faizannazir.cleanyourtv.tv.engine.BackupRestoreEngine
import com.faizannazir.cleanyourtv.tv.engine.CleanupContext
import com.faizannazir.cleanyourtv.tv.engine.CleanupEngine
import com.faizannazir.cleanyourtv.tv.engine.CommandRunner
import com.faizannazir.cleanyourtv.tv.engine.RestoreEngine
import com.faizannazir.cleanyourtv.tv.engine.SafetyRulesEngine
import com.faizannazir.cleanyourtv.tv.engine.ShellOutput
import com.faizannazir.cleanyourtv.tv.model.ActionType
import com.faizannazir.cleanyourtv.tv.model.AppCategory
import com.faizannazir.cleanyourtv.tv.model.AppInfo
import com.faizannazir.cleanyourtv.tv.model.CleanupBaseline
import com.faizannazir.cleanyourtv.tv.model.CleanupRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CleanupEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var backupDir: File

    @Before
    fun setUp() {
        backupDir = tempFolder.newFolder("backups")
    }

    @Test
    fun testBackupWriteFailureAbortsBeforeAnyTvMutation() {
        val notADirectory = tempFolder.newFile("cannot-create-backup-directory")
        val backup = BackupRestoreEngine(notADirectory)
        val shell = FakeShell()

        val outcome = CleanupEngine(SafetyRulesEngine(), backup, shell).executeCleanup(
            listOf(app("com.test.one")),
            context()
        )

        assertFalse(outcome.backupSaved)
        assertTrue(outcome.aborted)
        assertTrue(outcome.log, shell.commands.any { it == "cmd appops get com.test.one" })
        assertFalse(shell.commands.any { it.startsWith("cmd appops set") })
        assertFalse(shell.commands.any { it.startsWith("pm disable-user") })
    }

    @Test
    fun testCleanupDisablesOnlyExplicitlySelectedUnprotectedPackages() {
        val backup = BackupRestoreEngine(backupDir)
        val shell = FakeShell(
            enabled = listOf("com.test.one", "com.test.two", "com.android.settings")
        )

        val outcome = CleanupEngine(SafetyRulesEngine(), backup, shell).executeCleanup(
            listOf(
                app("com.test.one"),
                app("com.android.settings") // Protected, even if it was mislabelled OPTIONAL.
            ),
            context()
        )

        assertTrue(outcome.backupSaved)
        assertFalse(outcome.aborted)
        assertEquals(listOf("com.test.one"), outcome.disabledPackages)

        val packageMutations = shell.commands.filter {
            it.startsWith("pm disable-user") || it.startsWith("cmd appops set")
        }
        assertEquals(listOf("pm disable-user --user 0 com.test.one"), packageMutations)
        assertFalse(shell.commands.any { it.contains("com.test.two") && it != "pm list packages -e" })
        assertNoDestructiveCommands(shell.commands)
    }

    @Test
    fun testIncompleteAppOpsCaptureMarksRecordNonRestorableAndAborts() {
        val backup = BackupRestoreEngine(backupDir)
        val shell = FakeShell(
            appOps = mapOf("com.test.one" to "Error: SecurityException while reading app ops")
        )

        val outcome = CleanupEngine(SafetyRulesEngine(), backup, shell).executeCleanup(
            listOf(app("com.test.one")),
            context()
        )

        assertTrue(outcome.aborted)
        assertFalse(outcome.backupSaved)
        assertEquals(1, outcome.records.size)
        assertFalse(outcome.records[0].isRestorable)
        assertFalse(shell.commands.any { it.startsWith("pm disable-user") })
        assertFalse(shell.commands.any { it.startsWith("cmd appops set") })
    }

    @Test
    fun testInterruptedCleanupLeavesBaselineSufficientForRecovery() {
        val backup = BackupRestoreEngine(backupDir)
        val interruptedShell = FakeShell(
            appOps = mapOf(
                "com.test.one" to "RUN_IN_BACKGROUND: allow\nWAKE_LOCK: deny",
                "com.test.two" to "RUN_IN_BACKGROUND: foreground\nWAKE_LOCK: allow"
            ),
            interruptOn = { it.startsWith("pm disable-user") }
        )

        val outcome = CleanupEngine(SafetyRulesEngine(), backup, interruptedShell).executeCleanup(
            listOf(app("com.test.one"), app("com.test.two")),
            context()
        )

        assertTrue(outcome.aborted.not())
        assertTrue(outcome.backupSaved)

        val saved = backup.loadLatestBaseline()
        assertNotNull(saved)
        assertEquals(2, saved!!.records.size)
        assertTrue(saved.records.all { it.isRestorable })
        assertTrue(saved.records.all { it.previousState })
        assertTrue(saved.records.any { it.operationResult.contains("Interrupted") })
        assertTrue(saved.records.any { it.operationResult.contains("Not executed") })
        assertEquals(
            "allow",
            saved.records.first { it.packageName == "com.test.one" }
                .appOpsSnapshot()["RUN_IN_BACKGROUND"]
        )

        // A later restore can finish the recovery using only what the interrupted run left behind.
        val restoreShell = FakeShell()
        val restoreOutcome = RestoreEngine(SafetyRulesEngine(), backup, restoreShell)
            .restore(saved, "test-tv")

        assertFalse(restoreOutcome.interrupted)
        assertEquals(listOf("com.test.one", "com.test.two"), restoreOutcome.restoredPackages)
        assertTrue(restoreShell.commands.contains("pm enable com.test.one"))
        assertTrue(restoreShell.commands.contains("pm enable com.test.two"))
        assertTrue(
            restoreShell.commands.contains(
                "cmd appops set com.test.one RUN_IN_BACKGROUND allow"
            )
        )
        assertTrue(
            restoreShell.commands.contains(
                "cmd appops set com.test.one WAKE_LOCK deny"
            )
        )
        assertNoDestructiveCommands(restoreShell.commands)
    }

    @Test
    fun testRepeatedCleanupNeverOverwritesFirstCapturedState() {
        val backup = BackupRestoreEngine(backupDir)
        val firstShell = FakeShell(
            appOps = mapOf("com.test.one" to "RUN_IN_BACKGROUND: allow\nWAKE_LOCK: allow")
        )
        CleanupEngine(SafetyRulesEngine(), backup, firstShell).executeCleanup(
            listOf(app("com.test.one")),
            context()
        )

        val retryShell = FakeShell(
            appOps = mapOf("com.test.one" to "RUN_IN_BACKGROUND: deny\nWAKE_LOCK: deny")
        )
        CleanupEngine(SafetyRulesEngine(), backup, retryShell).executeCleanup(
            listOf(app("com.test.one")),
            context()
        )

        val saved = backup.loadLatestBaseline()!!
        val record = saved.records.first { it.packageName == "com.test.one" }
        assertTrue(record.previousState)
        assertEquals("allow", record.appOpsSnapshot()["RUN_IN_BACKGROUND"])
        assertEquals("allow", record.appOpsSnapshot()["WAKE_LOCK"])
    }

    @Test
    fun testAllFiveAppOpsModesRoundTripThroughSerializedBackup() {
        val backup = BackupRestoreEngine(backupDir)
        val modes = linkedMapOf(
            "RUN_IN_BACKGROUND" to "allow",
            "WAKE_LOCK" to "deny",
            "POST_NOTIFICATION" to "ignore",
            "CAMERA" to "default",
            "RECORD_AUDIO" to "foreground"
        )
        val captureShell = FakeShell(
            appOps = mapOf(
                "com.test.one" to modes.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            )
        )

        val outcome = CleanupEngine(SafetyRulesEngine(), backup, captureShell).executeCleanup(
            listOf(app("com.test.one")),
            context()
        )

        assertTrue(outcome.log, outcome.backupSaved)
        assertFalse(outcome.aborted)
        assertEquals(listOf("com.test.one"), outcome.disabledPackages)
        // Cleanup only disables packages; it never rewrites AppOps.
        assertFalse(captureShell.commands.any { it.startsWith("cmd appops set") })

        // Read the capture back from the real serialized file, not from the in-memory outcome.
        val saved = backup.loadLatestBaseline()
        assertNotNull(saved)
        val record = saved!!.records.first { it.packageName == "com.test.one" }
        assertTrue(record.isRestorable)
        assertEquals(modes, record.appOpsSnapshot())

        val restoreShell = FakeShell()
        val restored = RestoreEngine(SafetyRulesEngine(), backup, restoreShell).restore(saved, "test-tv")

        assertFalse(restored.interrupted)
        assertEquals(listOf("com.test.one"), restored.restoredPackages)
        assertTrue(restoreShell.commands.contains("pm enable com.test.one"))
        modes.forEach { (operation, mode) ->
            assertTrue(
                "missing restore of $operation=$mode: ${restoreShell.commands}",
                restoreShell.commands.contains("cmd appops set com.test.one $operation $mode")
            )
        }
        assertNoDestructiveCommands(restoreShell.commands)
    }

    @Test
    fun testNoOperationsCaptureIsRestorableAndRestoresNoAppOps() {
        val backup = BackupRestoreEngine(backupDir)
        val captureShell = FakeShell() // Unknown packages answer `No operations.`

        val outcome = CleanupEngine(SafetyRulesEngine(), backup, captureShell).executeCleanup(
            listOf(app("com.test.one")),
            context()
        )

        assertTrue(outcome.log, outcome.backupSaved)
        assertFalse(outcome.aborted)
        assertEquals(listOf("com.test.one"), outcome.disabledPackages)
        assertFalse(captureShell.commands.any { it.startsWith("cmd appops set") })

        val saved = backup.loadLatestBaseline()!!
        val record = saved.records.first { it.packageName == "com.test.one" }
        assertTrue(record.isRestorable)
        assertTrue(record.appOpsSnapshot().isEmpty())

        val restoreShell = FakeShell()
        val restored = RestoreEngine(SafetyRulesEngine(), backup, restoreShell).restore(saved)

        assertFalse(restored.interrupted)
        assertTrue(restoreShell.commands.contains("pm enable com.test.one"))
        assertFalse(restoreShell.commands.any { it.startsWith("cmd appops set") })
    }

    @Test
    fun testSuccessPrefixedAppOpsOutputIsParsed() {
        val backup = BackupRestoreEngine(backupDir)
        val shell = FakeShell(
            appOps = mapOf(
                "com.test.one" to "Success: RUN_IN_BACKGROUND: deny\nWAKE_LOCK: ignore"
            )
        )

        val outcome = CleanupEngine(SafetyRulesEngine(), backup, shell).executeCleanup(
            listOf(app("com.test.one")),
            context()
        )

        assertTrue(outcome.log, outcome.backupSaved)
        val saved = backup.loadLatestBaseline()!!
        assertEquals(
            mapOf("RUN_IN_BACKGROUND" to "deny", "WAKE_LOCK" to "ignore"),
            saved.records.first { it.packageName == "com.test.one" }.appOpsSnapshot()
        )

        val restoreShell = FakeShell()
        RestoreEngine(SafetyRulesEngine(), backup, restoreShell).restore(saved)

        assertTrue(restoreShell.commands.contains("cmd appops set com.test.one RUN_IN_BACKGROUND deny"))
        assertTrue(restoreShell.commands.contains("cmd appops set com.test.one WAKE_LOCK ignore"))
    }

    @Test
    fun testInheritedUidModeIsNotRecordedAsAPackageMode() {
        val backup = BackupRestoreEngine(backupDir)
        val shell = FakeShell(
            appOps = mapOf("com.test.one" to "Uid mode: RUN_IN_BACKGROUND: allow")
        )

        val outcome = CleanupEngine(SafetyRulesEngine(), backup, shell).executeCleanup(
            listOf(app("com.test.one")),
            context()
        )

        assertTrue(outcome.log, outcome.backupSaved)
        assertFalse(outcome.aborted)
        assertFalse(shell.commands.any { it.startsWith("cmd appops set") })

        val saved = backup.loadLatestBaseline()!!
        val record = saved.records.first { it.packageName == "com.test.one" }
        assertTrue(record.isRestorable)
        assertTrue(record.appOpsSnapshot().isEmpty())

        val restoreShell = FakeShell()
        RestoreEngine(SafetyRulesEngine(), backup, restoreShell).restore(saved)

        assertTrue(restoreShell.commands.contains("pm enable com.test.one"))
        assertFalse(restoreShell.commands.any { it.startsWith("cmd appops set") })
    }

    @Test
    fun testMixedUidAndPackageAppOpsOutputIsRefused() {
        assertCleanupRefusesCapture(
            "Uid mode: RUN_IN_BACKGROUND: allow\nWAKE_LOCK: deny"
        )
    }

    @Test
    fun testMalformedAppOpsModeIsRefused() {
        assertCleanupRefusesCapture("RUN_IN_BACKGROUND: allowed")
    }

    @Test
    fun testUnknownAppOpsOperationIsRefused() {
        assertCleanupRefusesCapture("Unknown(7): allow")
    }

    @Test
    fun testAmbiguousDuplicateAppOpsModesAreRefused() {
        assertCleanupRefusesCapture("RUN_IN_BACKGROUND: allow\nRUN_IN_BACKGROUND: deny")
    }

    @Test
    fun testEmptyAppOpsOutputIsRefused() {
        assertCleanupRefusesCapture("")
    }

    private fun assertCleanupRefusesCapture(rawAppOps: String) {
        val backup = BackupRestoreEngine(backupDir)
        val shell = FakeShell(appOps = mapOf("com.test.one" to rawAppOps))

        val outcome = CleanupEngine(SafetyRulesEngine(), backup, shell).executeCleanup(
            listOf(app("com.test.one")),
            context()
        )

        assertTrue(outcome.aborted)
        assertFalse(outcome.backupSaved)
        assertEquals(1, outcome.records.size)
        assertFalse(outcome.records[0].isRestorable)
        assertFalse(shell.commands.any { it.startsWith("pm disable-user") })
        assertFalse(shell.commands.any { it.startsWith("cmd appops set") })
        assertNull(backup.loadLatestBaseline())
    }

    @Test
    fun testRestoreUsesOriginalEnabledDisabledStateAndEveryAppOp() {
        val backup = BackupRestoreEngine(backupDir)
        val shell = FakeShell()
        val baseline = CleanupBaseline(
            timestamp = 1000L,
            deviceModel = "Test TV",
            androidVersion = "12",
            totalAppsScanned = 2,
            records = listOf(
                record(
                    "com.test.one",
                    previousState = true,
                    appOps = mapOf(
                        "RUN_IN_BACKGROUND" to "allow",
                        "WAKE_LOCK" to "deny",
                        "POST_NOTIFICATION" to "default"
                    )
                ),
                record(
                    "com.test.two",
                    previousState = false,
                    appOps = mapOf("RUN_IN_BACKGROUND" to "foreground")
                )
            )
        )

        val outcome = RestoreEngine(SafetyRulesEngine(), backup, shell).restore(baseline, "test-tv")

        assertFalse(outcome.interrupted)
        assertEquals(listOf("com.test.one", "com.test.two"), outcome.restoredPackages)
        assertTrue(shell.commands.contains("pm enable com.test.one"))
        assertTrue(shell.commands.contains("pm disable-user --user 0 com.test.two"))
        assertTrue(shell.commands.contains("cmd appops set com.test.one RUN_IN_BACKGROUND allow"))
        assertTrue(shell.commands.contains("cmd appops set com.test.one WAKE_LOCK deny"))
        assertTrue(shell.commands.contains("cmd appops set com.test.one POST_NOTIFICATION default"))
        assertTrue(shell.commands.contains("cmd appops set com.test.two RUN_IN_BACKGROUND foreground"))
        assertNoDestructiveCommands(shell.commands)
    }

    @Test
    fun testRestoreNeverDisablesProtectedPackageFromOldBaseline() {
        val backup = BackupRestoreEngine(backupDir)
        val shell = FakeShell()
        val baseline = CleanupBaseline(
            timestamp = 1000L,
            deviceModel = "Test TV",
            androidVersion = "12",
            totalAppsScanned = 1,
            records = listOf(
                record(
                    "com.android.settings",
                    previousState = false,
                    appOps = mapOf("RUN_IN_BACKGROUND" to "allow")
                )
            )
        )

        RestoreEngine(SafetyRulesEngine(), backup, shell).restore(baseline)

        assertFalse(shell.commands.any { it.startsWith("pm disable-user") })
        assertTrue(shell.commands.contains("cmd appops set com.android.settings RUN_IN_BACKGROUND allow"))
    }

    private fun context() = CleanupContext(
        deviceModel = "Test TV",
        androidVersion = "12",
        totalAppsScanned = 3,
        targetLabel = "test-tv"
    )

    private fun app(
        packageName: String,
        category: AppCategory = AppCategory.OPTIONAL,
        enabled: Boolean = true
    ) = AppInfo(
        packageName = packageName,
        label = packageName,
        isSystem = true,
        isEnabled = enabled,
        category = category
    )

    private fun record(
        packageName: String,
        previousState: Boolean,
        appOps: Map<String, String>
    ) = CleanupRecord(
        id = "id-$packageName",
        timestamp = 1000L,
        packageName = packageName,
        appLabel = packageName,
        previousState = previousState,
        previousAppOpsState = appOps["RUN_IN_BACKGROUND"] ?: "allow",
        previousAppOpsStates = appOps,
        isDataCleared = false,
        requestedAction = ActionType.DISABLE_USER,
        operationResult = "Disabled",
        isRestorable = true
    )

    private fun assertNoDestructiveCommands(commands: List<String>) {
        assertFalse(commands.any { it.contains("pm clear") })
        assertFalse(commands.any { it.contains("trim-caches") })
        assertFalse(commands.any { it.contains("pm uninstall") })
    }

    private class FakeShell(
        private val enabled: List<String> = listOf(
            "com.test.one",
            "com.test.two",
            "com.android.settings"
        ),
        private val disabled: List<String> = emptyList(),
        private val appOps: Map<String, String> = emptyMap(),
        private val interruptOn: ((String) -> Boolean)? = null
    ) : CommandRunner {

        val commands = mutableListOf<String>()

        override fun run(command: String): String {
            commands.add(command)
            if (interruptOn?.invoke(command) == true) {
                throw IOException("simulated interruption")
            }
            return when {
                command == "pm list packages -e" ->
                    enabled.joinToString("\n") { "package:$it" }
                command == "pm list packages -d" ->
                    if (disabled.isEmpty()) ShellOutput.EMPTY_OUTPUT_NOTICE
                    else disabled.joinToString("\n") { "package:$it" }
                command.startsWith("cmd appops get ") -> {
                    val packageName = command.removePrefix("cmd appops get ").trim()
                    appOps[packageName] ?: "No operations."
                }
                else -> "Success: ok"
            }
        }
    }
}
