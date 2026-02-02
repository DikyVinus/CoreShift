package core.coreshift.policy

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.rikka.shizuku.Shizuku
import java.io.File

class PolicyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        System.loadLibrary("coreshift")

        installExec("coreshift_discovery")
        installExec("coreshift_exec")
        installExec("coreshift_demote")

        if (Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission()
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {

            val i = Intent(this, ShizukuPermissionActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
    }

    override fun onInterrupt() {}

    private fun runtimeAbi(): String {
        return if (applicationInfo.nativeLibraryDir.contains("arm64")) {
            "arm64-v8a"
        } else {
            "armeabi-v7a"
        }
    }

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
}
