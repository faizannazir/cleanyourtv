package com.faizannazir.cleanyourtv.tv

import com.faizannazir.cleanyourtv.tv.engine.BackupRestoreEngine
import com.faizannazir.cleanyourtv.tv.model.ActionType
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

class BackupRestoreEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var backupDir: File

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
    fun testAtomicSaveAndLoad() {
        val engine = BackupRestoreEngine(backupDir)
        val baseline = baseline(1000L, record("com.test.one", previousState = true, opState = "allow"))

        assertTrue(engine.saveBaseline(baseline))

        assertTrue(File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME).isFile)
        assertTrue(File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME).isFile)

        val loaded = engine.loadLatestBaseline()
        assertNotNull(loaded)
        assertEquals(1, loaded!!.records.size)
        assertEquals("com.test.one", loaded.records[0].packageName)
        assertEquals("allow", loaded.records[0].appOpsSnapshot()["RUN_IN_BACKGROUND"])
    }

    @Test
    fun testBackupWriteFailureReturnsFalse() {
        val notADirectory = tempFolder.newFile("not-a-directory")
        val failingEngine = BackupRestoreEngine(notADirectory)
        val baseline = baseline(1000L, record("com.test.one", previousState = true, opState = "allow"))

        assertFalse(failingEngine.saveBaseline(baseline))
        assertNull(failingEngine.loadLatestBaseline())
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

        assertTrue(engine.saveBaseline(first))
        assertTrue(engine.saveBaseline(retry))

        val latest = engine.loadLatestBaseline()!!
        assertEquals(2, latest.records.size)
        val firstPackage = latest.records.first { it.packageName == "com.test.one" }
        assertTrue(firstPackage.previousState)
        assertEquals("allow", firstPackage.appOpsSnapshot()["RUN_IN_BACKGROUND"])

        // The first valid baseline is immutable and still describes the pre-cleanup state.
        val original = engine.loadOriginalBaseline()!!
        assertEquals(1, original.records.size)
        assertTrue(original.records[0].previousState)
        assertEquals("allow", original.records[0].appOpsSnapshot()["RUN_IN_BACKGROUND"])
    }

    @Test
    fun testCorruptedLatestBaselineFallsBackToOriginal() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow"))))

        File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME).writeText("not a serialized baseline")

        val loaded = engine.loadLatestBaseline()
        assertNotNull(loaded)
        assertEquals("com.test.one", loaded!!.records[0].packageName)
    }

    @Test
    fun testCorruptedBaselinesReturnNull() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow"))))

        File(backupDir, BackupRestoreEngine.LATEST_FILE_NAME).writeText("corrupt")
        File(backupDir, BackupRestoreEngine.ORIGINAL_FILE_NAME).writeText("also corrupt")

        assertNull(engine.loadLatestBaseline())
    }

    @Test
    fun testOperationResultUpdateDoesNotChangeCapturedState() {
        val engine = BackupRestoreEngine(backupDir)
        assertTrue(engine.saveBaseline(baseline(1000L, record("com.test.one", true, "allow"))))

        assertTrue(engine.updateOperationResults(mapOf("com.test.one" to "Interrupted")))

        val latest = engine.loadLatestBaseline()!!
        assertEquals("Interrupted", latest.records[0].operationResult)
        assertTrue(latest.records[0].previousState)
        assertEquals("allow", latest.records[0].appOpsSnapshot()["RUN_IN_BACKGROUND"])

        val original = engine.loadOriginalBaseline()!!
        assertEquals("Disabled", original.records[0].operationResult)
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
}
