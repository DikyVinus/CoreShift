package core.coreshift.policy

import android.content.Context
import android.os.Build
import java.io.File

enum class PrivilegeBackend {
    ROOT,
    SHELL,
    NONE
}

object PrivilegeResolver {

    @Volatile
    private var resolved: PrivilegeBackend? = null

    fun resolve(context: Context): PrivilegeBackend {
        resolved?.let { return it }

        synchronized(this) {
            resolved?.let { return it }

            if (checkRoot()) {
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

    private fun checkRoot(): Boolean =
        try {
            ProcessBuilder("su", "-c", "id")
                .start()
                .waitFor() == 0
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

object AxerishEnv {

    fun apply(context: Context, pb: ProcessBuilder) {
        val binDir = File(context.filesDir, "bin").absolutePath
        val env = pb.environment()

        env["ANDROID_ROOT"] = "/system"
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_RUNTIME_ROOT"] = "/apex/com.android.runtime"

        env.remove("CLASSPATH")
        env.remove("BOOTCLASSPATH")
        env.remove("SYSTEMSERVERCLASSPATH")
        env.remove("DEX_PATH")

        val is64 = Build.SUPPORTED_ABIS.any { it.contains("64") }
        val runtimeLib =
            if (is64)
                "/apex/com.android.runtime/lib64:/apex/com.android.art/lib64"
            else
                "/apex/com.android.runtime/lib:/apex/com.android.art/lib"

        env["LD_LIBRARY_PATH"] = "$runtimeLib:$binDir"
        env["PATH"] = "$binDir:${System.getenv("PATH")}"
    }
}

object RootEnv {

    fun apply(context: Context, pb: ProcessBuilder) {
        val binDir = File(context.filesDir, "bin").absolutePath
        pb.environment()["PATH"] = "$binDir:/system/bin:/system/xbin"
    }
}
