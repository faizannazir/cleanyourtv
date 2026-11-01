package com.faizannazir.cleanyourtv.tv.engine

import android.content.Context
import com.faizannazir.cleanyourtv.tv.model.ActionType
import com.faizannazir.cleanyourtv.tv.model.CleanupBaseline
import com.faizannazir.cleanyourtv.tv.model.CleanupRecord
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists the original package state before any cleanup mutation.
 *
 * `latest_baseline.dat` contains the merged, first-valid capture for every package ever touched.
 * `original_baseline.dat` is written once and is never replaced, so a retry cannot replace the
 * state that existed before the first cleanup. Both are written through a temporary file, flushed,
 * fsynced and atomically renamed into place.
 */
class BackupRestoreEngine(private val backupDir: File) {

    constructor(context: Context) : this(File(context.filesDir, BACKUP_DIR_NAME))

    private val latestBackupFile = File(backupDir, LATEST_FILE_NAME)
    private val originalBackupFile = File(backupDir, ORIGINAL_FILE_NAME)

    @Synchronized
    fun saveBaseline(baseline: CleanupBaseline): Boolean {
        if (!ensureBackupDirectory()) return false

        return try {
            val latest = readBaseline(latestBackupFile)
            val original = readBaseline(originalBackupFile)
            val merged = BaselineMerger.merge(latest ?: original, baseline)

            // Persist the working baseline first. If this fails nothing on the TV may be touched.
            if (!writeAtomically(latestBackupFile, merged)) return false

            // The first valid baseline is immutable from then on. Retries may append new packages
            // to `latest_baseline.dat`, but they never rewrite this file.
            if (original == null && !writeAtomically(originalBackupFile, merged)) return false

            // Timestamped copies are useful for inspection, but the two files above are the
            // authoritative recovery data. A failure here must not invalidate a successful save.
            writeTimestampedArchive(merged)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun loadLatestBaseline(): CleanupBaseline? =
        readBaseline(latestBackupFile) ?: readBaseline(originalBackupFile)

    @Synchronized
    fun loadOriginalBaseline(): CleanupBaseline? = readBaseline(originalBackupFile)

    /**
     * Updates only the human-readable result of records in the working baseline. Original package
     * and AppOps state is never changed here. Used after an interrupted cleanup so the next launch
     * can still see which operations completed and which were still pending.
     */
    @Synchronized
    fun updateOperationResults(results: Map<String, String>): Boolean {
        if (results.isEmpty()) return true
        val latest = readBaseline(latestBackupFile) ?: readBaseline(originalBackupFile) ?: return false
        val updated = latest.copy(
            records = latest.records.map { record ->
                results[record.packageName]?.let { record.copy(operationResult = it) } ?: record
            }
        )
        return writeAtomically(latestBackupFile, updated)
    }

    fun generateRestorePlan(baseline: CleanupBaseline): List<CleanupRecord> {
        return baseline.records
            .filter { record ->
                record.isRestorable &&
                        !record.isDataCleared &&
                        (record.requestedAction == ActionType.DISABLE_USER ||
                                record.requestedAction == ActionType.ENABLE)
            }
            .distinctBy { it.packageName }
    }

    fun exportReportJson(baseline: CleanupBaseline?): String {
        val sb = java.lang.StringBuilder()
        sb.append("{\n")
        sb.append("  \"appName\": \"CleanYourTV\",\n")
        sb.append("  \"exportTimestamp\": ${System.currentTimeMillis()},\n")
        if (baseline != null) {
            sb.append("  \"baselineTimestamp\": ${baseline.timestamp},\n")
            sb.append("  \"deviceModel\": \"${escapeJson(baseline.deviceModel)}\",\n")
            sb.append("  \"androidVersion\": \"${escapeJson(baseline.androidVersion)}\",\n")
            sb.append("  \"totalAppsScanned\": ${baseline.totalAppsScanned},\n")
            sb.append("  \"modifiedRecordsCount\": ${baseline.records.size},\n")
            sb.append("  \"capturedState\": \"Package enabled/disabled state and AppOps modes only\",\n")
            sb.append("  \"appDataDeleted\": false,\n")
            sb.append("  \"records\": [\n")
            baseline.records.forEachIndexed { idx, rec ->
                sb.append("    {\n")
                sb.append("      \"packageName\": \"${escapeJson(rec.packageName)}\",\n")
                sb.append("      \"appLabel\": \"${escapeJson(rec.appLabel)}\",\n")
                sb.append("      \"action\": \"${rec.requestedAction}\",\n")
                sb.append("      \"previousState\": ${rec.previousState},\n")
                sb.append("      \"previousAppOpsState\": \"${escapeJson(rec.previousAppOpsState)}\",\n")
                sb.append("      \"previousAppOpsStates\": {")
                val states = rec.appOpsSnapshot()
                states.entries.forEachIndexed { stateIndex, entry ->
                    sb.append("\"${escapeJson(entry.key)}\": \"${escapeJson(entry.value)}\"")
                    if (stateIndex < states.size - 1) sb.append(", ")
                }
                sb.append("},\n")
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

    private fun ensureBackupDirectory(): Boolean {
        if (backupDir.exists()) return backupDir.isDirectory
        return backupDir.mkdirs() || backupDir.isDirectory
    }

    private fun writeAtomically(target: File, baseline: CleanupBaseline): Boolean {
        var tempFile: File? = null
        return try {
            val temp = File.createTempFile("${target.name}.", ".tmp", backupDir)
            tempFile = temp
            FileOutputStream(temp).use { fileOut ->
                val objectOut = ObjectOutputStream(fileOut)
                objectOut.writeObject(baseline)
                objectOut.flush()
                // Make sure the bytes are on disk before the atomic rename publishes the file.
                fileOut.fd.sync()
                objectOut.close()
            }

            replaceAtomically(temp, target)
            target.isFile && target.length() > 0
        } catch (_: Exception) {
            tempFile?.delete()
            false
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeTimestampedArchive(baseline: CleanupBaseline) {
        val timestampFile = File(backupDir, "baseline_${baseline.timestamp}.dat")
        try {
            writeAtomically(timestampFile, baseline)
        } catch (_: Exception) {
            // The latest/original files are the recovery source of truth.
        }
    }

    private fun readBaseline(file: File): CleanupBaseline? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            ObjectInputStream(file.inputStream()).use { input ->
                val value = input.readObject() as? CleanupBaseline ?: return null
                if (value.records.isEmpty()) return null
                if (value.records.any { it.packageName.isBlank() }) return null
                value
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }

    companion object {
        const val BACKUP_DIR_NAME = "backups"
        const val LATEST_FILE_NAME = "latest_baseline.dat"
        const val ORIGINAL_FILE_NAME = "original_baseline.dat"
    }
}
