package core.coreshift.policy

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

object UserPackageCache {

    private val initialized = AtomicBoolean(false)
    private val userPkgs = HashSet<String>()

    fun isUserPackage(context: Context, pkg: String): Boolean {
        ensureLoaded(context)
        return userPkgs.contains(pkg)
    }

    private fun ensureLoaded(context: Context) {
        if (initialized.get()) return

        synchronized(this) {
            if (initialized.get()) return

            val backend = PrivilegeResolver.resolve(context)
            if (backend == PrivilegeBackend.NONE) {
                initialized.set(true)
                return
            }

            val binDir = context.filesDir.resolve("bin").absolutePath
            val cmd = "cmd package list packages -3"

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

                val proc = pb.start()
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    r.forEachLine { line ->
                        if (line.startsWith("package:")) {
                            userPkgs.add(line.substringAfter("package:"))
                        }
                    }
                }
                proc.waitFor()
            } catch (_: Throwable) {
                // swallow permanently
            } finally {
                initialized.set(true)
            }
        }
    }
}
