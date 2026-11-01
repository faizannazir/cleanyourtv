package com.faizannazir.cleanyourtv.tv.launcher

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Structural checks on an APK before it is sent to the TV.
 *
 * This is deliberately not a signature check: a TV refuses a bad signature itself. What it must
 * never do is stream a missing, truncated or non-APK file into an install session and then report
 * the unexplained result as an install.
 */
object ApkValidator {

    /** Files above this size are not a TV launcher and would only waste the TV's storage. */
    const val MAX_APK_BYTES = 256L * 1024 * 1024

    private val ZIP_MAGICS = listOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        byteArrayOf(0x50, 0x4B, 0x05, 0x06),
        byteArrayOf(0x50, 0x4B, 0x07, 0x08)
    )

    fun validate(file: File): ApkValidationResult {
        if (!file.exists()) return ApkValidationResult.invalid("the APK file does not exist")
        if (!file.isFile) return ApkValidationResult.invalid("the APK path is not a regular file")

        val size = file.length()
        if (size == 0L) return ApkValidationResult.invalid("the APK file is empty")
        if (size > MAX_APK_BYTES) {
            return ApkValidationResult.invalid(
                "the APK is $size bytes, above the ${MAX_APK_BYTES}-byte safety limit"
            )
        }

        val header = ByteArray(4)
        try {
            file.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read < 0) return ApkValidationResult.invalid("the APK file is truncated")
                    offset += read
                }
            }
        } catch (e: IOException) {
            return ApkValidationResult.invalid("the APK could not be read: ${e.message ?: e.javaClass.simpleName}")
        }

        if (ZIP_MAGICS.none { it.contentEquals(header) }) {
            return ApkValidationResult.invalid("the file is not a ZIP/APK archive")
        }

        return try {
            ZipFile(file).use { zip ->
                val manifest = zip.getEntry("AndroidManifest.xml")
                    ?: return ApkValidationResult.invalid("the archive has no AndroidManifest.xml")
                zip.getInputStream(manifest).use { entry ->
                    if (entry.read() < 0) {
                        return ApkValidationResult.invalid("AndroidManifest.xml is empty")
                    }
                }
            }
            ApkValidationResult.Valid
        } catch (e: IOException) {
            ApkValidationResult.invalid("the archive is corrupt: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            ApkValidationResult.invalid("the archive is corrupt: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

sealed class ApkValidationResult {
    data object Valid : ApkValidationResult()
    data class Invalid(val reason: String) : ApkValidationResult()

    val isValid: Boolean get() = this is Valid

    companion object {
        fun invalid(reason: String): ApkValidationResult = Invalid(reason)
    }
}
