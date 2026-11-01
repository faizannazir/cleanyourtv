package com.faizannazir.cleanyourtv.tv.engine

import android.content.Context
import com.faizannazir.cleanyourtv.tv.model.ActionType
import com.faizannazir.cleanyourtv.tv.model.BackupEnvelope
import com.faizannazir.cleanyourtv.tv.model.BackupTarget
import com.faizannazir.cleanyourtv.tv.model.CleanupBaseline
import com.faizannazir.cleanyourtv.tv.model.CleanupRecord
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What is currently stored on disk for one backup slot. */
enum class BackupState {
    /** No file exists for this slot. */
    MISSING,
    /** A readable, versioned backup bound to a target. */
    BOUND,
    /** A readable baseline written by a build that did not store a target. */
    LEGACY,
    /** A file exists but cannot be parsed or was written by an unsupported version. */
    UNREADABLE,
    /** A readable, versioned backup that belongs to a different host or port. */
    TARGET_MISMATCH
}

/**
 * The result of inspecting both authoritative backup files.
 *
 * [state] is the most restrictive condition across the two files, so a caller cannot use a good
 * `latest` file to bypass a corrupt or mismatched `original` file.
 */
data class BackupStatus(
    val state: BackupState,
    val baseline: CleanupBaseline? = null,
    val target: BackupTarget? = null,
    val detail: String = ""
) {
    val usableForTarget: Boolean
        get() = state == BackupState.BOUND || state == BackupState.MISSING
}

data class BackupSaveResult(val saved: Boolean, val message: String)

data class BackupMigrationResult(val migrated: Boolean, val message: String)

/**
 * Persists the original package state before any cleanup mutation.
 *
 * Both authoritative files contain a versioned [BackupEnvelope] that records the target host and
 * port. `latest_baseline.dat` contains the merged, first-valid capture for every package ever
 * touched. `original_baseline.dat` is written once and is never replaced, so a retry cannot replace
 * the state that existed before the first cleanup. Every write goes through a temporary file,
 * flush, fsync and atomic rename.
 *
 * A backup is never re-bound silently. A file from an older build has no target and is reported as
 * [BackupState.LEGACY]; it is migrated only through [migrateLegacyBackup], which preserves the
 * original bytes before writing an envelope. A file that cannot be read is reported as
 * [BackupState.UNREADABLE] and is left in place: it is never mistaken for "no backup" and never
 * overwritten.
 *
 * Host and port are not cryptographic physical-device identity. They cannot distinguish two TVs if
 * a DHCP server later gives the same address and port to another device. The binding prevents a
 * backup from being reused against a different address by accident; the person must still confirm
 * the target before restoring.
 */
class BackupRestoreEngine(private val backupDir: File) {

    constructor(context: Context) : this(File(context.filesDir, BACKUP_DIR_NAME))

    private val latestBackupFile = File(backupDir, LATEST_FILE_NAME)
    private val originalBackupFile = File(backupDir, ORIGINAL_FILE_NAME)

    /**
     * Reads both authoritative files and reports the most restrictive state.
     *
     * When [target] is supplied, any readable backup bound to another target makes the result
     * [BackupState.TARGET_MISMATCH]. When it is `null`, this is a report-only inspection and the
     * stored target is returned without comparison.
     */
    @Synchronized
    fun inspect(target: BackupTarget? = null): BackupStatus {
        val latest = readStoredBackup(latestBackupFile)
        val original = readStoredBackup(originalBackupFile)
        val files = listOf(latestBackupFile to latest, originalBackupFile to original)

        files.forEach { (file, stored) ->
            if (stored is StoredBackup.Unreadable) {
                return BackupStatus(
                    state = BackupState.UNREADABLE,
                    detail = "${file.name}: ${stored.reason}"
                )
            }
        }

        val bound = files.mapNotNull { (_, stored) ->
            (stored as? StoredBackup.Bound)?.envelope
        }

        if (target != null) {
            val mismatch = bound.firstOrNull { !it.target.sameAs(target) }
            if (mismatch != null) {
                return BackupStatus(
                    state = BackupState.TARGET_MISMATCH,
                    target = mismatch.target,
                    detail = "The saved baseline belongs to ${mismatch.target.display}, not ${target.display}."
                )
            }
        }

        val legacy = files.mapNotNull { (_, stored) ->
            (stored as? StoredBackup.Legacy)?.baseline
        }
        if (legacy.isNotEmpty()) {
            return BackupStatus(
                state = BackupState.LEGACY,
                baseline = (latest as? StoredBackup.Legacy)?.baseline ?: legacy.first(),
                detail = "The saved baseline was written by an older version and is not bound to a TV."
            )
        }

        if (bound.isEmpty()) {
            return BackupStatus(state = BackupState.MISSING)
        }

        val chosen = (latest as? StoredBackup.Bound)?.envelope
            ?: (original as? StoredBackup.Bound)?.envelope
            ?: return BackupStatus(state = BackupState.MISSING)
        return BackupStatus(
            state = BackupState.BOUND,
            baseline = chosen.baseline,
            target = chosen.target
        )
    }

    /**
     * Returns a baseline for the local report only.
     *
     * This deliberately ignores the target binding and falls back from a corrupt `latest` file to
     * `original`, so an export can still describe what was captured. It must never be used to
     * decide whether a restore is allowed; use [inspect] for that.
     */
    @Synchronized
    fun loadReportBaseline(): CleanupBaseline? {
        val latest = readStoredBackup(latestBackupFile)
        when (latest) {
            is StoredBackup.Bound -> return latest.envelope.baseline
            is StoredBackup.Legacy -> return latest.baseline
            else -> Unit
        }

        return when (val original = readStoredBackup(originalBackupFile)) {
            is StoredBackup.Bound -> original.envelope.baseline
            is StoredBackup.Legacy -> original.baseline
            else -> null
        }
    }

    /**
     * Saves a baseline for [target].
     *
     * A new baseline is written only when neither authoritative file exists. If a readable baseline
     * for the same target exists, [BaselineMerger] keeps every original record and appends only new
     * packages. A mismatched, legacy or unreadable file is never overwritten: the save fails and
     * the file is left exactly as it was.
     */
    @Synchronized
    fun saveBaseline(baseline: CleanupBaseline, target: BackupTarget): BackupSaveResult {
        if (baseline.records.isEmpty()) {
            return BackupSaveResult(false, "The baseline has no package records.")
        }
        if (baseline.records.any { it.packageName.isBlank() }) {
            return BackupSaveResult(false, "The baseline contains a blank package name.")
        }
        if (!ensureBackupDirectory()) {
            return BackupSaveResult(false, "The backup directory could not be created.")
        }

        val current = inspect(target)
        when (current.state) {
            BackupState.UNREADABLE -> return BackupSaveResult(
                false,
                "A saved baseline exists but cannot be read (${current.detail}). " +
                        "It was preserved; refusing to overwrite it."
            )
            BackupState.TARGET_MISMATCH -> return BackupSaveResult(
                false,
                "The saved baseline belongs to ${current.target?.display ?: "another target"}, " +
                        "not ${target.display}. Refusing to overwrite it."
            )
            BackupState.LEGACY -> return BackupSaveResult(
                false,
                "A saved baseline was written by an older version and is not bound to a TV. " +
                        "It was preserved; migrate it explicitly before saving a new baseline."
            )
            BackupState.BOUND, BackupState.MISSING -> Unit
        }

        val merged = BaselineMerger.merge(current.baseline, baseline)
        val now = System.currentTimeMillis()
        if (!writeAtomically(
                latestBackupFile,
                BackupEnvelope(BackupEnvelope.CURRENT_VERSION, target, now, merged)
            )
        ) {
            return BackupSaveResult(false, "The working baseline could not be written.")
        }

        // The original is created once. A retry never rewrites it, even when it appends packages.
        if (readStoredBackup(originalBackupFile) is StoredBackup.Missing) {
            if (!writeAtomically(
                    originalBackupFile,
                    BackupEnvelope(BackupEnvelope.CURRENT_VERSION, target, now, merged)
                )
            ) {
                return BackupSaveResult(false, "The original baseline could not be written.")
            }
        }

        writeTimestampedArchive(merged, target)

        val verified = inspect(target)
        if (verified.state != BackupState.BOUND || verified.baseline == null) {
            return BackupSaveResult(false, "The baseline was written but could not be verified.")
        }
        return BackupSaveResult(true, "Baseline saved for ${target.display}.")
    }

    /**
     * Updates only the human-readable result of records in the working baseline. Original package
     * and AppOps state is never changed here. Used after an interrupted cleanup so the next launch
     * can still see which operations completed and which were still pending.
     *
     * Returns `false` for a missing, legacy, corrupt or mismatched backup: this method never creates
     * a baseline and never binds an existing one to a different target.
     */
    @Synchronized
    fun updateOperationResults(results: Map<String, String>, target: BackupTarget): Boolean {
        if (results.isEmpty()) return true
        val current = inspect(target)
        if (current.state != BackupState.BOUND) return false
        val latest = current.baseline ?: return false
        val updated = latest.copy(
            records = latest.records.map { record ->
                results[record.packageName]?.let { record.copy(operationResult = it) } ?: record
            }
        )
        return writeAtomically(
            latestBackupFile,
            BackupEnvelope(
                BackupEnvelope.CURRENT_VERSION,
                target,
                System.currentTimeMillis(),
                updated
            )
        )
    }

    /**
     * Explicitly binds legacy files to [target] after the person confirms that they came from this
     * TV.
     *
     * Each legacy file is copied to a `*.legacy-<timestamp>` sibling before an envelope replaces it,
     * so the migration is reversible. Nothing is migrated when a file is unreadable, or when an
     * already-bound file belongs to another target. No caller may run this automatically.
     */
    @Synchronized
    fun migrateLegacyBackup(target: BackupTarget): BackupMigrationResult {
        if (!ensureBackupDirectory()) {
            return BackupMigrationResult(false, "The backup directory could not be created.")
        }

        val latest = readStoredBackup(latestBackupFile)
        val original = readStoredBackup(originalBackupFile)
        val files = listOf(latestBackupFile to latest, originalBackupFile to original)

        files.forEach { (file, stored) ->
            when (stored) {
                is StoredBackup.Unreadable -> return BackupMigrationResult(
                    false,
                    "${file.name} cannot be read (${stored.reason}). It was preserved; " +
                            "legacy migration stopped."
                )
                is StoredBackup.Bound -> if (!stored.envelope.target.sameAs(target)) {
                    return BackupMigrationResult(
                        false,
                        "${file.name} is bound to ${stored.envelope.target.display}, not " +
                                "${target.display}. It was preserved."
                    )
                }
                else -> Unit
            }
        }

        var migrated = 0
        if (latest is StoredBackup.Legacy) {
            if (!preserveLegacyCopy(latestBackupFile)) {
                return BackupMigrationResult(false, "The legacy working baseline could not be preserved.")
            }
            if (!writeAtomically(
                    latestBackupFile,
                    BackupEnvelope(
                        BackupEnvelope.CURRENT_VERSION,
                        target,
                        System.currentTimeMillis(),
                        latest.baseline
                    )
                )
            ) {
                return BackupMigrationResult(false, "The legacy working baseline could not be migrated.")
            }
            migrated++
        }

        if (original is StoredBackup.Legacy) {
            if (!preserveLegacyCopy(originalBackupFile)) {
                return BackupMigrationResult(false, "The legacy original baseline could not be preserved.")
            }
            if (!writeAtomically(
                    originalBackupFile,
                    BackupEnvelope(
                        BackupEnvelope.CURRENT_VERSION,
                        target,
                        System.currentTimeMillis(),
                        original.baseline
                    )
                )
            ) {
                return BackupMigrationResult(false, "The legacy original baseline could not be migrated.")
            }
            migrated++
        }

        if (migrated == 0) {
            return BackupMigrationResult(false, "No legacy backup needed migration.")
        }

        val verified = inspect(target)
        if (verified.state != BackupState.BOUND) {
            return BackupMigrationResult(
                false,
                "The legacy backup was copied but the migrated files could not be verified."
            )
        }
        return BackupMigrationResult(
            true,
            "Bound the legacy backup to ${target.display}. The original file was preserved."
        )
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

    private fun writeAtomically(target: File, value: Serializable): Boolean {
        var tempFile: File? = null
        return try {
            val temp = File.createTempFile("${target.name}.", ".tmp", backupDir)
            tempFile = temp
            FileOutputStream(temp).use { fileOut ->
                val objectOut = ObjectOutputStream(fileOut)
                objectOut.writeObject(value)
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

    private fun writeTimestampedArchive(baseline: CleanupBaseline, target: BackupTarget) {
        val timestampFile = File(backupDir, "baseline_${baseline.timestamp}.dat")
        try {
            writeAtomically(
                timestampFile,
                BackupEnvelope(BackupEnvelope.CURRENT_VERSION, target, System.currentTimeMillis(), baseline)
            )
        } catch (_: Exception) {
            // The latest/original files are the recovery source of truth.
        }
    }

    private fun preserveLegacyCopy(file: File): Boolean {
        val stamp = System.currentTimeMillis()
        var copy = File(backupDir, "${file.name}.legacy-$stamp")
        var suffix = 1
        while (copy.exists()) {
            copy = File(backupDir, "${file.name}.legacy-$stamp-${suffix++}")
        }
        return try {
            file.copyTo(copy, overwrite = false)
            copy.isFile && copy.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun readStoredBackup(file: File): StoredBackup {
        if (!file.exists()) return StoredBackup.Missing
        if (!file.isFile || !file.canRead()) {
            return StoredBackup.Unreadable("the path is not a readable file")
        }
        if (file.length() == 0L) return StoredBackup.Unreadable("the file is empty")

        return try {
            ObjectInputStream(file.inputStream()).use { input ->
                when (val value = input.readObject()) {
                    is BackupEnvelope -> validateEnvelope(value)
                    is CleanupBaseline -> validateLegacy(value)
                    else -> StoredBackup.Unreadable("the file does not contain a CleanYourTV baseline")
                }
            }
        } catch (e: Exception) {
            StoredBackup.Unreadable(e.localizedMessage ?: e.toString())
        }
    }

    private fun validateEnvelope(envelope: BackupEnvelope): StoredBackup {
        if (envelope.version !in BackupEnvelope.MIN_SUPPORTED_VERSION..BackupEnvelope.CURRENT_VERSION) {
            return StoredBackup.Unreadable("unsupported backup format version ${envelope.version}")
        }
        val target = envelope.target
        if (target.host.isBlank() || target.port !in 1..65535) {
            return StoredBackup.Unreadable("the backup has no valid target binding")
        }
        if (!isValidBaseline(envelope.baseline)) {
            return StoredBackup.Unreadable("the backup has no valid package records")
        }
        return StoredBackup.Bound(envelope)
    }

    private fun validateLegacy(baseline: CleanupBaseline): StoredBackup =
        if (isValidBaseline(baseline)) {
            StoredBackup.Legacy(baseline)
        } else {
            StoredBackup.Unreadable("the legacy baseline has no valid package records")
        }

    private fun isValidBaseline(baseline: CleanupBaseline): Boolean =
        baseline.records.isNotEmpty() && baseline.records.all { it.packageName.isNotBlank() }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }

    private sealed class StoredBackup {
        object Missing : StoredBackup()
        data class Unreadable(val reason: String) : StoredBackup()
        data class Legacy(val baseline: CleanupBaseline) : StoredBackup()
        data class Bound(val envelope: BackupEnvelope) : StoredBackup()
    }

    companion object {
        const val BACKUP_DIR_NAME = "backups"
        const val LATEST_FILE_NAME = "latest_baseline.dat"
        const val ORIGINAL_FILE_NAME = "original_baseline.dat"
    }
}
