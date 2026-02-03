package core.coreshift.policy

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object Policy {

    private val exec = Executors.newSingleThreadExecutor()
    private val lastPkg = AtomicReference<String?>(null)

    fun onForeground(context: Context, pkg: String) {
        exec.execute {
            if (pkg == lastPkg.getAndSet(pkg)) return@execute
            if (!Eligibility.isUser(context, pkg)) return@execute

            val backend = Runtime.resolvePrivilege(context)
            if (backend == PrivilegeBackend.NONE) return@execute

            log(context, "EXEC $pkg")
            Runtime.exec(context, backend, "coreshift_exec")
            Rate.record(context)

            if (Rate.shouldDemote(context)) {
                Runtime.exec(context, backend, "coreshift_demote")
                Rate.mark(context)
            }
        }
    }

    fun discoveryOnce(context: Context, backend: PrivilegeBackend) {
        val p = context.getSharedPreferences("coreshift", Context.MODE_PRIVATE)
        if (p.getBoolean("done", false)) return
        Runtime.exec(context, backend, "coreshift_discovery")
        p.edit().putBoolean("done", true).apply()
    }

    private fun log(context: Context, msg: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            context.filesDir.resolve("policy.log")
                .appendText("[$ts] $msg\n")
        } catch (_: Throwable) {}
    }
}

object Rate {
    private const val PREF = "rate"
    private const val WINDOW = 5 * 60 * 1000L
    private const val COOLDOWN = 60 * 60 * 1000L

    fun record(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val w = p.getLong("w", 0)
        val c = p.getInt("c", 0)

        if (now - w > WINDOW)
            p.edit().putLong("w", now).putInt("c", 1).apply()
        else
            p.edit().putInt("c", c + 1).apply()
    }

    fun shouldDemote(context: Context): Boolean {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getInt("c", 0) >= 10 &&
            System.currentTimeMillis() - p.getLong("d", 0) >= COOLDOWN
    }

    fun mark(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putLong("d", System.currentTimeMillis())
            .apply()
    }
}

object Eligibility {
    private val init = AtomicBoolean(false)
    private val pkgs = HashSet<String>()

    fun isUser(context: Context, pkg: String): Boolean {
        load(context)
        return pkgs.contains(pkg)
    }

    private fun load(context: Context) {
        if (init.get()) return
        synchronized(this) {
            if (init.get()) return

            val backend = Runtime.resolvePrivilege(context)
            if (backend == PrivilegeBackend.NONE) {
                init.set(true); return
            }

            val bin = context.filesDir.resolve("bin").absolutePath
            val cmd = "cmd package list packages -3"

            try {
                val pb = when (backend) {
                    PrivilegeBackend.ROOT ->
                        ProcessBuilder("su", "-c", cmd)

                    PrivilegeBackend.SHELL ->
                        ProcessBuilder("$bin/axerish", "-c", "\"$cmd\"")
                            .also { Runtime.applyAxerishEnv(context, it) }

                    else -> return
                }

                BufferedReader(InputStreamReader(pb.start().inputStream))
                    .forEachLine {
                        if (it.startsWith("package:"))
                            pkgs += it.substringAfter("package:")
                    }
            } catch (_: Throwable) {
            } finally {
                init.set(true)
            }
        }
    }
}
