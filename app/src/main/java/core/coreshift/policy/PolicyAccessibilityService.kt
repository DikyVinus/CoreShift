package core.coreshift.policy

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.rikka.shizuku.Shizuku
import java.io.File

class PolicyAccessibilityService : AccessibilityService() {

    private val TAG = "CoreShift"

    /* ================= privilege ================= */

    private var privileged = false
    private var privilegeSource = "none"

    /* ================= state ================= */

    private var discoveryDone = false
    private var lastForegroundPkg: String? = null
    private var execCount = 0

    /* ================= timing ================= */

    private val handler = Handler(Looper.getMainLooper())
    private val DEMOTE_INTERVAL_MS = 90L * 60L * 1000L

    /* ================= package model ================= */

    private val userPkgs = HashSet<String>()

    private val EXCEPTION_PKGS = setOf(
        "com.android.settings",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.googlequicksearchbox",
        "com.android.chrome",
        "com.android.vending"
    )

    /* ================================================= */

    override fun onServiceConnected() {
        System.loadLibrary("coreshift")

        installExec("coreshift_discovery")
        installExec("coreshift_exec")
        installExec("coreshift_demote")

        privileged = when {
            hasRoot() -> {
                privilegeSource = "root"
                true
            }
            hasShizuku() -> {
                privilegeSource = "shizuku"
                true
            }
            else -> false
        }

        if (!privileged) {
            requestShizukuOnce()
            return
        }

        Log.i(TAG, "Privilege latched via $privilegeSource")

        cacheUserPackages()
        startPrivilegedRuntime()
    }

    /* ================= privilege detection ================= */

    private fun hasRoot(): Boolean {
        return try {
            Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "true"))
                .waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasShizuku(): Boolean {
        return Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestShizukuOnce() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() ==
            PackageManager.PERMISSION_GRANTED) return

        val i = Intent(this, ShizukuPermissionActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    /* ================= package model ================= */

    private fun cacheUserPackages() {
        try {
            val p = Runtime.getRuntime()
                .exec(arrayOf("pm", "list", "packages", "-3"))
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    if (it.startsWith("package:")) {
                        userPkgs.add(it.substringAfter("package:"))
                    }
                }
            }
            p.waitFor()
            Log.i(TAG, "Cached ${userPkgs.size} user packages")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cache user packages", t)
        }
    }

    private fun isRealForeground(pkg: String): Boolean {
        return pkg in userPkgs || pkg in EXCEPTION_PKGS
    }

    /* ================= runtime ================= */

    private fun startPrivilegedRuntime() {
        if (!discoveryDone) {
            execBinary("coreshift_discovery")
            discoveryDone = true
        }
        scheduleDemote()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!privileged) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastForegroundPkg) return
        if (!isRealForeground(pkg)) return

        lastForegroundPkg = pkg
        execBinary("coreshift_exec")

        execCount++
        if (execCount % 40 == 0) {
            Log.i(
                TAG,
                "coreshift_exec x$execCount " +
                    "(source=$privilegeSource, pkg=$pkg)"
            )
        }
    }

    override fun onInterrupt() {}

    /* ================= demotion ================= */

    private fun scheduleDemote() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (privileged) {
                    execBinary("coreshift_demote")
                    handler.postDelayed(this, DEMOTE_INTERVAL_MS)
                }
            }
        }, DEMOTE_INTERVAL_MS)
    }

    /* ================= exec install ================= */

    private fun runtimeAbi(): String =
        if (applicationInfo.nativeLibraryDir.contains("arm64"))
            "arm64-v8a"
        else
            "armeabi-v7a"

    private fun installExec(name: String): File {
        val binDir = File(applicationInfo.dataDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
            binDir.setExecutable(true, false)
        }

        val out = File(binDir, name)
        if (out.exists() && out.canExecute()) return out

        val abi = runtimeAbi()
        assets.open("native/$abi/$name").use { input ->
            out.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        out.setExecutable(true, false)
        out.setReadable(true, false)
        return out
    }

    private fun execBinary(name: String) {
        val bin = File(applicationInfo.dataDir + "/bin", name)
        try {
            if (privilegeSource == "root") {
                Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", bin.absolutePath))
            } else {
                Runtime.getRuntime().exec(bin.absolutePath)
            }
        } catch (_: Throwable) {}
    }
}
