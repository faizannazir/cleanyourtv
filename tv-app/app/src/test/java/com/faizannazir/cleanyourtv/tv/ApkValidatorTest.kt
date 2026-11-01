package com.faizannazir.cleanyourtv.tv

import com.faizannazir.cleanyourtv.tv.launcher.ApkValidationResult
import com.faizannazir.cleanyourtv.tv.launcher.ApkValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkValidatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun validApkWithManifestPasses() {
        assertTrue(ApkValidator.validate(apkWithManifest()).isValid)
    }

    @Test
    fun missingFileIsRejected() {
        val result = ApkValidator.validate(File(folder.root, "absent.apk"))
        assertFalse(result.isValid)
        assertTrue((result as ApkValidationResult.Invalid).reason.contains("does not exist"))
    }

    @Test
    fun emptyFileIsRejected() {
        val file = folder.newFile("empty.apk")
        val result = ApkValidator.validate(file)
        assertFalse(result.isValid)
        assertTrue((result as ApkValidationResult.Invalid).reason.contains("empty"))
    }

    @Test
    fun plainTextIsNotAnApk() {
        val file = folder.newFile("notes.apk")
        file.writeText("this is not a zip archive")
        val result = ApkValidator.validate(file)
        assertFalse(result.isValid)
        assertTrue((result as ApkValidationResult.Invalid).reason.contains("ZIP"))
    }

    @Test
    fun zipWithoutAndroidManifestIsRejected() {
        val file = folder.newFile("without-manifest.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val result = ApkValidator.validate(file)
        assertFalse(result.isValid)
        assertTrue((result as ApkValidationResult.Invalid).reason.contains("AndroidManifest.xml"))
    }

    @Test
    fun truncatedZipIsRejected() {
        val valid = apkWithManifest()
        val bytes = valid.readBytes()
        val truncated = folder.newFile("truncated.apk")
        truncated.writeBytes(bytes.copyOf(bytes.size / 2))

        val result = ApkValidator.validate(truncated)

        assertFalse(result.isValid)
    }

    private fun apkWithManifest(): File {
        val file = folder.newFile("clean_launcher.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zip.closeEntry()
        }
        return file
    }
}
