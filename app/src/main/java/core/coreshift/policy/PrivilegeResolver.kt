package core.coreshift.policy

import android.content.Context
import java.io.File

object PrivilegeResolver {

    @Volatile
    private var resolved: PrivilegeBackend? = null

    fun resolve(context: Context): PrivilegeBackend {
        resolved?.let { return it }

        val binDir = File(context.filesDir, "bin").absolutePath

        if (checkRoot(context)) {
            resolved = PrivilegeBackend.ROOT
            PolicyLogger.log(context, "Privilege resolved: ROOT")
            return resolved!!
        }

        if (checkShell(context, binDir)) {
            resolved = PrivilegeBackend.SHELL
            PolicyLogger.log(context, "Privilege resolved: SHELL(axerish)")
            return resolved!!
        }

        return PrivilegeBackend.NONE
    }

    private fun checkRoot(context: Context): Boolean =
        try {
            val pb = ProcessBuilder("su", "-c", "id")
            AxerishEnv.apply(context, pb)
            pb.start().waitFor() == 0
        } catch (_: Throwable) {
            false
        }

    private fun checkShell(context: Context, binDir: String): Boolean =
        try {
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
