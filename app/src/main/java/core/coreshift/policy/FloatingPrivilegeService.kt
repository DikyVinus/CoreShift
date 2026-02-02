package core.coreshift.policy

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import java.io.File

class FloatingPrivilegeService : Service() {

    private var wm: WindowManager? = null
    private var button: Button? = null

    override fun onCreate() {
        super.onCreate()

        // If privilege already resolved, do nothing
        if (PrivilegeResolver.resolve(this) != PrivilegeBackend.NONE) {
            stopSelf()
            return
        }

        // HARD GATE: overlay permission
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        button = Button(this).apply {
            text = "Grant CoreShift Privilege"
            setOnClickListener { requestPrivilege() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        wm!!.addView(button, params)
    }

    private fun requestPrivilege() {
        val binDir = File(filesDir, "bin").absolutePath

        try {
            ProcessBuilder("su", "-c", "id").start().waitFor()
        } catch (_: Throwable) {
            try {
                val pb = ProcessBuilder(
                    "$binDir/axerish",
                    "-c",
                    "\"whoami\""
                )
                AxerishEnv.apply(this, pb)
                pb.start().waitFor()
            } catch (_: Throwable) {
                return
            }
        }

        val backend = PrivilegeResolver.resolve(this)
        if (backend != PrivilegeBackend.NONE) {
            DiscoveryController.runOnce(this, backend)
            cleanup()
        }
    }

    private fun cleanup() {
        try {
            button?.let { wm?.removeView(it) }
        } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
