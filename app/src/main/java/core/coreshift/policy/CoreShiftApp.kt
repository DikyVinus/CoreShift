package core.coreshift.policy

import android.app.*
import android.accessibilityservice.AccessibilityService
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import kotlin.math.roundToInt

class CoreShiftApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Runtime.install(this)

        val backend = Runtime.resolvePrivilege(this)
        if (backend == PrivilegeBackend.NONE) {
            startService(Intent(this, OverlayService::class.java))
        } else {
            Policy.discovery(this, backend)
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

    private lateinit var wm: WindowManager
    private lateinit var icon: ImageView
    private lateinit var params: WindowManager.LayoutParams

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
            isFocusable = false
        }

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        icon.setOnTouchListener(DragTouchListener())
        wm.addView(icon, params)
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (touchX - e.rawX).roundToInt()
                    params.y = startY + (e.rawY - touchY).roundToInt()
                    wm.updateViewLayout(icon, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    request()
                    return true
                }
            }
            return false
        }
    }

    private fun request() {
        Runtime.clearCache()
        val backend = Runtime.resolvePrivilege(this)
        if (backend != PrivilegeBackend.NONE) {
            Policy.discovery(this, backend)
            cleanup()
        }
    }

    private fun cleanup() {
        try { wm.removeView(icon) } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onBind(intent: Intent?) = null
}
