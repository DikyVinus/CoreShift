package core.coreshift.policy

import android.content.Context
import java.io.File

object AxerishEnv {

    fun apply(context: Context, pb: ProcessBuilder) {
        val binDir = File(context.filesDir, "bin").absolutePath
        val env = pb.environment()

        // Android runtime roots (MANDATORY for app_process)
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_ROOT"] = "/system"

        // Remove inherited app CLASSPATH (Android 14+ requirement)
        env.remove("CLASSPATH")

        // Ensure our binaries win
        env["PATH"] = "$binDir:${System.getenv("PATH")}"

        // Native libs if axerish ships any (safe even if unused)
        env["LD_LIBRARY_PATH"] = binDir
    }
}
