package core.coreshift.policy

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val PREF_STATE = "coreshift_state"
private const val PREF_RATE  = "rate"

private const val EXECUTOR_IDLE_TIMEOUT_MS = 2 * 60 * 1000L
private const val RATE_WINDOW_MS = 5 * 60 * 1000L
private const val RATE_COOLDOWN_MS = 60 * 60 * 1000L
private const val DEMOTE_THRESHOLD = 10

private val FOREGROUND_WHITELIST = setOf(
    "com.android.launcher3",
    "com.android.settings",
    "com.android.vending",
    "com.android.chrome"
)

private fun mark(context: Context, key: String) {
    context.getSharedPreferences(PREF_STATE, Context.MODE_PRIVATE)
        .edit()
        .putLong(key, System.currentTimeMillis())
        .apply()
}

object PolicyEngine {

    private val lastPkg = AtomicReference<String?>(null)
    private val lastExecAt = AtomicLong(0)

    @Volatile
    private var executor: ScheduledExecutorService? = null

    private val shutdownArmed = AtomicBoolean(false)

    private fun ensureExecutor(): ScheduledExecutorService {
        executor?.let { return it }
        synchronized(this) {
            executor?.let { return it }
            executor = Executors.newSingleThreadScheduledExecutor()
            shutdownArmed.set(false)
            return executor!!
        }
    }

    private fun armAutoShutdown() {
        if (!shutdownArmed.compareAndSet(false, true)) return
        executor?.schedule({
            synchronized(this) {
                executor?.shutdown()
                executor = null
                shutdownArmed.set(false)
            }
        }, EXECUTOR_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun onForeground(context: Context, pkg: String) {
        val exec = ensureExecutor()
        exec.execute {
            try {
                if (pkg == lastPkg.getAndSet(pkg)) return@execute

                if (!Eligibility.isUser(context, pkg) &&
                    !FOREGROUND_WHITELIST.contains(pkg)
                ) return@execute

                val backend = Runtime.resolvePrivilege(context)
                if (backend == PrivilegeBackend.NONE) return@execute

                val now = System.currentTimeMillis()
                if (now - lastExecAt.get() < 1000) return@execute
                lastExecAt.set(now)

                mark(context, "exec_at")
                recordRate(context)

                Runtime.exec(context, backend, "coreshift_exec")

                if (shouldDemote(context)) {
                    Runtime.exec(context, backend, "coreshift_demote")
                    mark(context, "demote_at")
                    markDemote(context)
                }
            } finally {
                armAutoShutdown()
            }
        }
    }

    fun discovery(context: Context, backend: PrivilegeBackend) {
        val p = context.getSharedPreferences(PREF_STATE, Context.MODE_PRIVATE)
        if (p.contains("discovery_at")) return

        Runtime.exec(context, backend, "coreshift_discovery", wait = true)
        mark(context, "discovery_at")
    }

    private fun recordRate(context: Context) {
        val p = context.getSharedPreferences(PREF_RATE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val w = p.getLong("w", 0)
        val c = p.getInt("c", 0)

        if (now - w > RATE_WINDOW_MS)
            p.edit().putLong("w", now).putInt("c", 1).apply()
        else
            p.edit().putInt("c", c + 1).apply()
    }

    private fun shouldDemote(context: Context): Boolean {
        val p = context.getSharedPreferences(PREF_RATE, Context.MODE_PRIVATE)
        val count = p.getInt("c", 0)
        val last = p.getLong("d", 0)

        return count >= DEMOTE_THRESHOLD &&
            System.currentTimeMillis() - last >= RATE_COOLDOWN_MS
    }

    private fun markDemote(context: Context) {
        context.getSharedPreferences(PREF_RATE, Context.MODE_PRIVATE)
            .edit()
            .putLong("d", System.currentTimeMillis())
            .putInt("c", 0)
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

            val prefs = context.getSharedPreferences(PREF_STATE, Context.MODE_PRIVATE)
            val cached = prefs.getStringSet("user_pkgs", null)
            if (cached != null) {
                pkgs.addAll(cached)
                init.set(true)
                return
            }

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

                prefs.edit().putStringSet("user_pkgs", HashSet(pkgs)).apply()
            } finally {
                init.set(true)
            }
        }
    }
}
