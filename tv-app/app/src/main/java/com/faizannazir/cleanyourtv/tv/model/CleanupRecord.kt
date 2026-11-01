package com.faizannazir.cleanyourtv.tv.model

import java.io.Serializable

enum class ActionType {
    DISABLE_USER,
    ENABLE
}

data class CleanupItem(
    val packageName: String,
    val appLabel: String,
    val action: ActionType,
    val reason: String
) : Serializable

data class CleanupRecord(
    val id: String,
    val timestamp: Long,
    val packageName: String,
    val appLabel: String,
    val previousState: Boolean,
    /**
     * Legacy single-operation value, kept so baselines written by older builds can still be
     * restored. New records always write the complete snapshot to [previousAppOpsStates].
     */
    val previousAppOpsState: String = "allow",
    /**
     * Every AppOps mode read from `cmd appops get <package>` before cleanup. `null` means the
     * snapshot was not present in an older baseline; [appOpsSnapshot] then falls back to the
     * legacy single operation.
     */
    val previousAppOpsStates: Map<String, String>? = null,
    /**
     * Legacy flag. Cleanup never deletes application data, so new records always leave this false.
     */
    val isDataCleared: Boolean = false,
    val requestedAction: ActionType,
    val operationResult: String,
    val packageStateCaptured: Boolean = true,
    val appOpsCaptured: Boolean = true,
    val isRestorable: Boolean = !isDataCleared && packageStateCaptured && appOpsCaptured
) : Serializable {

    /**
     * Returns the complete original AppOps snapshot. Old baselines that only contain the single
     * legacy RUN_IN_BACKGROUND value still restore that operation.
     */
    fun appOpsSnapshot(): Map<String, String> {
        val captured = previousAppOpsStates
        // A non-null map is authoritative even when it is empty ("No operations."). Only a
        // baseline written by an older build has null here, and only that falls back to the
        // legacy single operation.
        if (captured != null) return captured
        if (previousAppOpsState.isBlank()) return emptyMap()
        return mapOf("RUN_IN_BACKGROUND" to previousAppOpsState)
    }
}

data class CleanupBaseline(
    val timestamp: Long,
    val deviceModel: String,
    val androidVersion: String,
    val totalAppsScanned: Int,
    val records: List<CleanupRecord>
) : Serializable
