package core.coreshift.policy

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream

object BinaryInstaller {

    fun installAll(context: Context) {
        val abi = selectAbi()
        val binDir = File(context.filesDir, "bin")

        if (!binDir.exists()) binDir.mkdirs()

        // Always re-enforce permissions
        binDir.setReadable(true, true)
        binDir.setWritable(true, true)
        binDir.setExecutable(true, true)

        installAssets(context, abi, binDir)
        installNativeLibs(context, binDir)
    }

    private fun selectAbi(): String {
        val supported = Build.SUPPORTED_ABIS
        return when {
            supported.contains("arm64-v8a") -> "arm64-v8a"
            supported.contains("armeabi-v7a") -> "armeabi-v7a"
            else -> error("Unsupported ABI: ${supported.joinToString()}")
        }
    }

    private fun installAssets(context: Context, abi: String, binDir: File) {
        val assetPath = "native/$abi"
        val am = context.assets
        val files = am.list(assetPath) ?: return

        for (name in files) {
            val out = File(binDir, name)

            val needsInstall =
                !out.exists() ||
                !out.canRead() ||
                (name.endsWith(".dex") && out.canWrite()) ||
                (!name.endsWith(".dex") && !out.canExecute())

            if (!needsInstall) continue

            am.open("$assetPath/$name").use { input ->
                FileOutputStream(out, false).use { output ->
                    input.copyTo(output)
                }
            }

            if (name.endsWith(".dex")) {
                out.setReadable(true, true)
                out.setExecutable(false, true)
                out.setWritable(false, true)
            } else {
                out.setReadable(true, true)
                out.setExecutable(true, true)
                out.setWritable(false, true)
            }
        }
    }

    private fun installNativeLibs(context: Context, binDir: File) {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        if (!libDir.exists()) return

        libDir.listFiles()?.forEach { lib ->
            val out = File(binDir, lib.name)
            if (out.exists()) return@forEach

            lib.inputStream().use { input ->
                FileOutputStream(out).use { output ->
                    input.copyTo(output)
                }
            }

            out.setReadable(true, true)
            out.setExecutable(true, true)
            out.setWritable(false, true)
        }
    }
}

object NativeExecutor {

    fun exec(context: Context, backend: PrivilegeBackend, binary: String) {
        val binDir = File(context.filesDir, "bin").absolutePath
        val cmd = "$binDir/$binary"

        try {
            val pb = when (backend) {
                PrivilegeBackend.ROOT ->
                    ProcessBuilder("su", "-c", cmd).also {
                        RootEnv.apply(context, it)
                    }

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
        } catch (_: Throwable) {}
    }
}
