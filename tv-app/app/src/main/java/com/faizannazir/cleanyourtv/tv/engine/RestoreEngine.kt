package com.faizannazir.cleanyourtv.tv.engine

import com.faizannazir.cleanyourtv.tv.model.CleanupBaseline
import com.faizannazir.cleanyourtv.tv.model.CleanupRecord

data class RestoreOutcome(
    val log: String,
    val restoredPackages: List<String> = emptyList(),
    val interrupted: Boolean = false
)

/**
 * Restores only the state captured before cleanup: the original enabled/disabled flag and every
 * captured AppOps mode. It never clears data, cache, preferences or databases.
 */
class RestoreEngine(
    private val safetyRules: SafetyRulesEngine = SafetyRulesEngine(),
    private val backup: BackupRestoreEngine,
    private val commandRunner: CommandRunner
) {

    fun restore(baseline: CleanupBaseline, targetLabel: String = ""): RestoreOutcome {
        val log = StringBuilder()
        if (targetLabel.isNotBlank()) {
            log.append("Restoration Execution Log ($targetLabel):\n")
        } else {
            log.append("Restoration Execution Log:\n")
        }
        log.append("------------------------------------\n")

        val plan = backup.generateRestorePlan(baseline)
        if (plan.isEmpty()) {
            log.append("No restorable package-state records were found. No changes made.\n")
            return RestoreOutcome(log = log.toString())
        }

        val restored = mutableListOf<String>()
        var interrupted = false

        for (record in plan) {
            val packageName = record.packageName.replace("\r", "").trim()
            if (!CommandSafety.isSafePackageName(packageName)) {
                log.append(" • SKIPPED (invalid package name): ${record.packageName}\n")
                continue
            }

            try {
                restorePackageState(record, packageName, log)
                restoreAppOps(record, packageName, log)
                restored.add(packageName)
                log.append(" • Restored [${record.appLabel}] ($packageName)\n")
                log.append("   Original package state and captured AppOps modes restored.\n\n")
            } catch (e: Exception) {
                interrupted = true
                log.append(" • INTERRUPTED while restoring $packageName: ${e.localizedMessage ?: e}\n")
                log.append("   The baseline is unchanged; run Restore App States again to continue.\n\n")
                break
            }
        }

        if (restored.isEmpty() && !interrupted) {
            log.append("No packages were changed.\n")
        }

        return RestoreOutcome(
            log = log.toString(),
            restoredPackages = restored,
            interrupted = interrupted
        )
    }

    private fun restorePackageState(
        record: CleanupRecord,
        packageName: String,
        log: StringBuilder
    ) {
        val command: String? = if (record.previousState) {
            "pm enable $packageName"
        } else if (safetyRules.isProtected(packageName)) {
            // Never disable a protected package, even if an old or hand-edited baseline says so.
            log.append(" • SKIPPED (protected package will not be disabled): $packageName\n")
            null
        } else {
            "pm disable-user --user 0 $packageName"
        }
        if (command == null) return

        val result = commandRunner.run(command)
        log.append(" • $packageName Original package state (${if (record.previousState) "enabled" else "disabled"}): " +
                "${ShellOutput.clean(result)}\n")
    }

    private fun restoreAppOps(
        record: CleanupRecord,
        packageName: String,
        log: StringBuilder
    ) {
        for ((operation, mode) in record.appOpsSnapshot()) {
            if (!CommandSafety.isSafeAppOpName(operation)) continue
            if (!CommandSafety.isSafeAppOpMode(mode)) continue

            val result = commandRunner.run("cmd appops set $packageName $operation $mode")
            log.append("   AppOps $operation=$mode: ${ShellOutput.clean(result)}\n")
        }
    }
}
