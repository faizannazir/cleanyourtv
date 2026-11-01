package com.faizannazir.cleanyourtv.tv.engine

import com.faizannazir.cleanyourtv.tv.model.ActionType
import com.faizannazir.cleanyourtv.tv.model.AppCategory
import com.faizannazir.cleanyourtv.tv.model.AppInfo
import com.faizannazir.cleanyourtv.tv.model.BackupTarget
import com.faizannazir.cleanyourtv.tv.model.CleanupBaseline
import com.faizannazir.cleanyourtv.tv.model.CleanupRecord

data class CleanupContext(
    val deviceModel: String,
    val androidVersion: String,
    val totalAppsScanned: Int,
    val targetLabel: String = "",
    /**
     * The configured host and port this cleanup will talk to. A saved baseline may only be used
     * when it is bound to this exact target.
     */
    val target: BackupTarget? = null
)

data class CleanupOutcome(
    val backupSaved: Boolean,
    val aborted: Boolean,
    val log: String,
    val records: List<CleanupRecord> = emptyList(),
    val disabledPackages: List<String> = emptyList()
)

/**
 * Performs the normal cleanup flow.
 *
 * The only package mutation is `pm disable-user` for packages that were explicitly passed in by
 * the UI, are classified OPTIONAL, are currently enabled, and are not protected. Full AppOps and
 * enabled/disabled state is captured first, the backup must be persisted successfully, and only
 * then is the TV touched. No command here deletes app data or cache.
 */
class CleanupEngine(
    private val safetyRules: SafetyRulesEngine = SafetyRulesEngine(),
    private val backup: BackupRestoreEngine,
    private val commandRunner: CommandRunner
) {

    fun executeCleanup(targets: List<AppInfo>, context: CleanupContext): CleanupOutcome {
        val log = StringBuilder()
        if (context.targetLabel.isNotBlank()) {
            log.append("Debloat Execution Log (${context.targetLabel}):\n")
        } else {
            log.append("Debloat Execution Log:\n")
        }
        log.append("------------------------------------\n")

        val target = context.target
        if (target == null) {
            log.append("\nERROR: No TV target is configured, so a target-bound backup cannot be used.\n")
            log.append("No changes were made to the TV.\n")
            return CleanupOutcome(backupSaved = false, aborted = true, log = log.toString())
        }

        val backupStatus = backup.inspect(target)
        when (backupStatus.state) {
            BackupState.LEGACY -> {
                log.append("\nERROR: A saved baseline was written by an older version and is not bound to a TV.\n")
                log.append("It was preserved. Adopt it for ${target.display} first, or remove it manually if it did not come from this TV.\n")
                log.append("No changes were made to the TV.\n")
                return CleanupOutcome(backupSaved = false, aborted = true, log = log.toString())
            }
            BackupState.UNREADABLE -> {
                log.append("\nERROR: A saved baseline exists but cannot be read (${backupStatus.detail}).\n")
                log.append("It was preserved and was not overwritten. No changes were made to the TV.\n")
                return CleanupOutcome(backupSaved = false, aborted = true, log = log.toString())
            }
            BackupState.TARGET_MISMATCH -> {
                log.append("\nERROR: The saved baseline belongs to ${backupStatus.target?.display ?: "another target"}, not ${target.display}.\n")
                log.append("Restoring or extending another TV's backup is refused. No changes were made to the TV.\n")
                return CleanupOutcome(backupSaved = false, aborted = true, log = log.toString())
            }
            BackupState.MISSING, BackupState.BOUND -> Unit
        }

        val eligible = mutableListOf<AppInfo>()
        targets.distinctBy { it.packageName.replace("\r", "").trim() }.forEach { app ->
            val packageName = app.packageName.replace("\r", "").trim()
            when {
                !CommandSafety.isSafePackageName(packageName) -> {
                    log.append(" • SKIPPED (invalid package name): ${app.packageName}\n")
                }
                safetyRules.isProtected(packageName) -> {
                    log.append(" • SKIPPED (Protected): $packageName\n")
                }
                app.category != AppCategory.OPTIONAL -> {
                    log.append(" • SKIPPED (not an optional cleanup candidate): $packageName\n")
                }
                !app.isEnabled -> {
                    log.append(" • SKIPPED (already disabled): $packageName\n")
                }
                else -> eligible.add(app)
            }
        }

        if (eligible.isEmpty()) {
            log.append("\nNo explicitly selected, unprotected packages were eligible. No changes made.\n")
            return CleanupOutcome(backupSaved = false, aborted = true, log = log.toString())
        }

        val enabledRaw = try {
            commandRunner.run("pm list packages -e")
        } catch (e: Exception) {
            log.append("\nERROR: Could not read the TV's enabled package list: ${e.localizedMessage ?: e}\n")
            log.append("No changes were made to the TV.\n")
            return CleanupOutcome(backupSaved = false, aborted = true, log = log.toString())
        }
        val disabledRaw = try {
            commandRunner.run("pm list packages -d")
        } catch (e: Exception) {
            log.append("\nERROR: Could not read the TV's disabled package list: ${e.localizedMessage ?: e}\n")
            log.append("No changes were made to the TV.\n")
            return CleanupOutcome(backupSaved = false, aborted = true, log = log.toString())
        }
        if (!ShellOutput.isUsablePackageList(enabledRaw) ||
            !ShellOutput.isUsablePackageList(disabledRaw, allowEmpty = true)
        ) {
            log.append("\nERROR: Could not capture the TV's original enabled/disabled package state.\n")
            log.append("No changes were made to the TV.\n")
            return CleanupOutcome(backupSaved = false, aborted = true, log = log.toString())
        }

        val enabledPackages = ShellOutput.parsePackageList(enabledRaw)
        val disabledPackages = ShellOutput.parsePackageList(disabledRaw)
        val now = System.currentTimeMillis()
        val records = mutableListOf<CleanupRecord>()
        var captureFailed = false

        for (app in eligible) {
            val packageName = app.packageName.replace("\r", "").trim()
            val isEnabled = enabledPackages.contains(packageName)
            val isDisabled = disabledPackages.contains(packageName)

            if (!isEnabled && isDisabled) {
                log.append(" • SKIPPED (TV already reports disabled): $packageName\n")
                continue
            }
            if (!isEnabled) {
                log.append(" • SKIPPED (package state could not be read): $packageName\n")
                captureFailed = true
                continue
            }

            val appOps = try {
                AppOpsSnapshot.parse(commandRunner.run("cmd appops get $packageName"))
            } catch (_: Exception) {
                AppOpsSnapshot.Parsed(emptyMap(), captured = false)
            }

            val record = CleanupRecord(
                id = "${now}_$packageName",
                timestamp = now,
                packageName = packageName,
                appLabel = app.label,
                previousState = isEnabled,
                previousAppOpsState = appOps.states["RUN_IN_BACKGROUND"].orEmpty(),
                previousAppOpsStates = appOps.states,
                isDataCleared = false,
                requestedAction = ActionType.DISABLE_USER,
                operationResult = "Pending execution",
                packageStateCaptured = true,
                appOpsCaptured = appOps.captured,
                isRestorable = appOps.captured && !safetyRules.isProtected(packageName)
            )
            records.add(record)

            if (!record.isRestorable) {
                captureFailed = true
                log.append(" • CAPTURE FAILED (original AppOps state): $packageName\n")
            }
        }

        if (captureFailed) {
            log.append("\nERROR: The complete original state could not be captured for every selected package.\n")
            log.append("No changes were made to the TV. Fix the ADB connection and scan again.\n")
            return CleanupOutcome(
                backupSaved = false,
                aborted = true,
                log = log.toString(),
                records = records
            )
        }

        if (records.isEmpty()) {
            log.append("\nNo packages needed changes. No changes made.\n")
            return CleanupOutcome(backupSaved = false, aborted = true, log = log.toString())
        }

        val existingBaseline =
            if (backupStatus.state == BackupState.BOUND) backupStatus.baseline else null
        val requestedBaseline = CleanupBaseline(
            timestamp = now,
            deviceModel = context.deviceModel,
            androidVersion = context.androidVersion,
            totalAppsScanned = context.totalAppsScanned,
            records = records
        )
        val baseline = BaselineMerger.merge(existingBaseline, requestedBaseline)

        val saveResult = backup.saveBaseline(baseline, target)
        if (!saveResult.saved) {
            log.append("\nERROR: Failed to save the baseline backup for ${target.display}.\n")
            log.append("${saveResult.message}\n")
            log.append("Refusing to touch the TV or execute any package operations.\n")
            return CleanupOutcome(
                backupSaved = false,
                aborted = true,
                log = log.toString(),
                records = records
            )
        }

        val savedStatus = backup.inspect(target)
        if (savedStatus.state != BackupState.BOUND || savedStatus.baseline == null) {
            log.append("\nERROR: The saved baseline is not bound to ${target.display} after writing.\n")
            log.append("Refusing to touch the TV or execute any package operations.\n")
            return CleanupOutcome(
                backupSaved = false,
                aborted = true,
                log = log.toString(),
                records = records
            )
        }

        log.append("\n✓ Original package and AppOps state saved atomically before any mutation.\n")
        log.append("  Only the checked packages below are being disabled; app data is never deleted.\n\n")

        val operationResults = LinkedHashMap<String, String>()
        val successfullyDisabled = mutableListOf<String>()
        var interrupted = false

        for (record in records) {
            val packageName = record.packageName
            if (safetyRules.isProtected(packageName)) {
                operationResults[packageName] = "Skipped: protected package"
                log.append(" • SKIPPED (Protected): $packageName\n")
                continue
            }

            try {
                // Cleanup performs exactly one kind of mutation: disabling the explicitly
                // selected package. AppOps are captured only so restore can put them back.
                val disableResult = commandRunner.run("pm disable-user --user 0 $packageName")
                val cleanedResult = ShellOutput.clean(disableResult)
                log.append(" • $packageName Disable: $cleanedResult\n")

                if (ShellOutput.isFailure(disableResult)) {
                    operationResults[packageName] =
                        "Disable command reported a failure; original state retained in baseline: $cleanedResult"
                    log.append("   Original state is still recorded in the baseline for recovery.\n\n")
                } else {
                    operationResults[packageName] = "Disabled"
                    successfullyDisabled.add(packageName)
                    log.append("   Original state retained in the baseline for restoration.\n\n")
                }
            } catch (e: Exception) {
                interrupted = true
                operationResults[packageName] =
                    "Interrupted: ${e.localizedMessage ?: e.toString()}; original state retained in baseline"
                log.append(" • INTERRUPTED while changing $packageName: ${e.localizedMessage ?: e}\n")
                log.append("   The baseline was saved before this operation and is sufficient for recovery.\n\n")
                break
            }
        }

        if (interrupted) {
            records.filterNot { operationResults.containsKey(it.packageName) }.forEach {
                operationResults[it.packageName] =
                    "Not executed (cleanup interrupted); original state retained in baseline"
            }
        }

        // Keep the human-readable journal current. This only updates result text; the captured
        // original state and the immutable original baseline are never changed.
        backup.updateOperationResults(operationResults, target)

        log.append("Recovery: use Restore App States to put the captured package and AppOps states back.\n")
        if (successfullyDisabled.isNotEmpty()) {
            log.append("Disabled this run: ${successfullyDisabled.joinToString(", ")}\n")
        }

        return CleanupOutcome(
            backupSaved = true,
            aborted = false,
            log = log.toString(),
            records = records,
            disabledPackages = successfullyDisabled
        )
    }
}
