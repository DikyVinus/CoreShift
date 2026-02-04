package core.coreshift.policy

import android.app.*
import android.accessibilityservice.AccessibilityService
import android.content.*
import android.graphics.*
import android.graphics.drawable.PaintDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.roundToInt

private const val FOREGROUND_STABLE_MS = 5_000L
private const val SHELL_RETRY_DELAY_MS = 300L
private const val SHELL_RETRY_MAX = 20

class CoreShiftApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Runtime.install(this)

        val backend = Runtime.resolvePrivilege(this)
        if (backend == PrivilegeBackend.NONE) {
            startService(Intent(this, OverlayService::class.java))
        } else {
            PolicyEngine.discovery(this, backend)
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

    private val handler = Handler(Looper.getMainLooper())
    private var candidatePkg: String? = null
    private var confirmRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
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
    private var metrics: WindowMetrics? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0

    override fun onCreate() {
        super.onCreate()

        if (
            Runtime.resolvePrivilege(this) != PrivilegeBackend.NONE ||
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            metrics = wm!!.currentWindowMetrics
        }

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

        val w = metrics?.bounds?.width() ?: resources.displayMetrics.widthPixels
        val h = metrics?.bounds?.height() ?: resources.displayMetrics.heightPixels

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

        icon!!.setOnTouchListener(DragTouchListener(sizePx, w, h))
        wm!!.addView(icon, params)
    }

    private inner class DragTouchListener(
        private val size: Int,
        private val maxW: Int,
        private val maxH: Int
    ) : View.OnTouchListener {

        private var sx = 0
        private var sy = 0
        private var dx = 0f
        private var dy = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = params!!.x
                    sy = params!!.y
                    dx = e.rawX
                    dy = e.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val nx = sx + (e.rawX - dx).roundToInt()
                    val ny = sy + (e.rawY - dy).roundToInt()
                    if (abs(nx - params!!.x) < 1 && abs(ny - params!!.y) < 1) return true
                    params!!.x = nx
                    params!!.y = ny.coerceIn(0, maxH - size)
                    wm!!.updateViewLayout(icon, params)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    snap()
                    requestWithRetry()
                    return true
                }
            }
            return false
        }

        private fun snap() {
            params!!.x =
                if (params!!.x + size / 2 < maxW / 2) 0 else maxW - size
            wm!!.updateViewLayout(icon, params)
        }
    }

    private fun requestWithRetry() {
        retries = 0
        Runtime.clearCache()
        tryResolve()
    }

    private fun tryResolve() {
        val backend = Runtime.resolvePrivilege(this)
        if (backend != PrivilegeBackend.NONE) {
            PolicyEngine.discovery(this, backend)
            cleanup()
            return
        }

        if (retries++ < SHELL_RETRY_MAX) {
            handler.postDelayed({ tryResolve() }, SHELL_RETRY_DELAY_MS)
        }
    }

    private fun cleanup() {
        try {
            icon?.let { wm?.removeViewImmediate(it) }
        } catch (_: Throwable) {}
        icon = null
        params = null
        wm = null
        stopSelf()
    }

    override fun onBind(intent: Intent?) = null
}
