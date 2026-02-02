package core.coreshift.policy

import android.app.Application
import android.content.Intent

class CoreShiftApp : Application() {

    override fun onCreate() {
        super.onCreate()

        BinaryInstaller.installAll(this)

        val backend = PrivilegeResolver.resolve(this)

        if (backend == PrivilegeBackend.NONE) {
            startService(Intent(this, FloatingPrivilegeService::class.java))
        } else {
            DiscoveryController.runOnce(this, backend)
        }
    }
}
