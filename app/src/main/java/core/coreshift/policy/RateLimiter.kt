package core.coreshift.policy

import android.content.Context

object RateLimiter {

    private const val PREF = "coreshift_rate"

    // demotion
    private const val KEY_LAST_DEMOTE = "last_demote"
    private const val KEY_COUNT = "count"
    private const val KEY_WINDOW = "window"

    // 50-exec tracking
    private const val KEY_TOTAL = "total_exec"
    private const val KEY_LAST_50_TS = "last_50_ts"

    private const val WINDOW_MS = 5 * 60 * 1000L
    private const val THRESHOLD = 10
    private const val DEMOTE_COOLDOWN = 60 * 60 * 1000L

    fun recordExec(context: Context) {
        val now = System.currentTimeMillis()
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        // rolling window (demotion)
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

        // total execution counter
        val total = p.getInt(KEY_TOTAL, 0) + 1
        val editor = p.edit().putInt(KEY_TOTAL, total)

        if (total % 50 == 0) {
            val last = p.getLong(KEY_LAST_50_TS, 0L)
            val delta = if (last == 0L) 0L else now - last
            editor.putLong(KEY_LAST_50_TS, now).apply()

            PolicyLogger.log(
                context,
                "EXEC x50 reached: total=$total delta_ms=$delta"
            )
        } else {
            editor.apply()
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
