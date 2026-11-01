package com.faizannazir.cleanyourtv.tv.engine

import com.faizannazir.cleanyourtv.tv.model.CleanupBaseline
import com.faizannazir.cleanyourtv.tv.model.CleanupRecord

/**
 * Merges a new capture into an existing baseline without replacing an older, valid capture.
 *
 * A retry must never overwrite the state that was recorded before the first mutation: the second
 * capture may already describe a disabled package or a denied AppOps mode. Existing restorable
 * records therefore always win; new packages are only appended.
 */
object BaselineMerger {

    fun merge(existing: CleanupBaseline?, incoming: CleanupBaseline): CleanupBaseline {
        if (existing == null || existing.records.isEmpty()) return incoming

        val merged = LinkedHashMap<String, CleanupRecord>()
        existing.records.forEach { merged[it.packageName] = it }

        incoming.records.forEach { newRecord ->
            val oldRecord = merged[newRecord.packageName]
            if (oldRecord == null) {
                merged[newRecord.packageName] = newRecord
            } else if (!oldRecord.isRestorable && newRecord.isRestorable) {
                // A previously failed capture may be replaced by the first complete capture.
                merged[newRecord.packageName] = newRecord
            }
        }

        return incoming.copy(records = merged.values.toList())
    }
}
