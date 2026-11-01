package com.faizannazir.cleanyourtv.tv.launcher

import android.content.Context
import com.faizannazir.cleanyourtv.tv.adb.AdbConnection
import com.faizannazir.cleanyourtv.tv.adb.InstallResult
import java.io.FileInputStream

/** [LauncherTransport] over the app's own ADB client. */
class AdbLauncherTransport(
    context: Context,
    host: String,
    port: Int
) : LauncherTransport {

    private val connection = AdbConnection(context, host, port)

    override fun connect(): Boolean = connection.connect()

    override fun shell(command: String): String = connection.executeShellCommand(command)

    override fun install(apk: LauncherApk): InstallResult =
        FileInputStream(apk.file).use { input ->
            connection.installPackage(
                sessionName = "base.apk",
                apkSize = apk.size,
                packageToVerify = CLEAN_LAUNCHER_PACKAGE,
                apkInputStream = input
            )
        }

    override fun close() = connection.disconnect()
}
