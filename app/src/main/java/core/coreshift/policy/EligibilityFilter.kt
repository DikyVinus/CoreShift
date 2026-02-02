package core.coreshift.policy

import android.content.Context

object EligibilityFilter {

    // Explicit type required (CI-safe)
    private val whitelist: Set<String> = emptySet()

    fun isEligible(context: Context, pkg: String): Boolean {
        if (whitelist.contains(pkg)) return true
        return UserPackageCache.isUserPackage(context, pkg)
    }
}
