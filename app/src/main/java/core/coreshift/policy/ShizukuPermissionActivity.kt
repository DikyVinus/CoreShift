package core.coreshift.policy

import android.app.Activity
import android.os.Bundle
import dev.rikka.shizuku.Shizuku

class ShizukuPermissionActivity : Activity() {

    private val REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shizuku.pingBinder()) {
            finish()
            return
        }

        if (Shizuku.checkSelfPermission()
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }

        Shizuku.requestPermission(REQUEST_CODE)
        finish()
    }
}
