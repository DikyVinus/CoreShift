package core.coreshift.policy

import android.app.*
import android.accessibilityservice.AccessibilityService
import android.content.*
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button

class CoreShiftApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Runtime.install(this)

        val backend = Runtime.resolvePrivilege(this)
        if (backend == PrivilegeBackend.NONE) {
            startService(Intent(this, OverlayService::class.java))
        } else {
            Policy.discoveryOnce(this, backend)
        }
    }
}

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        finish()
    }
}

class CoreShiftAccessibility : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        Policy.onForeground(this, pkg)
    }
    override fun onInterrupt() {}
}

class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var button: Button? = null

    override fun onCreate() {
        super.onCreate()

        if (Runtime.resolvePrivilege(this) != PrivilegeBackend.NONE ||
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf(); return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        button = Button(this).apply {
            text = "Grant CoreShift Privilege"
            setOnClickListener { request() }
        }

        wm!!.addView(
            button,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        )
    }

    private fun request() {
        Runtime.resolvePrivilege(this)
        val backend = Runtime.resolvePrivilege(this)
        if (backend != PrivilegeBackend.NONE) {
            Policy.discoveryOnce(this, backend)
            cleanup()
        }
    }

    private fun cleanup() {
        try { button?.let { wm?.removeView(it) } } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onBind(intent: Intent?) = null
}
