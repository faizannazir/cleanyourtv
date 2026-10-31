package com.faizannazir.cleanyourtv.tv.engine

import android.content.Context
import com.faizannazir.cleanyourtv.tv.model.ActionType
import com.faizannazir.cleanyourtv.tv.model.CleanupBaseline
import com.faizannazir.cleanyourtv.tv.model.CleanupRecord
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class BackupRestoreEngine(private val context: Context) {

    private val backupDir = File(context.filesDir, "backups").apply {
        if (!exists()) mkdirs()
    }

    private val latestBackupFile = File(backupDir, "latest_baseline.dat")

    @Synchronized
    fun saveBaseline(baseline: CleanupBaseline): Boolean {
        val tempFile = File(backupDir, "latest_baseline.tmp")
        return try {
            FileOutputStream(tempFile).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(baseline)
                    oos.flush()
                }
                fos.fd.sync()
            }

            Files.move(
                tempFile.toPath(),
                latestBackupFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )

            // Save timestamped copy
            val timestampFile = File(backupDir, "baseline_${baseline.timestamp}.dat")
            FileOutputStream(timestampFile).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(baseline)
                    oos.flush()
                }
                fos.fd.sync()
            }
            true
        } catch (_: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            false
        }
    }

    @Synchronized
    fun loadLatestBaseline(): CleanupBaseline? {
        if (!latestBackupFile.exists() || latestBackupFile.length() == 0L) return null
        return try {
            ObjectInputStream(latestBackupFile.inputStream()).use { input ->
                val obj = input.readObject() as? CleanupBaseline
                if (obj != null && obj.schemaVersion >= 1) obj else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun generateRestorePlan(baseline: CleanupBaseline): List<CleanupRecord> {
        return baseline.records.filter { record ->
            record.isRestorable && record.requestedAction == ActionType.DISABLE_USER
        }
    }

    fun exportReportJson(baseline: CleanupBaseline?): String {
        val sb = java.lang.StringBuilder()
        sb.append("{\n")
        sb.append("  \"appName\": \"CleanYourTV\",\n")
        sb.append("  \"exportTimestamp\": ${System.currentTimeMillis()},\n")
        if (baseline != null) {
            sb.append("  \"schemaVersion\": ${baseline.schemaVersion},\n")
            sb.append("  \"baselineTimestamp\": ${baseline.timestamp},\n")
            sb.append("  \"deviceModel\": \"${escapeJson(baseline.deviceModel)}\",\n")
            sb.append("  \"androidVersion\": \"${escapeJson(baseline.androidVersion)}\",\n")
            sb.append("  \"totalAppsScanned\": ${baseline.totalAppsScanned},\n")
            sb.append("  \"modifiedRecordsCount\": ${baseline.records.size},\n")
            sb.append("  \"records\": [\n")
            baseline.records.forEachIndexed { idx, rec ->
                sb.append("    {\n")
                sb.append("      \"packageName\": \"${escapeJson(rec.packageName)}\",\n")
                sb.append("      \"appLabel\": \"${escapeJson(rec.appLabel)}\",\n")
                sb.append("      \"action\": \"${rec.requestedAction}\",\n")
                sb.append("      \"previousState\": ${rec.previousState},\n")
                sb.append("      \"previousAppOpsState\": \"${escapeJson(rec.previousAppOpsState)}\",\n")
                sb.append("      \"isRestorable\": ${rec.isRestorable},\n")
                sb.append("      \"result\": \"${escapeJson(rec.operationResult)}\"\n")
                sb.append("    }${if (idx < baseline.records.size - 1) "," else ""}\n")
            }
            sb.append("  ]\n")
        } else {
            sb.append("  \"status\": \"No baseline backup available\"\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }
}
