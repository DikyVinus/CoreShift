package core.coreshift.policy

import android.app.Application
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.PaintDrawable
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.roundToInt

private const val FOREGROUND_STABLE_MS = 5_000L

class CoreShiftApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Runtime.install(this)
        // DO NOT start services here
    }
}

class CoreShiftAccessibility : android.accessibilityservice.AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var candidatePkg: String? = null
    private var confirmRunnable: Runnable? = null

    override fun onServiceConnected() {
        // This is the ONLY legal place to start the overlay
        startService(Intent(this, OverlayService::class.java))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg.isBlank() || pkg.startsWith("android")) return
        if (pkg == candidatePkg) return

        candidatePkg = pkg
        confirmRunnable?.let { handler.removeCallbacks(it) }

        val r = Runnable {
            if (pkg == candidatePkg) {
                PolicyEngine.onForeground(this, pkg)
            }
        }

        confirmRunnable = r
        handler.postDelayed(r, FOREGROUND_STABLE_MS)
    }

    override fun onInterrupt() {
        confirmRunnable?.let { handler.removeCallbacks(it) }
        confirmRunnable = null
        candidatePkg = null
    }
}

class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var icon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            48f,
            resources.displayMetrics
        ).roundToInt()

        icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_coreshift)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = PaintDrawable(Color.TRANSPARENT).apply {
                setCornerRadius(sizePx.toFloat())
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            isClickable = true
        }

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = w - sizePx
            y = h / 3
        }

        icon!!.setOnClickListener { executeOnce() }
        wm!!.addView(icon, params)
    }

    private fun executeOnce() {
        try {
            Runtime.exec(
                this,
                PrivilegeBackend.SHELL,
                "coreshift_policy_cli",
                args = listOf("boost", "manual")
            )
            Toast.makeText(this, "CoreShift executed", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "Execution failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBind(intent: Intent?) = null
}
