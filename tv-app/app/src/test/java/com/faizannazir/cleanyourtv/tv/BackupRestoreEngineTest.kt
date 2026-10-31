package com.faizannazir.cleanyourtv.tv

import com.faizannazir.cleanyourtv.tv.model.ActionType
import com.faizannazir.cleanyourtv.tv.model.CleanupBaseline
import com.faizannazir.cleanyourtv.tv.model.CleanupRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class BackupRestoreEngineTest {

    @Test
    fun testRestorabilityContractPreservesAppOpsAndPackageState() {
        val record = CleanupRecord(
            id = "rec1",
            timestamp = 1000L,
            packageName = "com.test.app",
            appLabel = "Test App",
            previousState = true,
            previousAppOpsState = "allow",
            isDataCleared = false,
            requestedAction = ActionType.DISABLE_USER,
            operationResult = "Disabled",
            isRestorable = true
        )
        assertTrue(record.isRestorable)
        assertFalse(record.isDataCleared)
        assertEquals("allow", record.previousAppOpsState)
    }

    @Test
    fun testDestructiveDataClearedCannotBeMarkedRestorable() {
        val destructiveRecord = CleanupRecord(
            id = "rec2",
            timestamp = 1000L,
            packageName = "com.test.destructive",
            appLabel = "Destructive App",
            previousState = true,
            previousAppOpsState = "allow",
            isDataCleared = true,
            requestedAction = ActionType.DISABLE_USER,
            operationResult = "Data Wiped",
            isRestorable = false
        )
        assertFalse(destructiveRecord.isRestorable)
        assertTrue(destructiveRecord.isDataCleared)
    }

    @Test
    fun testBaselineSerializationRoundTrip() {
        val original = CleanupBaseline(
            schemaVersion = 1,
            timestamp = 5000L,
            deviceModel = "TCL Smart TV Pro",
            androidVersion = "12",
            totalAppsScanned = 42,
            records = listOf(
                CleanupRecord(
                    id = "r1",
                    timestamp = 5000L,
                    packageName = "com.tcl.partnercustomizer",
                    appLabel = "Partner Customizer",
                    previousState = true,
                    previousAppOpsState = "allow",
                    isDataCleared = false,
                    requestedAction = ActionType.DISABLE_USER,
                    operationResult = "Success",
                    isRestorable = true
                )
            )
        )

        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { oos ->
            oos.writeObject(original)
        }

        val bais = ByteArrayInputStream(baos.toByteArray())
        val deserialized = ObjectInputStream(bais).use { ois ->
            ois.readObject() as CleanupBaseline
        }

        assertEquals(original.schemaVersion, deserialized.schemaVersion)
        assertEquals(original.deviceModel, deserialized.deviceModel)
        assertEquals(original.records.size, deserialized.records.size)
        assertEquals("com.tcl.partnercustomizer", deserialized.records[0].packageName)
        assertEquals("allow", deserialized.records[0].previousAppOpsState)
        assertTrue(deserialized.records[0].isRestorable)
    }

    @Test
    fun testRestorePlanFiltersOnlyRestorableRecords() {
        val baseline = CleanupBaseline(
            schemaVersion = 1,
            timestamp = 3000L,
            deviceModel = "Test TV",
            androidVersion = "11",
            totalAppsScanned = 15,
            records = listOf(
                CleanupRecord(
                    id = "1",
                    timestamp = 3000L,
                    packageName = "com.app.restorable",
                    appLabel = "Restorable App",
                    previousState = true,
                    previousAppOpsState = "allow",
                    isDataCleared = false,
                    requestedAction = ActionType.DISABLE_USER,
                    operationResult = "Disabled",
                    isRestorable = true
                ),
                CleanupRecord(
                    id = "2",
                    timestamp = 3000L,
                    packageName = "com.app.wiped",
                    appLabel = "Wiped App",
                    previousState = true,
                    previousAppOpsState = "allow",
                    isDataCleared = true,
                    requestedAction = ActionType.DISABLE_USER,
                    operationResult = "Wiped",
                    isRestorable = false
                )
            )
        )

        val plan = baseline.records.filter { it.isRestorable && it.requestedAction == ActionType.DISABLE_USER }
        assertEquals(1, plan.size)
        assertEquals("com.app.restorable", plan[0].packageName)
    }
}
