package core.coreshift.policy

import android.content.Context
import java.io.File

object PrivilegeResolver {

    private var resolved: PrivilegeBackend? = null

    fun resolve(context: Context): PrivilegeBackend {
        resolved?.let { return it }

        val binDir = File(context.filesDir, "bin").absolutePath

        if (checkRoot(binDir)) {
            resolved = PrivilegeBackend.ROOT
            return resolved!!
        }

        if (checkShell(context, binDir)) {
            resolved = PrivilegeBackend.SHELL
            return resolved!!
        }

        resolved = PrivilegeBackend.NONE
        return resolved!!
    }

    private fun checkRoot(binDir: String): Boolean =
        try {
            ProcessBuilder("su", "-c", "id")
                .apply {
                    environment()["PATH"] = "$binDir:${System.getenv("PATH")}"
                }
                .start()
                .waitFor() == 0
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
