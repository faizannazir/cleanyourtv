package com.faizannazir.cleanyourtv.tv.launcher

import com.faizannazir.cleanyourtv.tv.adb.InstallResult
import com.faizannazir.cleanyourtv.tv.engine.ShellOutput
import java.io.File

const val CLEAN_LAUNCHER_PACKAGE = "com.gathin.tvhome"
const val CLEAN_LAUNCHER_ACTIVITY = "$CLEAN_LAUNCHER_PACKAGE/.MainActivity"

/**
 * Launchers this workflow is willing to disable, and only the one that owns Home at the time.
 *
 * The list matches the repository's `launcher/build-and-install.sh`; anything else (a chooser, a
 * setup wizard, an unknown third-party launcher) is left enabled.
 */
val KNOWN_STOCK_LAUNCHERS: Set<String> = setOf(
    "com.google.android.tvlauncher",
    "com.google.android.apps.tv.launcherx",
    "com.google.android.leanbacklauncher",
    "com.mitv.tvhome"
)

/** The launcher APK to deploy, already extracted to a readable file. */
data class LauncherApk(val file: File, val source: String) {
    val size: Long get() = file.length()
}

sealed class LauncherApkResolution {
    data class Found(val apk: LauncherApk) : LauncherApkResolution()
    data object Missing : LauncherApkResolution()
    data class Unreadable(val message: String) : LauncherApkResolution()
}

/** Finds the launcher APK in this build: a raw resource, an asset, or nothing at all. */
fun interface LauncherApkProvider {
    fun resolve(): LauncherApkResolution
}

/** The ADB operations the deployment needs. Implementations must never fake a result. */
interface LauncherTransport : AutoCloseable {
    fun connect(): Boolean
    fun shell(command: String): String
    fun install(apk: LauncherApk): InstallResult
    override fun close()
}

data class DeploymentOutcome(
    val status: Status,
    val log: String,
    val recovery: String? = null
) {
    enum class Status { COMPLETED, FAILED, UNCERTAIN, UNSUPPORTED }

    /** The text the console view shows, including the recovery instructions. */
    fun asConsoleText(): String = buildString {
        append(log.trimEnd())
        if (!recovery.isNullOrBlank()) {
            append("\n\nRECOVERY:\n")
            append(recovery.trim())
        }
    }
}

/**
 * Installs Clean Launcher and hands Home over to it, one confirmed step at a time.
 *
 * Safety rules, in order:
 *  - nothing is contacted until a packaged APK exists and passes [ApkValidator];
 *  - an install is never treated as done without the package service's confirmation and a package
 *    listing;
 *  - the new launcher must start (`Status: ok`) and Home must resolve to it before any stock
 *    launcher is disabled;
 *  - only the stock launcher that owned Home is disabled, and Home is checked again afterwards;
 *  - an uncertain ADB response stops the workflow with every remaining stock launcher untouched;
 *  - the transport is closed in `finally` on every path.
 */
class LauncherDeploymentWorkflow(
    private val apkProvider: LauncherApkProvider,
    private val transportFactory: () -> LauncherTransport,
    private val validator: ApkValidator = ApkValidator,
    private val stockLaunchers: Set<String> = KNOWN_STOCK_LAUNCHERS
) {

    fun deploy(): DeploymentOutcome {
        val log = StringBuilder()
        log.append("Clean Launcher Deployment Log\n")
        log.append("-------------------------------------------\n")

        val apk = when (val resolution = apkProvider.resolve()) {
            is LauncherApkResolution.Found -> resolution.apk
            LauncherApkResolution.Missing -> return DeploymentOutcome(
                DeploymentOutcome.Status.UNSUPPORTED,
                log.append(
                    "This build does not package the Clean Launcher APK.\n" +
                            "Expected res/raw/clean_launcher.apk or assets/clean_launcher.apk.\n" +
                            "Nothing was sent to the TV and no launcher was changed."
                ).toString(),
                "Build the launcher (launcher/ in this repository) and package its APK as " +
                        "res/raw/clean_launcher.apk, then run this step again."
            )
            is LauncherApkResolution.Unreadable -> return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "The packaged Clean Launcher APK could not be read: ${resolution.message}\n" +
                            "Nothing was sent to the TV and no launcher was changed."
                ).toString(),
                "Reinstall this app so its packaged resources are restored."
            )
        }

        log.append("APK source: ${apk.source} (${apk.size} bytes)\n")

        when (val validation = validator.validate(apk.file)) {
            ApkValidationResult.Valid -> log.append("APK check: valid ZIP with AndroidManifest.xml.\n")
            is ApkValidationResult.Invalid -> return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "APK check failed: ${validation.reason}.\n" +
                            "Nothing was sent to the TV and no launcher was changed."
                ).toString(),
                "Package a complete, readable launcher APK (it must contain AndroidManifest.xml)."
            )
        }

        val transport = try {
            transportFactory()
        } catch (e: Exception) {
            return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append("Could not create the ADB transport: ${describe(e)}\n").toString(),
                "Nothing was sent to the TV. Try again."
            )
        }

        return try {
            deployWith(apk, transport, log)
        } catch (e: Exception) {
            DeploymentOutcome(
                DeploymentOutcome.Status.UNCERTAIN,
                log.append("The deployment stopped on an unexpected error: ${describe(e)}\n").toString(),
                RECOVERY_CHECK_TV
            )
        } finally {
            try {
                transport.close()
            } catch (_: Exception) {
                // The TV state is what matters; a failed close is reported by the next connect.
            }
        }
    }

    private fun deployWith(
        apk: LauncherApk,
        transport: LauncherTransport,
        log: StringBuilder
    ): DeploymentOutcome {
        if (!transport.connect()) {
            return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "Could not connect to the TV ADB service.\n" +
                            "Nothing was installed and no launcher was changed."
                ).toString(),
                "Turn on Network debugging (or Wireless debugging) in the TV's Developer options, " +
                        "accept the debugging prompt on the TV, then run this step again."
            )
        }
        log.append("ADB connection: authenticated.\n")

        when (val install = transport.install(apk)) {
            is InstallResult.Confirmed -> log.append(
                "Install: confirmed by the TV (session ${install.sessionId}).\n"
            )
            is InstallResult.Failed -> return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "Install failed: ${install.message}\n" +
                            "No Home setting was changed and no stock launcher was disabled."
                ).toString(),
                RECOVERY_NO_STOCK_TOUCHED
            )
            is InstallResult.Uncertain -> return DeploymentOutcome(
                DeploymentOutcome.Status.UNCERTAIN,
                log.append(
                    "Install: ${install.message}\n" +
                            "No Home setting was changed and no stock launcher was disabled."
                ).toString(),
                RECOVERY_NO_STOCK_TOUCHED + " Check whether $CLEAN_LAUNCHER_PACKAGE appears on the TV before retrying."
            )
        }

        val listed = transport.shell("pm list packages $CLEAN_LAUNCHER_PACKAGE")
        when {
            ShellOutput.isUncertain(listed) -> return uncertainStop(
                log, "Checking the installed package", listed
            )
            ShellOutput.isFailure(listed) -> return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "The TV did not confirm the installed package: ${ShellOutput.clean(listed)}\n" +
                            "No Home setting was changed and no stock launcher was disabled."
                ).toString(),
                RECOVERY_NO_STOCK_TOUCHED
            )
            !ShellOutput.parsePackageList(listed).contains(CLEAN_LAUNCHER_PACKAGE) -> return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "The install was reported, but $CLEAN_LAUNCHER_PACKAGE is not listed as installed.\n" +
                            "No Home setting was changed and no stock launcher was disabled."
                ).toString(),
                RECOVERY_NO_STOCK_TOUCHED
            )
            else -> log.append("Package check: $CLEAN_LAUNCHER_PACKAGE is installed.\n")
        }

        // The permissions are conveniences; a clean refusal is not a reason to stop a Home handoff.
        for (permission in OPTIONAL_PERMISSIONS) {
            val result = transport.shell("pm grant $CLEAN_LAUNCHER_PACKAGE $permission")
            when {
                ShellOutput.isUncertain(result) -> return uncertainStop(
                    log, "Granting $permission", result
                )
                ShellOutput.isFailure(result) -> log.append("Permission not granted: $permission.\n")
                else -> log.append("Permission granted: $permission.\n")
            }
        }

        val start = transport.shell(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $CLEAN_LAUNCHER_ACTIVITY"
        )
        if (ShellOutput.isUncertain(start)) return uncertainStop(log, "Starting Clean Launcher", start)
        val started = ShellOutput.clean(start)
        if (ShellOutput.isFailure(start) || !started.contains("Status: ok")) {
            return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "Clean Launcher did not start: ${started.ifBlank { "no output" }}\n" +
                            "No Home setting was changed and no stock launcher was disabled."
                ).toString(),
                RECOVERY_NO_STOCK_TOUCHED
            )
        }
        log.append("Start check: Clean Launcher reported Status: ok.\n")

        val homeBefore = when (val lookup = readHomeOwner(transport)) {
            is HomeLookup.Found -> lookup.owner
            is HomeLookup.Uncertain -> return uncertainStop(log, "Reading the Home app", lookup.raw)
            is HomeLookup.Unreadable -> return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "Could not read which app currently owns Home: ${lookup.raw}\n" +
                            "No Home setting was changed and no stock launcher was disabled."
                ).toString(),
                RECOVERY_NO_STOCK_TOUCHED
            )
        }
        log.append("Home before handoff: $homeBefore\n")

        val setHome = transport.shell(
            "cmd package set-home-activity --user 0 $CLEAN_LAUNCHER_ACTIVITY"
        )
        if (ShellOutput.isUncertain(setHome)) return uncertainStop(log, "Setting the Home activity", setHome)
        if (ShellOutput.isFailure(setHome)) {
            return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "The TV refused to set Clean Launcher as Home: ${ShellOutput.clean(setHome)}\n" +
                            "No stock launcher was disabled."
                ).toString(),
                RECOVERY_NO_STOCK_TOUCHED + " Set Home to CleanYourTV in the TV's Settings > Apps > Default apps."
            )
        }

        val homeAfter = when (val lookup = readHomeOwner(transport)) {
            is HomeLookup.Found -> lookup.owner
            is HomeLookup.Uncertain -> return uncertainStop(log, "Reading the Home app", lookup.raw)
            is HomeLookup.Unreadable -> return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "Could not read which app owns Home after the handoff: ${lookup.raw}\n" +
                            "No stock launcher was disabled."
                ).toString(),
                RECOVERY_NO_STOCK_TOUCHED
            )
        }
        log.append("Home after handoff: $homeAfter\n")
        if (homeAfter != CLEAN_LAUNCHER_PACKAGE) {
            return DeploymentOutcome(
                DeploymentOutcome.Status.FAILED,
                log.append(
                    "Home handoff was not confirmed: Home resolves to $homeAfter, not $CLEAN_LAUNCHER_PACKAGE.\n" +
                            "No stock launcher was disabled."
                ).toString(),
                RECOVERY_NO_STOCK_TOUCHED + " Choose CleanYourTV as Home in the TV's Settings."
            )
        }

        val stockToDisable = homeBefore.takeIf { it in stockLaunchers }
        if (stockToDisable == null) {
            log.append(
                "No stock launcher was disabled: $homeBefore is not one of the known stock launchers " +
                        "this app is allowed to disable.\n"
            )
            return DeploymentOutcome(
                DeploymentOutcome.Status.COMPLETED,
                log.toString(),
                null
            )
        }

        val disable = transport.shell("pm disable-user --user 0 $stockToDisable")
        if (ShellOutput.isUncertain(disable)) {
            return uncertainStop(
                log,
                "Disabling $stockToDisable",
                disable,
                " Re-enable it with: pm enable --user 0 $stockToDisable"
            )
        }
        if (ShellOutput.isFailure(disable)) {
            log.append(
                "The stock launcher $stockToDisable was not disabled: ${ShellOutput.clean(disable)}\n" +
                        "CleanYourTV remains the confirmed Home app.\n"
            )
            return DeploymentOutcome(DeploymentOutcome.Status.COMPLETED, log.toString(), null)
        }
        log.append("Stock launcher disabled: $stockToDisable\n")

        // Disabling a HOME app drops Android's Home preference, so set and re-check it.
        val reassert = transport.shell(
            "cmd package set-home-activity --user 0 $CLEAN_LAUNCHER_ACTIVITY"
        )
        if (ShellOutput.isUncertain(reassert)) {
            return rollBack(
                log,
                transport,
                stockToDisable,
                "Home could not be re-confirmed after disabling $stockToDisable"
            )
        }

        val homeFinal = when (val lookup = readHomeOwner(transport)) {
            is HomeLookup.Found -> lookup.owner
            is HomeLookup.Uncertain -> return rollBack(
                log,
                transport,
                stockToDisable,
                "Home could not be re-read after disabling $stockToDisable"
            )
            is HomeLookup.Unreadable -> return rollBack(
                log,
                transport,
                stockToDisable,
                "Home could not be read after disabling $stockToDisable: ${lookup.raw}"
            )
        }
        if (homeFinal != CLEAN_LAUNCHER_PACKAGE) {
            return rollBack(
                log,
                transport,
                stockToDisable,
                "Home resolves to $homeFinal after disabling $stockToDisable"
            )
        }

        log.append("Home check: $homeFinal owns Home.\n")
        log.append("Deployment complete.\n")
        return DeploymentOutcome(DeploymentOutcome.Status.COMPLETED, log.toString(), null)
    }

    private fun readHomeOwner(transport: LauncherTransport): HomeLookup {
        val raw = transport.shell(HOME_QUERY)
        if (ShellOutput.isUncertain(raw)) return HomeLookup.Uncertain(raw)
        if (ShellOutput.isFailure(raw)) return HomeLookup.Unreadable(ShellOutput.clean(raw))
        val line = ShellOutput.clean(raw)
            .lines()
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            ?: return HomeLookup.Unreadable("empty response")
        val owner = line.substringBefore('/').trim()
        return if (owner.isEmpty()) HomeLookup.Unreadable("empty component") else HomeLookup.Found(owner)
    }

    private sealed class HomeLookup {
        data class Found(val owner: String) : HomeLookup()
        data class Uncertain(val raw: String) : HomeLookup()
        data class Unreadable(val raw: String) : HomeLookup()
    }

    private fun uncertainStop(
        log: StringBuilder,
        step: String,
        raw: String,
        extraRecovery: String = ""
    ): DeploymentOutcome {
        log.append(
            "$step was interrupted: ${ShellOutput.clean(raw).ifBlank { "no complete response" }}\n" +
                    "The command may or may not have run, so the workflow stopped and did not retry it.\n"
        )
        return DeploymentOutcome(
            DeploymentOutcome.Status.UNCERTAIN,
            log.toString(),
            RECOVERY_CHECK_TV + extraRecovery
        )
    }

    private fun rollBack(
        log: StringBuilder,
        transport: LauncherTransport,
        stockLauncher: String,
        reason: String
    ): DeploymentOutcome {
        log.append("$reason.\n")
        val enable = runCatching { transport.shell("pm enable --user 0 $stockLauncher") }
            .getOrDefault("Error: transport closed")

        val restoredHome = runCatching {
            val component = transport.shell(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                        "-c android.intent.category.HOME $stockLauncher"
            )
            if (ShellOutput.isUncertain(component) || ShellOutput.isFailure(component)) {
                null
            } else {
                ShellOutput.clean(component).lines().lastOrNull { it.isNotBlank() }?.trim()
            }
        }.getOrNull()

        if (restoredHome != null) {
            runCatching {
                transport.shell("cmd package set-home-activity --user 0 $restoredHome")
            }
        }

        val detail = when {
            ShellOutput.isUncertain(enable) ->
                "Re-enabling $stockLauncher was interrupted and needs a manual check."
            ShellOutput.isFailure(enable) ->
                "$stockLauncher could not be re-enabled: ${ShellOutput.clean(enable)}"
            else ->
                "$stockLauncher was re-enabled."
        }
        log.append("$detail\n")

        return DeploymentOutcome(
            DeploymentOutcome.Status.UNCERTAIN,
            log.toString(),
            "Check Home on the TV. If CleanYourTV does not appear, restore the stock launcher with: " +
                    "pm enable --user 0 $stockLauncher, then set its Home activity. " +
                    "$detail"
        )
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    companion object {
        private const val HOME_QUERY =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

        private val OPTIONAL_PERMISSIONS = listOf(
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION"
        )

        private const val RECOVERY_NO_STOCK_TOUCHED =
            "Every stock launcher is still enabled. Fix the cause, then run this step again."

        private const val RECOVERY_CHECK_TV =
            "Check Home on the TV before trying again. If CleanYourTV is missing, choose the stock " +
                    "launcher in the TV's Settings > Apps > Default apps."
    }
}
