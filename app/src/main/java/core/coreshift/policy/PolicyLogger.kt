package core.coreshift.policy

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PolicyLogger {

    private const val LOG_FILE = "policy.log"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, msg: String) {
        try {
            val file = File(context.filesDir, LOG_FILE)
            val ts = fmt.format(Date())
            file.appendText("[$ts] $msg\n")
        } catch (_: Throwable) {
            // never throw
        }
    }
}
