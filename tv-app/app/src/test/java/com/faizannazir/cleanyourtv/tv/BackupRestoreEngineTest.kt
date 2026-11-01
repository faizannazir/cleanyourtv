package com.faizannazir.cleanyourtv.tv

import com.faizannazir.cleanyourtv.tv.engine.BackupRestoreEngine
import com.faizannazir.cleanyourtv.tv.engine.BackupState
import com.faizannazir.cleanyourtv.tv.engine.RestoreEngine
import com.faizannazir.cleanyourtv.tv.engine.SafetyRulesEngine
import com.faizannazir.cleanyourtv.tv.engine.CommandRunner
import com.faizannazir.cleanyourtv.tv.model.ActionType
import com.faizannazir.cleanyourtv.tv.model.BackupEnvelope
import com.faizannazir.cleanyourtv.tv.model.BackupTarget
import com.faizannazir.cleanyourtv.tv.model.CleanupBaseline
import com.faizannazir.cleanyourtv.tv.model.CleanupRecord
import org.junit.Assert.assertArrayEquals
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
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class BackupRestoreEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var backupDir: File

    private val tvA = BackupTarget.of("192.168.1.20", 5555)!!
    private val tvB = BackupTarget.of("192.168.1.21", 5555)!!
    private val tvAOnAnotherPort = BackupTarget.of("192.168.1.20", 5556)!!

    @Before
    fun setUp() {
        backupDir = tempFolder.newFolder("backups")
    }

    @Test
    fun testRestorabilityContract() {
        val restorableRecord = CleanupRecord(
            id = "1",
            timestamp = 1000L,
            packageName = "com.test.app",
            appLabel = "Test App",
            previousState = true,
            previousAppOpsState = "allow",
            previousAppOpsStates = mapOf("RUN_IN_BACKGROUND" to "allow"),
            isDataCleared = false,
            requestedAction = ActionType.DISABLE_USER,
            operationResult = "Success",
            isRestorable = true
        )
        assertTrue(restorableRecord.isRestorable)
        assertTrue(restorableRecord.appOpsSnapshot().containsKey("RUN_IN_BACKGROUND"))

        val incompleteRecord = CleanupRecord(
            id = "2",
            timestamp = 1000L,
            packageName = "com.test.app2",
            appLabel = "Test App 2",
            previousState = true,
            previousAppOpsState = "allow",
            previousAppOpsStates = null,
            isDataCleared = false,
            requestedAction = ActionType.DISABLE_USER,
            operationResult = "AppOps capture failed",
            appOpsCaptured = false
        )
        assertFalse(incompleteRecord.isRestorable)

        val legacyDataWipedRecord = CleanupRecord(
            id = "3",
            timestamp = 1000L,
            packageName = "com.test.app3",
            appLabel = "Test App 3",
            previousState = true,
            previousAppOpsState = "allow",
            previousAppOpsStates = null,
            isDataCleared = true,
            requestedAction = ActionType.DISABLE_USER,
            operationResult = "Legacy record",
            isRestorable = false
        )
        assertFalse(legacyDataWipedRecord.isRestorable)
    }

    @Test
    fun testRestorePlanGeneration() {
        val baseline = CleanupBaseline(
            timestamp = 2000L,
            deviceModel = "Test TV",
            androidVersion = "12",
            totalAppsScanned = 10,
            records = listOf(
                CleanupRecord(
                    id = "1",
                    timestamp = 2000L,
                    packageName = "com.test.app1",
                    appLabel = "App 1",
                    previousState = true,
                    previousAppOpsState = "allow",
                    previousAppOpsStates = mapOf("RUN_IN_BACKGROUND" to "allow"),
                    isDataCleared = false,
                    requestedAction = ActionType.DISABLE_USER,
                    operationResult = "Disabled",
                    isRestorable = true
                ),
                CleanupRecord(
                    id = "2",
                    timestamp = 2000L,
                    packageName = "com.test.app2",
                    appLabel = "App 2",
                    previousState = true,
                    previousAppOpsState = "allow",
                    previousAppOpsStates = null,
                    isDataCleared = true,
                    requestedAction = ActionType.DISABLE_USER,
                    operationResult = "Legacy record",
                    isRestorable = false
                )
            )
        )

        val plan = BackupRestoreEngine(backupDir).generateRestorePlan(baseline)
        assertEquals(1, plan.size)
        assertEquals("com.test.app1", plan[0].packageName)
    }

    @Test
    fun testVersionedEnvelopeRecordsTargetAndIsAtomicallyVisible() {
        val engine = BackupRestoreEngine(backupDir)
        val baseline = baseline(1000L, record("com.test.one", previousState = true, opState = "allow"))

        assertTrue(engine.saveBaseline(baseline, tvA).saved)

        val latestFile = File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME)
        val originalFile = File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME)
        assertTrue(latestFile.isFile)
        assertTrue(originalFile.isFile)

        val status = engine.inspect(tvA)
        assertEquals(BackupState.BOUND, status.state)
        assertEquals(tvA, status.target)
        assertEquals("com.test.one", status.baseline!!.records[0].packageName)
        assertEquals("allow", status.baseline!!.records[0].appOpsSnapshot()["RUN_IN_BACKGROUND"])

        val stored = readObject(latestFile)
        assertTrue(stored is BackupEnvelope)
        val envelope = stored as BackupEnvelope
        assertEquals(BackupEnvelope.CURRENT_VERSION, envelope.version)
        assertEquals(tvA, envelope.target)
        assertEquals("com.test.one", envelope.baseline.records[0].packageName)
    }

    @Test
    fun testBackupWriteFailureReturnsFalse() {
        val notADirectory = tempFolder.newFile("not-a-directory")
        val failingEngine = BackupRestoreEngine(notADirectory)
        val baseline = baseline(1000L, record("com.test.one", previousState = true, opState = "allow"))

        assertFalse(failingEngine.saveBaseline(baseline, tvA).saved)
        assertEquals(BackupState.MISSING, failingEngine.inspect(tvA).state)
        assertNull(failingEngine.loadReportBaseline())
    }

    @Test
    fun testRepeatedSavePreservesFirstValidCapture() {
        val engine = BackupRestoreEngine(backupDir)

        val first = baseline(1000L, record("com.test.one", previousState = true, opState = "allow"))
        val retry = baseline(
            2000L,
            record("com.test.one", previousState = false, opState = "deny"),
            record("com.test.two", previousState = true, opState = "foreground")
        )

        assertTrue(engine.saveBaseline(first, tvA).saved)
        val originalBytes = File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME).readBytes()
        assertTrue(engine.saveBaseline(retry, tvA).saved)
        assertArrayEquals(
            originalBytes,
            File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME).readBytes()
        )

        val latest = engine.inspect(tvA).baseline!!
        assertEquals(2, latest.records.size)
        val firstPackage = latest.records.first { it.packageName == "com.test.one" }
        assertTrue(firstPackage.previousState)
        assertEquals("allow", firstPackage.appOpsSnapshot()["RUN_IN_BACKGROUND"])

        // The first valid baseline is immutable and still describes the pre-cleanup state.
        val original = readObject(File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME))
        assertTrue(original is BackupEnvelope)
        val originalBaseline = (original as BackupEnvelope).baseline
        assertEquals(1, originalBaseline.records.size)
        assertTrue(originalBaseline.records[0].previousState)
        assertEquals("allow", originalBaseline.records[0].appOpsSnapshot()["RUN_IN_BACKGROUND"])
    }

    @Test
    fun testCorruptedLatestBaselineIsPreservedAndReportFallsBackToOriginal() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow")), tvA).saved)

        val latestFile = File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME)
        latestFile.writeText("not a serialized baseline")
        val corruptedBytes = latestFile.readBytes()

        val status = engine.inspect(tvA)
        assertEquals(BackupState.UNREADABLE, status.state)
        assertTrue(status.detail, status.detail.contains(BackupRestoreEngine.LATEST_FILE_NAME))

        // Report reading may fall back to the readable original, but the corrupt file stays intact.
        val report = engine.loadReportBaseline()
        assertNotNull(report)
        assertEquals("com.test.one", report!!.records[0].packageName)

        val retry = engine.saveBaseline(
            baseline(2000L, record("com.test.one", true, "allow")),
            tvA
        )
        assertFalse(retry.saved)
        assertArrayEquals(corruptedBytes, latestFile.readBytes())
    }

    @Test
    fun testCorruptedBaselinesAreNeverTreatedAsMissing() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow")), tvA).saved)

        val latestFile = File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME)
        val originalFile = File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME)
        latestFile.writeText("corrupt")
        originalFile.writeText("also corrupt")
        val latestBytes = latestFile.readBytes()
        val originalBytes = originalFile.readBytes()

        assertEquals(BackupState.UNREADABLE, engine.inspect(tvA).state)
        assertNull(engine.loadReportBaseline())
        assertFalse(engine.saveBaseline(baseline(2000L, record("com.test.two", true, "allow")), tvA).saved)
        assertArrayEquals(latestBytes, latestFile.readBytes())
        assertArrayEquals(originalBytes, originalFile.readBytes())
    }

    @Test
    fun testOperationResultUpdateRequiresMatchingTargetAndNeverCreatesABaseline() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow")), tvA).saved)

        assertTrue(engine.updateOperationResults(mapOf("com.test.one" to "Interrupted"), tvA))

        val latest = engine.inspect(tvA).baseline!!
        assertEquals("Interrupted", latest.records[0].operationResult)
        assertTrue(latest.records[0].previousState)
        assertEquals("allow", latest.records[0].appOpsSnapshot()["RUN_IN_BACKGROUND"])

        val original = (readObject(File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME))
                as BackupEnvelope).baseline
        assertEquals("Disabled", original.records[0].operationResult)

        // A different target is never updated, and a missing target never gets a new file.
        assertFalse(engine.updateOperationResults(mapOf("com.test.one" to "Wrong TV"), tvB))
        assertEquals("Interrupted", engine.inspect(tvA).baseline!!.records[0].operationResult)

        val emptyDir = tempFolder.newFolder("empty-backups")
        val emptyEngine = BackupRestoreEngine(emptyDir)
        assertFalse(emptyEngine.updateOperationResults(mapOf("com.test.one" to "Created?"), tvA))
        assertEquals(BackupState.MISSING, emptyEngine.inspect(tvA).state)
    }

    @Test
    fun testBackupFromTvACannotBeRestoredOrExtendedAgainstTvB() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(
            baseline(1000L, record("com.test.one", true, "allow")),
            tvA
        ).saved)

        val latestFile = File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME)
        val before = latestFile.readBytes()

        val status = engine.inspect(tvB)
        assertEquals(BackupState.TARGET_MISMATCH, status.state)
        assertEquals(tvA, status.target)

        val saveForB = engine.saveBaseline(
            baseline(2000L, record("com.test.two", true, "deny")),
            tvB
        )
        assertFalse(saveForB.saved)
        assertTrue(saveForB.message.contains(tvA.display))
        assertArrayEquals(before, latestFile.readBytes())

        assertFalse(engine.updateOperationResults(mapOf("com.test.one" to "Wrong TV"), tvB))
        assertEquals(BackupState.BOUND, engine.inspect(tvA).state)

        // RestoreEngine must refuse before issuing a single shell command.
        val restoreShell = RecordingShell()
        val outcome = RestoreEngine(SafetyRulesEngine(), engine, restoreShell)
            .restore(engine.inspect(tvA).baseline!!, tvB, tvB.display)
        assertTrue(outcome.log, outcome.log.contains("belongs to ${tvA.display}"))
        assertFalse(outcome.log, outcome.log.contains("Restored"))
        assertTrue(restoreShell.commands.isEmpty())
    }

    @Test
    fun testChangingOnlyThePortCountsAsADifferentTarget() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow")), tvA).saved)

        val status = engine.inspect(tvAOnAnotherPort)
        assertEquals(BackupState.TARGET_MISMATCH, status.state)
        assertEquals(tvA, status.target)
        assertFalse(
            engine.saveBaseline(
                baseline(2000L, record("com.test.one", true, "allow")),
                tvAOnAnotherPort
            ).saved
        )
    }

    @Test
    fun testMissingBackupAllowsTheFirstBaselineButAnExistingOneCannotBeReplaced() {
        val engine = BackupRestoreEngine(backupDir)
        assertEquals(BackupState.MISSING, engine.inspect(tvA).state)

        val first = engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow")), tvA)
        assertTrue(first.saved)
        assertEquals(BackupState.BOUND, engine.inspect(tvA).state)

        // An unrelated target does not get a fresh baseline while this TV's backup exists.
        assertFalse(
            engine.saveBaseline(baseline(2000L, record("com.test.two", true, "deny")), tvB).saved
        )
        assertEquals(BackupState.BOUND, engine.inspect(tvA).state)
    }

    @Test
    fun testLegacyBackupIsPreservedUntilExplicitlyMigrated() {
        val engine = BackupRestoreEngine(backupDir)
        val legacy = baseline(1000L, record("com.test.one", true, "allow"))
        writeLegacy(File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME), legacy)
        writeLegacy(File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME), legacy)

        val status = engine.inspect(tvA)
        assertEquals(BackupState.LEGACY, status.state)
        assertEquals("com.test.one", status.baseline!!.records[0].packageName)
        assertEquals("com.test.one", engine.loadReportBaseline()!!.records[0].packageName)

        // An unbound legacy file is never overwritten or silently adopted.
        val refused = engine.saveBaseline(baseline(2000L, record("com.test.two", true, "deny")), tvA)
        assertFalse(refused.saved)
        assertTrue(readObject(File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME)) is CleanupBaseline)

        val migration = engine.migrateLegacyBackup(tvA)
        assertTrue(migration.message, migration.migrated)

        val migrated = engine.inspect(tvA)
        assertEquals(BackupState.BOUND, migrated.state)
        assertEquals(tvA, migrated.target)
        assertEquals("com.test.one", migrated.baseline!!.records[0].packageName)

        // The old bytes remain available beside the migrated envelopes.
        val copies = backupDir.listFiles()!!.filter { it.name.contains(".legacy-") }
        assertEquals(2, copies.size)
        assertTrue(copies.all { readObject(it) is CleanupBaseline })

        assertTrue(
            engine.saveBaseline(
                baseline(2000L, record("com.test.two", true, "foreground")),
                tvA
            ).saved
        )
    }

    @Test
    fun testLegacyMigrationStopsWhenASiblingIsCorrupt() {
        val engine = BackupRestoreEngine(backupDir)
        writeLegacy(
            File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME),
            baseline(1000L, record("com.test.one", true, "allow"))
        )
        val originalFile = File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME)
        originalFile.writeText("corrupt sibling")
        val corruptBytes = originalFile.readBytes()

        val migration = engine.migrateLegacyBackup(tvA)
        assertFalse(migration.migrated)
        assertTrue(migration.message, migration.message.contains(BackupRestoreEngine.ORIGINAL_FILE_NAME))
        assertArrayEquals(corruptBytes, originalFile.readBytes())
        assertTrue(readObject(File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME)) is CleanupBaseline)
    }

    @Test
    fun testLegacyMigrationRefusesAnAlreadyBoundDifferentTarget() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow")), tvA).saved)

        // Simulate a mixed state: a legacy working file beside a bound original.
        writeLegacy(
            File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME),
            baseline(1000L, record("com.test.one", true, "allow"))
        )
        val originalFile = File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME)
        val originalBytes = originalFile.readBytes()

        val refused = engine.migrateLegacyBackup(tvB)
        assertFalse(refused.migrated)
        assertTrue(refused.message, refused.message.contains(tvA.display))
        assertArrayEquals(originalBytes, originalFile.readBytes())

        assertTrue(engine.migrateLegacyBackup(tvA).migrated)
        assertEquals(BackupState.BOUND, engine.inspect(tvA).state)
    }

    @Test
    fun testHostComparisonIsNormalized() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow")), tvA).saved)

        val sameTarget = BackupTarget.of(" 192.168.1.20 ", 5555)!!
        assertEquals(BackupState.BOUND, engine.inspect(sameTarget).state)
    }

    private fun baseline(timestamp: Long, vararg records: CleanupRecord) = CleanupBaseline(
        timestamp = timestamp,
        deviceModel = "Test TV",
        androidVersion = "12",
        totalAppsScanned = records.size,
        records = records.toList()
    )

    private fun record(
        packageName: String,
        previousState: Boolean,
        opState: String
    ) = CleanupRecord(
        id = "id-$packageName",
        timestamp = 1000L,
        packageName = packageName,
        appLabel = packageName,
        previousState = previousState,
        previousAppOpsState = opState,
        previousAppOpsStates = mapOf("RUN_IN_BACKGROUND" to opState),
        isDataCleared = false,
        requestedAction = ActionType.DISABLE_USER,
        operationResult = "Disabled",
        isRestorable = true
    )

    private fun writeLegacy(file: File, baseline: CleanupBaseline) {
        FileOutputStream(file).use { fileOut ->
            ObjectOutputStream(fileOut).use { it.writeObject(baseline) }
        }
    }

    private fun readObject(file: File): Any? =
        ObjectInputStream(file.inputStream()).use { it.readObject() }

    private class RecordingShell : CommandRunner {
        val commands = mutableListOf<String>()

        override fun run(command: String): String {
            commands.add(command)
            return "Success: ok"
        }
    }
}
