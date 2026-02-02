package core.coreshift.policy

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

object ExecutionController {

    private val executor = Executors.newSingleThreadExecutor()

    fun onForegroundChanged(context: Context, pkg: String) {
        executor.execute {
            if (!EligibilityFilter.isEligible(context, pkg)) return@execute

            val backend = PrivilegeResolver.resolve(context)
            if (backend == PrivilegeBackend.NONE) return@execute

            NativeExecutor.exec(context, backend, "coreshift_exec")
            RateLimiter.recordExec(context)

            if (RateLimiter.shouldDemote(context)) {
                NativeExecutor.exec(context, backend, "coreshift_demote")
                RateLimiter.markDemoted(context)
            }
        }
    }
}
