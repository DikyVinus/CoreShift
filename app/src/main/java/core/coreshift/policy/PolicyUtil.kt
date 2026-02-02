package core.coreshift.policy

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object PolicyLogger {

    private const val LOG_FILE = "policy.log"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, msg: String) {
        try {
            val file = File(context.filesDir, LOG_FILE)
            val ts = fmt.format(Date())
            file.appendText("[$ts] $msg\n")
        } catch (_: Throwable) {}
    }
}

object RateLimiter {

    private const val PREF = "coreshift_rate"
    private const val KEY_LAST_DEMOTE = "last_demote"
    private const val KEY_COUNT = "count"
    private const val KEY_WINDOW = "window"

    private const val WINDOW_MS = 5 * 60 * 1000L
    private const val THRESHOLD = 10
    private const val DEMOTE_COOLDOWN = 60 * 60 * 1000L

    fun recordExec(context: Context) {
        val now = System.currentTimeMillis()
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        val window = p.getLong(KEY_WINDOW, 0L)
        val count = p.getInt(KEY_COUNT, 0)

        if (now - window > WINDOW_MS) {
            p.edit()
                .putLong(KEY_WINDOW, now)
                .putInt(KEY_COUNT, 1)
                .apply()
        } else {
            p.edit()
                .putInt(KEY_COUNT, count + 1)
                .apply()
        }
    }

    fun shouldDemote(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        val count = p.getInt(KEY_COUNT, 0)
        val last = p.getLong(KEY_LAST_DEMOTE, 0L)

        return count >= THRESHOLD && now - last >= DEMOTE_COOLDOWN
    }

    fun markDemoted(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_DEMOTE, System.currentTimeMillis())
            .apply()

        PolicyLogger.log(context, "DEMOTE executed")
    }
}

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
            } finally {
                initialized.set(true)
            }
        }
    }
}

object EligibilityFilter {

    private val whitelist: Set<String> = emptySet()

    fun isEligible(context: Context, pkg: String): Boolean =
        whitelist.contains(pkg) || UserPackageCache.isUserPackage(context, pkg)
}
