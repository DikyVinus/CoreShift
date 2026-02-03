package core.coreshift.policy

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val PREF_STATE = "coreshift_state"

private fun mark(context: Context, key: String) {
    context.getSharedPreferences(PREF_STATE, Context.MODE_PRIVATE)
        .edit()
        .putLong(key, System.currentTimeMillis())
        .apply()
}

object Policy {

    private val exec = Executors.newSingleThreadExecutor()
    private val lastPkg = AtomicReference<String?>(null)

    fun onForeground(context: Context, pkg: String) {
        exec.execute {
            if (pkg == lastPkg.getAndSet(pkg)) return@execute
            if (!Eligibility.isUser(context, pkg)) return@execute

            val backend = Runtime.resolvePrivilege(context)
            if (backend == PrivilegeBackend.NONE) return@execute

            // record execution timestamp (overwrite allowed)
            mark(context, "exec_at")

            // increment rate counter (FIX: required for demotion to ever trigger)
            Rate.record(context)

            Runtime.exec(context, backend, "coreshift_exec")

            if (Rate.shouldDemote(context)) {
                Runtime.exec(context, backend, "coreshift_demote")
                mark(context, "demote_at")
                Rate.mark(context)
            }
        }
    }

    fun discoveryOnce(context: Context, backend: PrivilegeBackend) {
        val p = context.getSharedPreferences(PREF_STATE, Context.MODE_PRIVATE)
        if (p.contains("discovery_at")) return

        Runtime.exec(context, backend, "coreshift_discovery")
        mark(context, "discovery_at")
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
                init.set(true)
                return
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

                pb.start().inputStream.bufferedReader().forEachLine {
                    if (it.startsWith("package:"))
                        pkgs += it.substringAfter("package:")
                }
            } finally {
                init.set(true)
            }
        }
    }
}
