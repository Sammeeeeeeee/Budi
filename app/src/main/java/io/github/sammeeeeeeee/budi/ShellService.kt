package io.github.sammeeeeeeee.budi

import android.os.ParcelFileDescriptor
import kotlin.system.exitProcess

/** Shizuku user service: runs in a separate process as the shell user. */
class ShellService : IShell.Stub() {
    override fun run(script: String, output: ParcelFileDescriptor) {
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        ParcelFileDescriptor.AutoCloseOutputStream(output).use { process.inputStream.copyTo(it) }
        process.waitFor()
    }

    override fun destroy() = exitProcess(0)
}
