package core.coreshift.policy

import rikka.shizuku.ShizukuShell
import java.io.File

object Exec {

    fun run(bin: File) {
        ShizukuShell.newInstance()
            .newJob()
            .add(bin.absolutePath)
            .exec()
    }
}
