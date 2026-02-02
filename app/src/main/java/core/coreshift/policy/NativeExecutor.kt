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
                    ).also {
                        AxerishEnv.apply(context, it)
                    }

                else -> return
            }

            pb.start().waitFor()
        } catch (_: Throwable) {
            // swallow permanently
        }
    }
}
