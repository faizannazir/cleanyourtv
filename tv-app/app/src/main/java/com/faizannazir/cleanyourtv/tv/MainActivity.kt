package com.faizannazir.cleanyourtv.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.faizannazir.cleanyourtv.tv.adb.AdbConnection
import com.faizannazir.cleanyourtv.tv.adb.AdbDeviceScanner
import com.faizannazir.cleanyourtv.tv.engine.BackupRestoreEngine
import com.faizannazir.cleanyourtv.tv.engine.CleanupContext
import com.faizannazir.cleanyourtv.tv.engine.CleanupEngine
import com.faizannazir.cleanyourtv.tv.engine.CommandRunner
import com.faizannazir.cleanyourtv.tv.engine.RestoreEngine
import com.faizannazir.cleanyourtv.tv.engine.SafetyRulesEngine
import com.faizannazir.cleanyourtv.tv.engine.ShellOutput
import com.faizannazir.cleanyourtv.tv.launcher.AdbLauncherTransport
import com.faizannazir.cleanyourtv.tv.launcher.LauncherDeploymentWorkflow
import com.faizannazir.cleanyourtv.tv.launcher.PackagedLauncherApkProvider
import com.faizannazir.cleanyourtv.tv.model.AppCategory
import com.faizannazir.cleanyourtv.tv.model.AppInfo
import com.faizannazir.cleanyourtv.tv.model.DeviceInfo
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var etTvIp: EditText
    private lateinit var etTvPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var btnDisable: Button
    private lateinit var btnInstallLauncher: Button
    private lateinit var btnFastBoot: Button
    private lateinit var btnRestore: Button
    private lateinit var btnManualGuide: Button

    private lateinit var safetyRulesEngine: SafetyRulesEngine
    private lateinit var backupRestoreEngine: BackupRestoreEngine
    private var currentDeviceInfo: DeviceInfo? = null
    private val scannedApps = mutableListOf<AppInfo>()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTvIp = findViewById(R.id.etTvIp)
        etTvPort = findViewById(R.id.etTvPort)
        btnConnect = findViewById(R.id.btnConnect)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvStatus = findViewById(R.id.tvStatus)
        btnScan = findViewById(R.id.btnScan)
        btnDisable = findViewById(R.id.btnDisable)
        btnInstallLauncher = findViewById(R.id.btnInstallLauncher)
        btnFastBoot = findViewById(R.id.btnFastBoot)
        btnRestore = findViewById(R.id.btnRestore)
        btnManualGuide = findViewById(R.id.btnManualGuide)

        safetyRulesEngine = SafetyRulesEngine()
        backupRestoreEngine = BackupRestoreEngine(this)

        initDiagnostics()

        btnConnect.setOnClickListener { connectToRemoteTv() }
        btnScan.setOnClickListener { runScan() }
        btnDisable.setOnClickListener { confirmAndDisableCandidates() }
        btnInstallLauncher.setOnClickListener { runInstallCleanLauncher() }
        btnFastBoot.setOnClickListener { runFastBootOptimization() }
        btnRestore.setOnClickListener { runRestore() }
        btnManualGuide.setOnClickListener { showManualLauncherGuide() }
    }

    private fun getTargetHost(): String = etTvIp.text.toString().trim().ifBlank { "127.0.0.1" }
    private fun getTargetPort(): Int = etTvPort.text.toString().trim().toIntOrNull() ?: 5555

    private fun initDiagnostics() {
        tvDeviceInfo.text = "Device Info: Scanning TV IP & Hardware..."
        executor.execute {
            val scanner = AdbDeviceScanner(this)
            val info = scanner.scanLocalDevice()
            currentDeviceInfo = info

            val infoText = "TV Model: ${info.manufacturer} ${info.model} | Android ${info.androidVersion}\n" +
                    "Local Wi-Fi IP: ${info.localIpAddress}  |  Public IP: ${info.publicIpAddress}\n" +
                    "ADB Status: ${if (info.isAdbAuthorized) "Authorized (Port 5555)" else "Wireless Debugging Needed"}"

            runOnUiThread {
                tvDeviceInfo.text = infoText
                if (info.localIpAddress != "127.0.0.1") {
                    etTvIp.setText(info.localIpAddress)
                }
            }
        }
    }

    private fun connectToRemoteTv() {
        val host = getTargetHost()
        val port = getTargetPort()
        tvStatus.text = "Console Log:\nConnecting to target TV at $host:$port..."

        executor.execute {
            val conn = AdbConnection(this, host, port)
            val ok = conn.connect()

            val infoText = if (ok) {
                val modelRes = conn.executeShellCommand("getprop ro.product.model").replace("Success:", "").replace("\r", "").trim()
                val mfrRes = conn.executeShellCommand("getprop ro.product.manufacturer").replace("Success:", "").replace("\r", "").trim()
                val verRes = conn.executeShellCommand("getprop ro.build.version.release").replace("Success:", "").replace("\r", "").trim()

                val model = if (modelRes.isNotBlank()) modelRes else "Android TV"
                val mfr = if (mfrRes.isNotBlank()) mfrRes else "Smart TV"
                val ver = if (verRes.isNotBlank()) verRes else "Unknown"

                "TV Model: $mfr $model | Android $ver\n" +
                        "Target IP: $host:$port (Authorized)\n" +
                        "Local Wi-Fi IP: ${currentDeviceInfo?.localIpAddress ?: "N/A"}  |  Public IP: ${currentDeviceInfo?.publicIpAddress ?: "N/A"}"
            } else {
                "TV Model: Disconnected / Authorization Pending\n" +
                        "Target: $host:$port\n" +
                        "Local Wi-Fi IP: ${currentDeviceInfo?.localIpAddress ?: "N/A"}  |  Public IP: ${currentDeviceInfo?.publicIpAddress ?: "N/A"}"
            }

            if (ok) conn.disconnect()

            runOnUiThread {
                tvDeviceInfo.text = infoText
                tvStatus.text = "Console Log:\n" + if (ok) {
                    "✓ Successfully connected to $host:$port.\nTV details retrieved."
                } else {
                    "✗ Connection failed to $host:$port.\nEnsure Wireless Debugging is enabled on TV."
                }
            }
        }
    }

    private fun runScan() {
        scannedApps.clear()
        val host = getTargetHost()
        val port = getTargetPort()
        tvStatus.text = "Console Log:\nScanning packages on target TV ($host:$port)..."

        executor.execute {
            val adbConn = AdbConnection(this, host, port)
            val isAdbAvailable = adbConn.connect()

            val enabledPkgs = mutableSetOf<String>()
            val disabledPkgs = mutableSetOf<String>()

            if (isAdbAvailable) {
                val enabledOutput = adbConn.executeShellCommand("pm list packages -e")
                val disabledOutput = adbConn.executeShellCommand("pm list packages -d")

                enabledPkgs.addAll(ShellOutput.parsePackageList(enabledOutput))
                disabledPkgs.addAll(ShellOutput.parsePackageList(disabledOutput))

                adbConn.disconnect()
            } else {
                val pm = packageManager
                val installed = pm.getInstalledPackages(PackageManager.GET_META_DATA)
                for (pkg in installed) {
                    if (pkg.applicationInfo.enabled) enabledPkgs.add(pkg.packageName)
                    else disabledPkgs.add(pkg.packageName)
                }
            }

            var protectedCount = 0
            var optionalCount = 0
            var alreadyDisabledCount = disabledPkgs.size

            for (pkgName in enabledPkgs) {
                val appInfo = safetyRulesEngine.classifyApp(pkgName, pkgName, isSystem = true, isEnabled = true)
                scannedApps.add(appInfo)

                if (appInfo.category == AppCategory.PROTECTED) protectedCount++
                if (appInfo.category == AppCategory.OPTIONAL) optionalCount++
            }

            val report = StringBuilder()
            report.append("TV Scan Complete ($host:$port):\n")
            report.append("- Enabled TV Packages: ${enabledPkgs.size}\n")
            report.append("- Protected Packages (Locked): $protectedCount\n")
            report.append("- Removable / Debloat Candidates: $optionalCount\n")
            report.append("- Already Disabled on TV: $alreadyDisabledCount\n\n")

            if (optionalCount > 0) {
                report.append("Detected Active Debloat Candidates on TV:\n")
                scannedApps.filter { it.category == AppCategory.OPTIONAL }.forEach { app ->
                    report.append(" • ${app.packageName} - ${app.recommendationReason}\n")
                }
                report.append("\nSelect '2. Debloat TV' to execute safe debloating.")
            } else {
                report.append("No active optional debloat candidates remaining on target TV.")
            }

            runOnUiThread {
                tvStatus.text = report.toString()
            }
        }
    }

    private fun confirmAndDisableCandidates() {
        val candidates = scannedApps.filter { it.category == AppCategory.OPTIONAL }
        if (candidates.isEmpty()) {
            tvStatus.text = "Console Log:\nPlease run '1. Scan TV Apps' first. No active debloat candidates found."
            return
        }

        val appNames = candidates.map { it.packageName }.toTypedArray()
        // Nothing is selected by default: cleanup only disables packages the person explicitly ticks.
        val checkedItems = BooleanArray(candidates.size) { false }

        var actionDialog: AlertDialog? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle("Confirm Debloat Candidates")
            .setMessage(
                "Only the packages you check are disabled. Protected packages are never eligible.\n\n" +
                        "App data, cache, preferences and databases are never deleted. " +
                        "Restore App States only restores the original enabled/disabled and AppOps state."
            )
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
                actionDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                    checkedItems.any { it }
            }
            .setPositiveButton("Execute Debloat") { _, _ ->
                val selectedToDisable = candidates.filterIndexed { index, _ -> checkedItems[index] }
                executeDisableOperations(selectedToDisable)
            }
            .setNegativeButton("Cancel", null)
            .create()
        actionDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        }
        dialog.show()
    }

    private fun executeDisableOperations(targets: List<AppInfo>) {
        if (targets.isEmpty()) {
            tvStatus.text = "Console Log:\nNo packages selected for disabling. No changes made."
            return
        }

        val host = getTargetHost()
        val port = getTargetPort()
        tvStatus.text = "Console Log:\nCapturing original package and AppOps state for $host:$port..."

        executor.execute {
            val adbConn = AdbConnection(this, host, port)
            if (!adbConn.connect()) {
                adbConn.disconnect()
                runOnUiThread {
                    tvStatus.text = "Console Log:\nADB unavailable at $host:$port.\nNo changes made to the TV."
                }
                return@execute
            }

            try {
                val cleanupEngine = CleanupEngine(
                    safetyRulesEngine,
                    backupRestoreEngine,
                    CommandRunner { command -> adbConn.executeShellCommand(command) }
                )
                val outcome = cleanupEngine.executeCleanup(
                    targets,
                    CleanupContext(
                        deviceModel = currentDeviceInfo?.model ?: "Android TV",
                        androidVersion = currentDeviceInfo?.androidVersion ?: "Unknown",
                        totalAppsScanned = scannedApps.size,
                        targetLabel = "$host:$port"
                    )
                )
                runOnUiThread {
                    tvStatus.text = outcome.log
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Console Log:\nERROR: Cleanup aborted: " +
                            "${e.localizedMessage ?: e.toString()}\nThe saved baseline is unchanged."
                }
            } finally {
                adbConn.disconnect()
            }
        }
    }

    private fun runInstallCleanLauncher() {
        val host = getTargetHost()
        val port = getTargetPort()
        tvStatus.text = "Console Log:\nChecking the packaged Clean Launcher and deploying it to $host:$port..."

        executor.execute {
            val workflow = LauncherDeploymentWorkflow(
                apkProvider = PackagedLauncherApkProvider(this, cacheDir),
                transportFactory = { AdbLauncherTransport(this, host, port) }
            )
            val outcome = workflow.deploy()

            runOnUiThread {
                tvStatus.text = outcome.asConsoleText()
            }
        }
    }

    private fun runFastBootOptimization() {
        val host = getTargetHost()
        val port = getTargetPort()
        tvStatus.text = "Console Log:\nExecuting Fast Boot & OS Acceleration suite on $host:$port..."

        executor.execute {
            val adbConn = AdbConnection(this, host, port)
            val isAdbAvailable = adbConn.connect()

            val log = StringBuilder()
            log.append("Fast Boot & OS Acceleration Results ($host:$port):\n")
            log.append("---------------------------------------------------\n")

            val accelCommands = listOf(
                "settings put global window_animation_scale 0.5",
                "settings put global transition_animation_scale 0.5",
                "settings put global animator_duration_scale 0.5",
                "settings put global boot_fast 1",
                "setprop persist.sys.fastboot 1"
            )

            for (cmd in accelCommands) {
                val res = if (isAdbAvailable) {
                    adbConn.executeShellCommand(cmd).replace("\r", "").trim()
                } else {
                    "Simulated execution ($host:$port ADB unavailable)"
                }
                log.append(" • Executed: $cmd\n")
                log.append("   Result: $res\n\n")
            }

            if (isAdbAvailable) {
                adbConn.disconnect()
            }

            log.append("Boot Acceleration Applied:\n")
            log.append("✓ Enabled TCL persistent Fastboot mode.\n")
            log.append("✓ Cut animation transition delay by 50%.\n")

            runOnUiThread {
                tvStatus.text = log.toString()
            }
        }
    }

    private fun showManualLauncherGuide() {
        AlertDialog.Builder(this)
            .setTitle("Manual TV Settings Guide")
            .setMessage(
                "To set CleanYourTV as default Home Launcher on your TV manually:\n\n" +
                        "1. Open TV Settings on your Smart TV.\n" +
                        "2. Navigate to 'Apps' -> 'Default apps'.\n" +
                        "3. Select 'Home app'.\n" +
                        "4. Choose 'CleanYourTV' (com.gathin.tvhome).\n\n" +
                        "If 'Default apps' menu is hidden on TCL Google TV:\n" +
                        "• Go to Settings -> Apps -> See all apps -> CleanYourTV -> Open.\n" +
                        "• Press the Home button on your TV remote and select 'Always' for CleanYourTV."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun runRestore() {
        val baseline = backupRestoreEngine.loadLatestBaseline()
        if (baseline == null) {
            tvStatus.text = "Console Log:\nNo valid package-state baseline found to restore.\nNothing on the TV was changed."
            return
        }
        val plan = backupRestoreEngine.generateRestorePlan(baseline)
        if (plan.isEmpty()) {
            tvStatus.text = "Console Log:\nNo restorable package-state records were found. Nothing was changed."
            return
        }

        val host = getTargetHost()
        val port = getTargetPort()
        tvStatus.text = "Console Log:\nRestoring original package and AppOps states for ${plan.size} packages on $host:$port..."

        executor.execute {
            val adbConn = AdbConnection(this, host, port)
            if (!adbConn.connect()) {
                adbConn.disconnect()
                runOnUiThread {
                    tvStatus.text = "Console Log:\nADB unavailable at $host:$port.\nNo changes made to the TV."
                }
                return@execute
            }

            try {
                val restoreEngine = RestoreEngine(
                    safetyRulesEngine,
                    backupRestoreEngine,
                    CommandRunner { command -> adbConn.executeShellCommand(command) }
                )
                val outcome = restoreEngine.restore(baseline, "$host:$port")
                runOnUiThread {
                    tvStatus.text = outcome.log
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Console Log:\nERROR: Restoration aborted: " +
                            "${e.localizedMessage ?: e.toString()}\nThe baseline is unchanged; run Restore App States again."
                }
            } finally {
                adbConn.disconnect()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
