package com.faizannazir.cleanyourtv.tv.launcher

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Finds the launcher APK in this build.
 *
 * The APK belongs in `res/raw/clean_launcher.apk` (or `assets/clean_launcher.apk`); Gradle packages
 * it with the app. When neither exists the feature reports an unsupported state instead of
 * connecting to the TV and pretending an install happened.
 */
class PackagedLauncherApkProvider(
    private val context: Context,
    private val cacheDirectory: File
) : LauncherApkProvider {

    override fun resolve(): LauncherApkResolution {
        val rawId = runCatching {
            context.resources.getIdentifier("clean_launcher", "raw", context.packageName)
        }.getOrDefault(0)

        if (rawId != 0) {
            val stream = runCatching { context.resources.openRawResource(rawId) }.getOrNull()
                ?: return LauncherApkResolution.Unreadable("res/raw/clean_launcher.apk could not be opened")
            return stage(stream, "res/raw/clean_launcher.apk")
        }

        val assetName = ASSET_NAMES.firstOrNull { name ->
            runCatching { context.assets.open(name).close() }.isSuccess
        } ?: return LauncherApkResolution.Missing

        val stream = runCatching { context.assets.open(assetName) }.getOrNull()
            ?: return LauncherApkResolution.Unreadable("assets/$assetName could not be opened")
        return stage(stream, "assets/$assetName")
    }

    private fun stage(stream: InputStream, source: String): LauncherApkResolution {
        val target = File(cacheDirectory, "clean_launcher.apk")
        val temporary = File(cacheDirectory, "clean_launcher.apk.tmp")
        return try {
            target.parentFile?.mkdirs()
            stream.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            if (target.length() <= 0L) {
                LauncherApkResolution.Unreadable("$source is empty")
            } else {
                LauncherApkResolution.Found(LauncherApk(target, source))
            }
        } catch (e: Exception) {
            temporary.delete()
            LauncherApkResolution.Unreadable(
                "$source could not be copied: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private companion object {
        val ASSET_NAMES = listOf("clean_launcher.apk", "clean_launcher/clean_launcher.apk")
    }
}
