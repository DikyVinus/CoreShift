package core.coreshift.policy

import android.content.Context
import java.io.File

object PrivilegeResolver {

    @Volatile
    private var resolved: PrivilegeBackend? = null

    fun resolve(context: Context): PrivilegeBackend {
        resolved?.let { return it }

        synchronized(this) {
            resolved?.let { return it }

            if (checkRoot(context)) {
                resolved = PrivilegeBackend.ROOT
                PolicyLogger.log(context, "Privilege resolved: ROOT")
                return resolved!!
            }

            if (checkShell(context)) {
                resolved = PrivilegeBackend.SHELL
                PolicyLogger.log(context, "Privilege resolved: SHELL(axerish)")
                return resolved!!
            }

            resolved = PrivilegeBackend.NONE
            return resolved!!
        }
    }

    private fun checkRoot(context: Context): Boolean =
        try {
            val pb = ProcessBuilder("su", "-c", "id")
            AxerishEnv.apply(context, pb)
            pb.start().waitFor() == 0
        } catch (_: Throwable) {
            false
        }

    private fun checkShell(context: Context): Boolean =
        try {
            val binDir = File(context.filesDir, "bin").absolutePath
            val pb = ProcessBuilder(
                "$binDir/axerish",
                "-c",
                "\"whoami\""
            )
            AxerishEnv.apply(context, pb)
            pb.start().waitFor() == 0
        } catch (_: Throwable) {
            false
        }
}
