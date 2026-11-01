package com.faizannazir.cleanyourtv.tv.engine

/**
 * Strict tokens for values that are interpolated into shell commands. Package names and AppOps
 * identifiers come from the TV, but validating them here keeps a malformed dump from turning into
 * an arbitrary shell command.
 */
object CommandSafety {

    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val APP_OP_NAME = Regex("^[A-Z][A-Z0-9_]*$")

    fun isSafePackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    fun isSafeAppOpName(value: String): Boolean = APP_OP_NAME.matches(value)

    /**
     * Only the five modes Android actually reports may ever be written back with
     * `cmd appops set`. Anything else is treated as a corrupted or unknown value.
     */
    fun isSafeAppOpMode(value: String): Boolean = value in AppOpsSnapshot.ALLOWED_MODES
}
