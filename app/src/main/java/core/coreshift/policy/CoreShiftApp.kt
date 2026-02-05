package core.coreshift.policy

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.PaintDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

private const val FOREGROUND_STABLE_MS = 5_000L
private const val SHELL_RETRY_DELAY_MS = 300L
private const val SHELL_RETRY_MAX = 20
private const val PRIV_RETRY_MIN_INTERVAL_MS = 1_000L

class CoreShiftApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Runtime.install(this)

        val intent = Intent(this, OverlayService::class.java)

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
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

    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var lastResolveAttempt = 0L

    override fun onCreate() {
        super.onCreate()

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= 26) {
            val channelId = "coreshift_overlay"
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "CoreShift",
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
            }

            startForeground(
                1,
                Notification.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_coreshift)
                    .setContentTitle("CoreShift")
                    .setContentText("Awaiting privilege")
                    .build()
            )
        }

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
            val p = params ?: return false
            val wmgr = wm ?: return false
            val ic = icon ?: return false

            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x
                    sy = p.y
                    dx = e.rawX
                    dy = e.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - dx).roundToInt()
                    p.y = (sy + (e.rawY - dy).roundToInt())
                        .coerceIn(0, maxH - size)
                    wmgr.updateViewLayout(ic, p)
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
            val p = params ?: return
            val wmgr = wm ?: return
            val ic = icon ?: return

            p.x = if (p.x + size / 2 < maxW / 2) 0 else maxW - size
            wmgr.updateViewLayout(ic, p)
        }
    }

    private fun requestWithRetry() {
        retries = 0
        Runtime.clearCache()
        tryResolve()
    }

    private fun tryResolve() {
        val now = SystemClock.uptimeMillis()
        if (now - lastResolveAttempt < PRIV_RETRY_MIN_INTERVAL_MS) return
        lastResolveAttempt = now

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
