package core.coreshift.policy

import android.content.Context
import java.io.File

object DiscoveryController {

    private const val PREF = "coreshift_policy"
    private const val KEY_DONE = "discovery_done"

    fun runOnce(context: Context, backend: PrivilegeBackend) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        val binDir = File(context.filesDir, "bin").absolutePath
        val path = "$binDir:${System.getenv("PATH")}"
        val cmd = "$binDir/coreshift_discovery"

        try {
            when (backend) {
                PrivilegeBackend.ROOT ->
                    ProcessBuilder("su", "-c", cmd)

                PrivilegeBackend.SHELL ->
                    ProcessBuilder(
                        "$binDir/axerish",
                        "-c",
                        "\"$cmd\""
                    )

                else -> return
            }.apply {
                environment()["PATH"] = path
            }.start().waitFor()

            prefs.edit().putBoolean(KEY_DONE, true).apply()
        } catch (_: Throwable) {}
    }
}
