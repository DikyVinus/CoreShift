package core.coreshift.policy

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

object DiscoveryController {

    private const val PREF = "coreshift_policy"
    private const val KEY_DONE = "discovery_done"

    fun runOnce(context: Context, backend: PrivilegeBackend) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        NativeExecutor.exec(context, backend, "coreshift_discovery")
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }
}

object ExecutionController {

    private val executor = Executors.newSingleThreadExecutor()
    private val lastPkg = AtomicReference<String?>(null)

    fun onForegroundChanged(context: Context, pkg: String) {
        executor.execute {
            val prev = lastPkg.getAndSet(pkg)
            if (pkg == prev) return@execute

            if (!EligibilityFilter.isEligible(context, pkg)) return@execute

            val backend = PrivilegeResolver.resolve(context)
            if (backend == PrivilegeBackend.NONE) return@execute

            PolicyLogger.log(context, "EXEC foreground=$pkg backend=$backend")

            NativeExecutor.exec(context, backend, "coreshift_exec")
            RateLimiter.recordExec(context)

            if (RateLimiter.shouldDemote(context)) {
                NativeExecutor.exec(context, backend, "coreshift_demote")
                RateLimiter.markDemoted(context)
            }
        }
    }
}
