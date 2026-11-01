package com.faizannazir.cleanyourtv.tv.engine

/**
 * Parser for the output of `cmd appops get <package>`.
 *
 * AOSP prints one line per package operation:
 *
 *     OP_NAME: mode[; time=...][; rejectTime=...][; duration=...][ (running)]
 *
 * `mode` is exactly one of [ALLOWED_MODES]. Inherited UID modes are printed as
 * `Uid mode: OP_NAME: mode`, but only the first line of the UID block carries that marker: the
 * remaining lines of that block look exactly like package lines. An output that mixes a UID line
 * with anything else is therefore ambiguous and is refused instead of being guessed at.
 *
 * `No operations.` is the complete answer for a package with no UID and no package overrides.
 * Anything else that cannot be read with certainty returns `captured = false`, and cleanup then
 * refuses to touch the TV.
 */
object AppOpsSnapshot {

    val ALLOWED_MODES = setOf("allow", "deny", "ignore", "default", "foreground")

    private val METADATA_ENTRY = Regex("^(?:time|rejectTime|duration)=\\S.*$")

    data class Parsed(
        val states: Map<String, String>,
        val captured: Boolean
    )

    fun parse(raw: String): Parsed {
        val text = ShellOutput.clean(raw)
        if (text.isBlank() || ShellOutput.isFailure(text)) {
            return Parsed(emptyMap(), captured = false)
        }

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val noOperations = lines.any { isNoOperations(it) }
        val operationLines = lines.filterNot { isNoOperations(it) || isDefaultModeLine(it) }

        if (noOperations) {
            // "No operations." is a complete answer only when nothing else was printed. If the TV
            // also printed operation lines the output is contradictory, so it is not trustworthy.
            return if (operationLines.isEmpty()) {
                Parsed(emptyMap(), captured = true)
            } else {
                Parsed(emptyMap(), captured = false)
            }
        }

        if (operationLines.isEmpty()) {
            return Parsed(emptyMap(), captured = false)
        }

        val uidLines = operationLines.filter { isUidModeLine(it) }
        if (uidLines.isNotEmpty()) {
            // A single, purely UID-level line means the package itself has no override. As soon as
            // there is more than one line we can no longer tell where the UID block ends and the
            // package block begins, and must not attribute inherited modes to the package.
            return if (uidLines.size == operationLines.size) {
                Parsed(emptyMap(), captured = true)
            } else {
                Parsed(emptyMap(), captured = false)
            }
        }

        val states = LinkedHashMap<String, String>()
        for (line in operationLines) {
            val parsed = parseOperationLine(line) ?: return Parsed(emptyMap(), captured = false)
            val (operation, mode) = parsed
            val existing = states[operation]
            if (existing != null && existing != mode) {
                // The same operation reported with two different modes is ambiguous.
                return Parsed(emptyMap(), captured = false)
            }
            states[operation] = mode
        }

        if (states.isEmpty()) {
            return Parsed(emptyMap(), captured = false)
        }
        return Parsed(states, captured = true)
    }

    private fun isNoOperations(line: String): Boolean =
        line.trim().trimEnd('.').equals("no operations", ignoreCase = true)

    private fun isDefaultModeLine(line: String): Boolean =
        line.startsWith("default mode:", ignoreCase = true)

    private fun isUidModeLine(line: String): Boolean =
        line.startsWith("uid mode:", ignoreCase = true)

    private fun parseOperationLine(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null

        val operation = line.substring(0, colon).trim()
        if (!CommandSafety.isSafeAppOpName(operation)) return null

        val rest = line.substring(colon + 1).trim()
        val modeEnd = rest.indexOfFirst { it == ';' || it.isWhitespace() }
        val mode = (if (modeEnd < 0) rest else rest.substring(0, modeEnd)).lowercase()
        if (mode !in ALLOWED_MODES) return null

        val suffix = if (modeEnd < 0) "" else rest.substring(modeEnd).trim()
        if (!isValidMetadataSuffix(suffix)) return null

        return operation to mode
    }

    /**
     * Accepts the optional AOSP metadata after the mode (`; time=...`, `; rejectTime=...`,
     * `; duration=...`) and a trailing `(running)`. Any unrecognised text makes the line
     * unreliable, because it could mean the mode token was not what it appeared to be.
     */
    private fun isValidMetadataSuffix(suffix: String): Boolean {
        if (suffix.isBlank()) return true

        var rest = suffix.trim()
        if (rest.endsWith("(running)")) {
            rest = rest.removeSuffix("(running)").trimEnd()
        }
        if (rest.isBlank()) return true
        if (!rest.startsWith(";")) return false

        val parts = rest.split(';')
        if (parts.first().isNotBlank()) return false
        return parts.drop(1).all { METADATA_ENTRY.matches(it.trim()) }
    }
}
