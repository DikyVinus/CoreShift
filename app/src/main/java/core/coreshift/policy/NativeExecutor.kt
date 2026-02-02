package core.coreshift.policy

import android.content.Context
import java.io.File

object NativeExecutor {

    fun exec(context: Context, backend: PrivilegeBackend, binary: String) {
        val binDir = File(context.filesDir, "bin").absolutePath
        val cmd = "$binDir/$binary"

        try {
            val pb = when (backend) {
                PrivilegeBackend.ROOT ->
                    ProcessBuilder("su", "-c", cmd)

                PrivilegeBackend.SHELL ->
                    ProcessBuilder(
                        "$binDir/axerish",
                        "-c",
                        "\"$cmd\""
                    )

                else -> return
            }

            if (backend == PrivilegeBackend.SHELL) {
                AxerishEnv.apply(context, pb)
            } else {
                pb.environment()["PATH"] = "$binDir:${System.getenv("PATH")}"
            }

            pb.start().waitFor()
        } catch (_: Throwable) {}
    }
}
