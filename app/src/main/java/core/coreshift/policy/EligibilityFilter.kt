package core.coreshift.policy

import android.content.Context

object EligibilityFilter {

    private val whitelist = setOf(
        // optional hardcoded exceptions
    )

    fun isEligible(context: Context, pkg: String): Boolean {
        if (whitelist.contains(pkg)) return true
        return UserPackageCache.isUserPackage(context, pkg)
    }
}
