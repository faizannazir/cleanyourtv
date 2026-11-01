package com.faizannazir.cleanyourtv.tv

import com.faizannazir.cleanyourtv.tv.adb.AdbConnection
import com.faizannazir.cleanyourtv.tv.adb.InstallResult
import com.faizannazir.cleanyourtv.tv.launcher.DeploymentOutcome
import com.faizannazir.cleanyourtv.tv.launcher.LauncherApk
import com.faizannazir.cleanyourtv.tv.launcher.LauncherApkResolution
import com.faizannazir.cleanyourtv.tv.launcher.LauncherDeploymentWorkflow
import com.faizannazir.cleanyourtv.tv.launcher.LauncherTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The deployment rules, exercised with a scripted transport. Tests never open a socket or run an
 * install command against a TV.
 */
class LauncherDeploymentWorkflowTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun missingPackagedApkIsAnHonestUnsupportedState() {
        var factoryCalls = 0
        val workflow = workflow(
            apkProvider = { LauncherApkResolution.Missing },
            transportFactory = {
                factoryCalls++
                FakeTransport()
            }
        )

        val outcome = workflow.deploy()

        assertEquals(DeploymentOutcome.Status.UNSUPPORTED, outcome.status)
        assertEquals(0, factoryCalls)
        assertTrue(outcome.log.contains("does not package"))
        assertTrue(outcome.asConsoleText().contains("Nothing was sent to the TV"))
    }

    @Test
    fun unreadablePackagedApkDoesNotContactTheTv() {
        var factoryCalls = 0
        val workflow = workflow(
            apkProvider = { LauncherApkResolution.Unreadable("bad resource") },
            transportFactory = {
                factoryCalls++
                FakeTransport()
            }
        )

        val outcome = workflow.deploy()

        assertEquals(DeploymentOutcome.Status.FAILED, outcome.status)
        assertEquals(0, factoryCalls)
        assertTrue(outcome.log.contains("bad resource"))
    }

    @Test
    fun invalidApkIsRejectedBeforeConnecting() {
        val invalid = folder.newFile("not-an-apk.apk").apply { writeText("not a zip") }
        var factoryCalls = 0
        val workflow = workflow(
            apkProvider = { LauncherApkResolution.Found(LauncherApk(invalid, "test")) },
            transportFactory = {
                factoryCalls++
                FakeTransport()
            }
        )

        val outcome = workflow.deploy()

        assertEquals(DeploymentOutcome.Status.FAILED, outcome.status)
        assertEquals(0, factoryCalls)
        assertTrue(outcome.log.contains("APK check failed"))
    }

    @Test
    fun failedInstallStopsBeforeHomeOrStockChanges() {
        val tv = FakeTransport(installResult = InstallResult.Failed("INSTALL_FAILED_INVALID_APK"))
        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.FAILED, outcome.status)
        assertTrue(tv.closed)
        assertFalse(tv.commands.any { it.startsWith("cmd package set-home-activity") })
        assertFalse(tv.commands.any { it.startsWith("pm disable-user") })
        assertTrue(outcome.asConsoleText().contains("Every stock launcher is still enabled"))
    }

    @Test
    fun uncertainInstallNeverDisablesAStockLauncher() {
        val tv = FakeTransport(installResult = InstallResult.Uncertain("the transfer broke"))
        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.UNCERTAIN, outcome.status)
        assertTrue(tv.closed)
        assertFalse(tv.commands.any { it.startsWith("pm disable-user") })
        assertFalse(tv.commands.any { it.startsWith("cmd package set-home-activity") })
    }

    @Test
    fun installThatDoesNotListThePackageStops() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"), installed = false)
        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.FAILED, outcome.status)
        assertTrue(tv.closed)
        assertFalse(tv.commands.any { it.startsWith("pm disable-user") })
    }

    @Test
    fun failedHomeHandoffLeavesEveryStockLauncherEnabled() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"))
        tv.uncertainWhen = { it.startsWith("cmd package set-home-activity") }
        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.UNCERTAIN, outcome.status)
        assertTrue(tv.closed)
        assertFalse(tv.commands.any { it.startsWith("pm disable-user") })
    }

    @Test
    fun homeThatDoesNotResolveToCleanLauncherStopsTheHandoff() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"))
        tv.honourSetHome = false
        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.FAILED, outcome.status)
        assertTrue(tv.closed)
        assertFalse(tv.commands.any { it.startsWith("pm disable-user") })
    }

    @Test
    fun launcherThatDoesNotStartLeavesEveryStockLauncherEnabled() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"))
        tv.startSucceeds = false

        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.FAILED, outcome.status)
        assertTrue(tv.closed)
        assertFalse(tv.commands.any { it.startsWith("pm disable-user") })
    }

    @Test
    fun successfulDeploymentDisablesOnlyTheCurrentStockHome() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"))
        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.COMPLETED, outcome.status)
        assertTrue(tv.closed)
        assertTrue(
            tv.commands.contains("pm disable-user --user 0 com.google.android.tvlauncher")
        )
        assertFalse(tv.disabled.contains("com.google.android.apps.tv.launcherx"))
        assertEquals("com.gathin.tvhome", tv.homeOwner)
    }

    @Test
    fun unknownHomeOwnerIsNeverDisabled() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"))
        tv.homeOwner = "com.example.thirdparty.launcher"

        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.COMPLETED, outcome.status)
        assertFalse(tv.commands.any { it.startsWith("pm disable-user") })
        assertEquals("com.gathin.tvhome", tv.homeOwner)
    }

    @Test
    fun uncertainDisableStopsAndReportsRecovery() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"))
        tv.uncertainWhen = { it.startsWith("pm disable-user") }
        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.UNCERTAIN, outcome.status)
        assertTrue(tv.closed)
        assertTrue(outcome.asConsoleText().contains("pm enable --user 0 com.google.android.tvlauncher"))
        assertEquals(1, tv.commands.count { it.startsWith("pm disable-user") })
    }

    @Test
    fun lostHomeAfterDisablingRollsTheStockLauncherBack() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"))
        tv.loseHomeOnDisable = true

        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.UNCERTAIN, outcome.status)
        assertTrue(tv.closed)
        assertTrue(tv.commands.contains("pm enable --user 0 com.google.android.tvlauncher"))
        assertFalse(tv.disabled.contains("com.google.android.tvlauncher"))
    }

    @Test
    fun transportIsClosedWhenAShellCommandThrows() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"))
        tv.throwOnCommandPrefix = "am start"

        val outcome = workbookOutcome(validApk(), tv)

        assertEquals(DeploymentOutcome.Status.UNCERTAIN, outcome.status)
        assertTrue(tv.closed)
    }

    @Test
    fun connectionFailureIsReportedWithoutTouchingTheTv() {
        val tv = FakeTransport(installResult = InstallResult.Confirmed(1, "Success"), connect = false)
        val outcome = workflowFor(validApk(), tv).deploy()

        assertEquals(DeploymentOutcome.Status.FAILED, outcome.status)
        assertTrue(tv.closed)
        assertTrue(tv.commands.isEmpty())
        assertTrue(outcome.asConsoleText().contains("Network debugging"))
    }

    // ---------------------------------------------------------------- helpers

    private fun workbookOutcome(apk: File, tv: FakeTransport): DeploymentOutcome =
        workflowFor(apk, tv).deploy()

    private fun workflowFor(apk: File, tv: FakeTransport): LauncherDeploymentWorkflow =
        workflow(
            apkProvider = { LauncherApkResolution.Found(LauncherApk(apk, "test")) },
            transportFactory = { tv }
        )

    private fun workflow(
        apkProvider: () -> LauncherApkResolution,
        transportFactory: () -> LauncherTransport
    ): LauncherDeploymentWorkflow = LauncherDeploymentWorkflow(apkProvider, transportFactory)

    private fun validApk(): File {
        val file = folder.newFile("clean_launcher.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zip.closeEntry()
        }
        return file
    }

    private class FakeTransport(
        private val installResult: InstallResult = InstallResult.Confirmed(1, "Success"),
        private val connect: Boolean = true,
        private val installed: Boolean = true
    ) : LauncherTransport {

        var uncertainWhen: (String) -> Boolean = { false }
        val commands = mutableListOf<String>()
        val disabled = mutableSetOf<String>()
        var closed = false
        var connected = false
        var installedApk: LauncherApk? = null
        var homeOwner = "com.google.android.tvlauncher"
        var startSucceeds = true
        var honourSetHome = true
        var loseHomeOnDisable = false
        var homeLost = false
        var throwOnCommandPrefix: String? = null

        override fun connect(): Boolean {
            connected = true
            return connect
        }

        override fun shell(command: String): String {
            commands += command
            if (throwOnCommandPrefix != null && command.startsWith(throwOnCommandPrefix!!)) {
                throw IllegalStateException("scripted transport failure")
            }
            if (uncertainWhen(command)) {
                return "Error: ${AdbConnection.UNCERTAIN_MARKER} - scripted break"
            }
            return when {
                command.startsWith("pm list packages") ->
                    if (installed) "Success: package:com.gathin.tvhome" else AdbConnection.EMPTY_OUTPUT_NOTICE
                command.startsWith("pm grant") -> AdbConnection.EMPTY_OUTPUT_NOTICE
                command.startsWith("cmd package set-home-activity") -> {
                    val component = command.substringAfterLast(' ')
                    if (honourSetHome && !homeLost) {
                        homeOwner = component.substringBefore('/')
                    }
                    AdbConnection.EMPTY_OUTPUT_NOTICE
                }
                command.startsWith("cmd package resolve-activity") -> {
                    val last = command.substringAfterLast(' ')
                    val owner = if (last.startsWith("android.")) homeOwner else last.substringBefore('/')
                    "Success: $owner/.MainActivity"
                }
                command.startsWith("am start") ->
                    if (startSucceeds) {
                        "Success: Starting: Intent { act=android.intent.action.MAIN }\nStatus: ok"
                    } else {
                        "Success: Error: Activity not started, unable to resolve Intent"
                    }
                command.startsWith("pm disable-user") -> {
                    val packageName = command.substringAfterLast(' ')
                    disabled += packageName
                    if (loseHomeOnDisable) {
                        homeLost = true
                        homeOwner = "android"
                        // Disabling the HOME app drops the preference; the workflow's re-assert
                        // cannot restore it in this scripted failure.
                    }
                    AdbConnection.EMPTY_OUTPUT_NOTICE
                }
                command.startsWith("pm enable") -> {
                    val packageName = command.substringAfterLast(' ')
                    disabled -= packageName
                    if (packageName == "com.google.android.tvlauncher") {
                        homeOwner = "com.google.android.tvlauncher"
                        homeLost = false
                    }
                    AdbConnection.EMPTY_OUTPUT_NOTICE
                }
                else -> AdbConnection.EMPTY_OUTPUT_NOTICE
            }
        }

        override fun install(apk: LauncherApk): InstallResult {
            installedApk = apk
            return installResult
        }

        override fun close() {
            closed = true
        }
    }
}
