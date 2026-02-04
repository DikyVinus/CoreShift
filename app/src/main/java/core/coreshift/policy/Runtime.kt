package core.coreshift.policy

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

enum class PrivilegeBackend { ROOT, SHELL, NONE }

object Runtime {

    @Volatile
    private var cached: PrivilegeBackend? = null

    private val bg = Executors.newSingleThreadExecutor()

    fun clearCache() {
        cached = null
    }

    private fun markPrivilege(context: Context) {
        val p = context.getSharedPreferences("coreshift_state", Context.MODE_PRIVATE)
        if (!p.contains("privilege_at")) {
            p.edit().putLong("privilege_at", System.currentTimeMillis()).apply()
        }
    }

    fun install(context: Context) {
        val abi = selectAbi()
        val binDir = File(context.filesDir, "bin")

        /* === verify bin dir is actually writable === */
        fun isReallyWritable(dir: File): Boolean {
            return try {
                val probe = File(dir, ".probe")
                FileOutputStream(probe).use { it.write(0) }
                probe.delete()
            } catch (_: Throwable) {
                false
            }
        }

        if (binDir.exists() && !isReallyWritable(binDir)) {
            binDir.deleteRecursively()
        }

        if (!binDir.exists()) {
            binDir.mkdirs()
        }

        binDir.setReadable(true, false)
        binDir.setWritable(true, false)
        binDir.setExecutable(true, false)

        val assetPath = "native/$abi"
        val am = context.assets
        val files = am.list(assetPath) ?: return

        for (name in files) {
            val out = File(binDir, name)

            if (out.exists()) {
                out.setWritable(true, true)
                if (!out.delete()) {
                    throw IllegalStateException("Failed to delete poisoned file: $out")
                }
            }

            am.open("$assetPath/$name").use { input ->
                FileOutputStream(out, false).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }

            if (name.endsWith(".dex")) {
                out.setReadable(true, true)     // 0400
                out.setWritable(false, false)
                out.setExecutable(false, false)
            } else {
                out.setReadable(true, false)    // 0755
                out.setWritable(false, false)
                out.setExecutable(true, false)
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
        } catch (_: Throwable) {
            false
        }

    private fun tryShell(context: Context): Boolean =
        try {
            val bin = context.filesDir.resolve("bin").absolutePath
            val pb = ProcessBuilder("$bin/axrun", "-c", "id")
            applyAxrunEnv(context, pb)
            pb.start().waitFor() == 0
        } catch (_: Throwable) {
            false
        }

    fun exec(
        context: Context,
        backend: PrivilegeBackend,
        binary: String,
        args: List<String> = emptyList(),
        wait: Boolean = false
    ) {
        val bin = context.filesDir.resolve("bin").absolutePath
        val command = buildShellCommand("$bin/$binary", args)

        val pb = when (backend) {
            PrivilegeBackend.ROOT ->
                ProcessBuilder("su", "-c", command)
                    .apply { environment()["PATH"] = "$bin:/system/bin:/system/xbin" }

            PrivilegeBackend.SHELL ->
                ProcessBuilder("$bin/axrun", "-c", command)
                    .also { applyAxrunEnv(context, it) }

            else -> return
        }

        if (wait) {
            try { pb.start().waitFor() } catch (_: Throwable) {}
        } else {
            bg.execute {
                try { pb.start() } catch (_: Throwable) {}
            }
        }
    }

    internal fun applyAxrunEnv(context: Context, pb: ProcessBuilder) {
        val bin = context.filesDir.resolve("bin").absolutePath
        val env = pb.environment()

        env["APPLICATION_ID"] = context.packageName
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

    private fun buildShellCommand(bin: String, args: List<String>): String =
        buildString {
            append(bin)
            for (a in args) {
                append(' ')
                append(shellEscape(a))
            }
        }

    private fun shellEscape(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    private fun selectAbi(): String =
        when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64-v8a"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armeabi-v7a"
            else -> error("Unsupported ABI")
        }
}
