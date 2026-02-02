package core.coreshift.policy

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class CoreShiftAccessibility : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        ExecutionController.onForegroundChanged(this, pkg)
    }

    override fun onInterrupt() {}
}
