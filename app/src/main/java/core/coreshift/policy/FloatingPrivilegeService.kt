package core.coreshift.policy

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import java.io.File

class FloatingPrivilegeService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var button: Button

    override fun onCreate() {
        super.onCreate()

        if (PrivilegeResolver.resolve(this) != PrivilegeBackend.NONE) {
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

        wm.addView(button, params)
    }

    private fun requestPrivilege() {
        val binDir = File(filesDir, "bin").absolutePath
        val path = "$binDir:${System.getenv("PATH")}"

        try {
            ProcessBuilder("su", "-c", "id")
                .apply { environment()["PATH"] = path }
                .start()
                .waitFor()
        } catch (_: Throwable) {
            try {
                ProcessBuilder(
                    "$binDir/axerish",
                    "-c",
                    "\"whoami\""
                ).apply {
                    environment()["PATH"] = path
                }.start().waitFor()
            } catch (_: Throwable) {
                return
            }
        }

        if (PrivilegeResolver.resolve(this) != PrivilegeBackend.NONE) {
            wm.removeView(button)
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            wm.removeView(button)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
