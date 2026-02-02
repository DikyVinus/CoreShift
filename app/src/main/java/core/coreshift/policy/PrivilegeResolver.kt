package core.coreshift.policy

import android.content.Context
import java.io.File

object PrivilegeResolver {

    @Volatile
    private var resolved: PrivilegeBackend? = null

    fun resolve(context: Context): PrivilegeBackend {
        resolved?.let {
            if (it != PrivilegeBackend.NONE) return it
        }

        val binDir = File(context.filesDir, "bin").absolutePath

        if (checkRoot(context, binDir)) {
            resolved = PrivilegeBackend.ROOT
            return resolved!!
        }

        if (checkShell(context, binDir)) {
            resolved = PrivilegeBackend.SHELL
            return resolved!!
        }

        return PrivilegeBackend.NONE
    }

    private fun checkRoot(context: Context, binDir: String): Boolean =
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
