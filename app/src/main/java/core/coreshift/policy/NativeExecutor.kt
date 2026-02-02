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
                    ProcessBuilder("su", "-c", cmd).apply {
                        environment()["PATH"] = "$binDir:${System.getenv("PATH")}"
                    }

                PrivilegeBackend.SHELL ->
                    ProcessBuilder(
                        "$binDir/axerish",
                        "-c",
                        "\"$cmd\""
                    ).apply {
                        AxerishEnv.apply(context, this)
                    }

                else -> return
            }

            pb.start().waitFor()
        } catch (_: Throwable) {}
    }
}
