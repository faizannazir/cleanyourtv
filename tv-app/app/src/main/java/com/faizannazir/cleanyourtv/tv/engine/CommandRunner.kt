package com.faizannazir.cleanyourtv.tv.engine

/**
 * Small seam around ADB shell execution so cleanup and restore can be exercised by unit tests
 * without a TV or a live ADB connection.
 */
fun interface CommandRunner {
    fun run(command: String): String
}

object ShellOutput {

    /**
     * [com.faizannazir.cleanyourtv.tv.adb.AdbConnection] reports a command that succeeded but
     * produced no output with this sentence. It is a transport status, not shell output, so it is
     * treated as an empty result instead of data.
     */
    const val EMPTY_OUTPUT_NOTICE = com.faizannazir.cleanyourtv.tv.adb.AdbConnection.EMPTY_OUTPUT_NOTICE

    /**
     * A response that ended before the remote ADB `CLSE`. The command may or may not have run, so it
     * is always treated as a failure and is never retried automatically.
     */
    const val UNCERTAIN_MARKER = com.faizannazir.cleanyourtv.tv.adb.AdbConnection.UNCERTAIN_MARKER

    fun isUncertain(raw: String): Boolean = raw.contains(UNCERTAIN_MARKER)

    fun isSuccess(raw: String): Boolean {
        if (isUncertain(raw) || isFailure(raw)) return false
        // A successful command either produced output or used the empty-output notice.
        return raw.trim().isNotEmpty()
    }

    fun clean(raw: String): String {
        val text = raw.replace("\r", "")
            .removePrefix("Success:")
            .trim()
        return if (text == EMPTY_OUTPUT_NOTICE) "" else text
    }

    fun isFailure(raw: String): Boolean {
        // An interrupted response is never a success, whatever text it carried before the break.
        if (isUncertain(raw)) return true
        val text = clean(raw).lowercase()
        if (text.isBlank()) return false
        return text.startsWith("error") ||
                text.startsWith("exception") ||
                text.contains("unknown command") ||
                text.contains("permission denial") ||
                text.contains("not found") ||
                text.contains("cannot ") ||
                text.contains("failed")
    }

    fun parsePackageList(raw: String): Set<String> {
        val text = clean(raw)
        if (isFailure(text)) return emptySet()
        return text.lines()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * `pm list packages` returns one `package:<name>` line per package.
     *
     * The enabled list must never be empty: every TV has enabled packages, so an empty or
     * malformed response means the capture failed. The disabled list, on the other hand, is
     * legitimately empty on a TV where nothing has been disabled yet, so callers that read it
     * pass [allowEmpty] and an empty successful response is accepted.
     */
    fun isUsablePackageList(raw: String, allowEmpty: Boolean = false): Boolean {
        val text = clean(raw)
        if (isFailure(text)) return false
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return allowEmpty
        return lines.all { it.startsWith("package:") }
    }
}
