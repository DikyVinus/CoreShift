package core.coreshift.policy

import android.content.Context
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
private const val DEMOTE_THRESHOLD = 3
private const val BURST_DEMOTE_THRESHOLD = 5

private val FOREGROUND_WHITELIST = setOf(
    "com.android.launcher3",
    "com.android.settings",
    "com.android.vending",
    "com.android.chrome"
)

/* ===== Privileged eligibility (legacy-correct, optimized) ===== */

private object Eligibility {

    private val init = AtomicBoolean(false)

    @Volatile
    private var pkgs: Set<String> = emptySet()

    fun isEligible(
        context: Context,
        backend: PrivilegeBackend,
        pkg: String
    ): Boolean {
        load(context, backend)
        return pkgs.contains(pkg)
    }

    private fun load(context: Context, backend: PrivilegeBackend) {
        if (init.get()) return
        synchronized(this) {
            if (init.get()) return

            val prefs =
                context.getSharedPreferences(PREF_STATE, Context.MODE_PRIVATE)
            val cached = prefs.getStringSet("user_pkgs", null)
            if (cached != null) {
                pkgs = cached.toSet()
                init.set(true)
                return
            }

            if (backend == PrivilegeBackend.NONE) {
                pkgs = emptySet()
                init.set(true)
                return
            }

            val collected = HashSet<String>()
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
                        collected += it.substringAfter("package:")
                }

                pkgs = collected.toSet()
                prefs.edit()
                    .putStringSet("user_pkgs", HashSet(pkgs))
                    .apply()
            } finally {
                init.set(true)
            }
        }
    }
}

/* ============================================================ */

private object Prefs {
    lateinit var state: android.content.SharedPreferences
    lateinit var rate: android.content.SharedPreferences

    fun init(context: Context) {
        if (!this::state.isInitialized) {
            state = context.getSharedPreferences(PREF_STATE, Context.MODE_PRIVATE)
            rate  = context.getSharedPreferences(PREF_RATE, Context.MODE_PRIVATE)
        }
    }
}

private fun mark(context: Context, key: String) {
    Prefs.init(context)
    Prefs.state.edit()
        .putLong(key, System.currentTimeMillis())
        .apply()
}

object PolicyEngine {

    private val lastPkg = AtomicReference<String?>(null)
    private val lastExecAt = AtomicLong(0)

    @Volatile
    private var executor: ScheduledExecutorService? = null

    private val shutdownArmed = AtomicBoolean(false)

    // NEW: burst-scoped demote latch
    private val demotedThisBurst = AtomicBoolean(false)

    private fun ensureExecutor(): ScheduledExecutorService {
        executor?.let { return it }
        synchronized(this) {
            executor?.let { return it }
            executor = Executors.newSingleThreadScheduledExecutor()
            shutdownArmed.set(false)
            demotedThisBurst.set(false)
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
                demotedThisBurst.set(false)
                Prefs.rate.edit().putInt("c", 0).apply()
            }
        }, EXECUTOR_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun onForeground(context: Context, pkg: String) {
        Prefs.init(context)

        val exec = ensureExecutor()
        exec.execute {
            try {
                if (pkg == lastPkg.getAndSet(pkg)) return@execute

                val backend = Runtime.resolvePrivilege(context)
                if (backend == PrivilegeBackend.NONE) return@execute

                // fast path: whitelist first
                if (!FOREGROUND_WHITELIST.contains(pkg)) {
                    if (!Eligibility.isEligible(context, backend, pkg))
                        return@execute
                }

                val now = System.currentTimeMillis()
                if (now - lastExecAt.get() < 1000) return@execute
                lastExecAt.set(now)

                mark(context, "exec_at")
                recordRate()

                Runtime.exec(
                    context,
                    backend,
                    "coreshift_policy_cli",
                    args = listOf("boost", pkg)
                )

                if (shouldDemoteBurst() || shouldDemoteRare()) {
                    Runtime.exec(
                        context,
                        backend,
                        "coreshift_policy_cli",
                        args = listOf("demote")
                    )
                    mark(context, "demote_at")
                    markDemote()
                }
            } finally {
                armAutoShutdown()
            }
        }
    }

    fun discovery(context: Context, backend: PrivilegeBackend) {
        Prefs.init(context)
        if (Prefs.state.contains("discovery_at")) return

        Runtime.exec(
            context,
            backend,
            "coreshift_policy_cli",
            args = listOf("discovery"),
            wait = true
        )

        mark(context, "discovery_at")
    }

    private fun recordRate() {
        val now = System.currentTimeMillis()
        val w = Prefs.rate.getLong("w", 0)
        val c = Prefs.rate.getInt("c", 0)

        if (now - w > RATE_WINDOW_MS)
            Prefs.rate.edit().putLong("w", now).putInt("c", 1).apply()
        else
            Prefs.rate.edit().putInt("c", c + 1).apply()
    }

    // Condition A: burst / app-lifetime demote
    private fun shouldDemoteBurst(): Boolean {
        if (demotedThisBurst.get()) return false
        val count = Prefs.rate.getInt("c", 0)
        if (count >= BURST_DEMOTE_THRESHOLD) {
            demotedThisBurst.set(true)
            return true
        }
        return false
    }

    // Condition B: rare global safety demote (legacy)
    private fun shouldDemoteRare(): Boolean {
        val count = Prefs.rate.getInt("c", 0)
        val last = Prefs.rate.getLong("d", 0)
        return count >= DEMOTE_THRESHOLD &&
            System.currentTimeMillis() - last >= RATE_COOLDOWN_MS
    }

    private fun markDemote() {
        Prefs.rate.edit()
            .putLong("d", System.currentTimeMillis())
            .putInt("c", 0)
            .apply()
    }
}
