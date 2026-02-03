package core.coreshift.policy

import android.content.Context
import android.os.Build
import java.io.FileOutputStream

enum class PrivilegeBackend { ROOT, SHELL, NONE }

object Runtime {

    @Volatile
    private var cached: PrivilegeBackend? = null

    private fun markPrivilege(context: Context) {
        val p = context.getSharedPreferences("coreshift_state", Context.MODE_PRIVATE)
        if (!p.contains("privilege_at")) {
            p.edit().putLong("privilege_at", System.currentTimeMillis()).apply()
        }
    }

    fun install(context: Context) {
        val abi = selectAbi()
        val binDir = context.filesDir.resolve("bin")
        if (!binDir.exists()) binDir.mkdirs()

        binDir.setReadable(true, true)
        binDir.setWritable(true, true)
        binDir.setExecutable(true, true)

        val assetPath = "native/$abi"
        val am = context.assets
        val files = am.list(assetPath) ?: return

        for (name in files) {
            val out = binDir.resolve(name)
            val needs =
                !out.exists() ||
                (name.endsWith(".dex") && out.canWrite()) ||
                (!name.endsWith(".dex") && !out.canExecute())

            if (!needs) continue

            am.open("$assetPath/$name").use { input ->
                FileOutputStream(out, false).use { output ->
                    input.copyTo(output)
                }
            }

            if (name.endsWith(".dex")) {
                out.setReadable(true, true)
                out.setWritable(false, true)
                out.setExecutable(false, true)
            } else {
                out.setReadable(true, true)
                out.setWritable(false, true)
                out.setExecutable(true, true)
            }
        }
    }

    fun resolvePrivilege(context: Context): PrivilegeBackend {
        cached?.let { return it }

        synchronized(this) {
            cached?.let { return it }

            if (tryRoot(context)) {
                cached = PrivilegeBackend.ROOT
                markPrivilege(context)
                return cached!!
            }

            if (tryShell(context)) {
                cached = PrivilegeBackend.SHELL
                markPrivilege(context)
                return cached!!
            }

            cached = PrivilegeBackend.NONE
            return cached!!
        }
    }

    private fun tryRoot(context: Context): Boolean =
        try {
            val bin = context.filesDir.resolve("bin").absolutePath
            ProcessBuilder("su", "-c", "id")
                .apply { environment()["PATH"] = "$bin:/system/bin:/system/xbin" }
                .start()
                .waitFor() == 0
        } catch (_: Throwable) { false }

    private fun tryShell(context: Context): Boolean =
        try {
            val bin = context.filesDir.resolve("bin").absolutePath
            val pb = ProcessBuilder("$bin/axerish", "-c", "\"whoami\"")
            applyAxerishEnv(context, pb)
            pb.start().waitFor() == 0
        } catch (_: Throwable) { false }

    fun exec(context: Context, backend: PrivilegeBackend, binary: String) {
        val bin = context.filesDir.resolve("bin").absolutePath
        val cmd = "$bin/$binary"

        try {
            val pb = when (backend) {
                PrivilegeBackend.ROOT ->
                    ProcessBuilder("su", "-c", cmd)
                        .apply { environment()["PATH"] = "$bin:/system/bin:/system/xbin" }
                PrivilegeBackend.SHELL ->
                    ProcessBuilder("$bin/axerish", "-c", "\"$cmd\"")
                        .also { applyAxerishEnv(context, it) }
                else -> return
            }
            pb.start().waitFor()
        } catch (_: Throwable) {}
    }

    fun applyAxerishEnv(context: Context, pb: ProcessBuilder) {
        val bin = context.filesDir.resolve("bin").absolutePath
        val env = pb.environment()

        env["ANDROID_ROOT"] = "/system"
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_RUNTIME_ROOT"] = "/apex/com.android.runtime"

        env.remove("CLASSPATH")
        env.remove("BOOTCLASSPATH")
        env.remove("SYSTEMSERVERCLASSPATH")
        env.remove("DEX_PATH")

        val runtimeLib =
            if (Build.SUPPORTED_ABIS.any { it.contains("64") })
                "/apex/com.android.runtime/lib64:/apex/com.android.art/lib64"
            else
                "/apex/com.android.runtime/lib:/apex/com.android.art/lib"

        env["LD_LIBRARY_PATH"] = "$runtimeLib:$bin"
        env["PATH"] = "$bin:${System.getenv("PATH")}"
    }

    private fun selectAbi(): String =
        when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64-v8a"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armeabi-v7a"
            else -> error("Unsupported ABI")
        }
}
