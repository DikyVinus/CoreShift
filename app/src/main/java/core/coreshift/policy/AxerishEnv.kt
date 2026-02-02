package core.coreshift.policy

import android.content.Context
import android.os.Build
import java.io.File

object AxerishEnv {

    fun apply(context: Context, pb: ProcessBuilder) {
        val binDir = File(context.filesDir, "bin").absolutePath
        val env = pb.environment()

        // Required for app_process
        env["ANDROID_ROOT"] = "/system"
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_RUNTIME_ROOT"] = "/apex/com.android.runtime"

        // CRITICAL: never inherit app CLASSPATH
        env.remove("CLASSPATH")

        val is64 = Build.SUPPORTED_ABIS.any { it.contains("64") }

        val runtimeLib = if (is64)
            "/apex/com.android.runtime/lib64:/apex/com.android.art/lib64"
        else
            "/apex/com.android.runtime/lib:/apex/com.android.art/lib"

        env["LD_LIBRARY_PATH"] = "$runtimeLib:$binDir"
        env["PATH"] = "$binDir:${System.getenv("PATH")}"
    }
}
