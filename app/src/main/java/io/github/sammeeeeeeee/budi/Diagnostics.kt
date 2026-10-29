package io.github.sammeeeeeeee.budi

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.concurrent.thread

/**
 * Collects what One UI exposes about the buds (Bluetooth metadata, settings, the Samsung manager's
 * components, broadcasts and logs), to look for a source that doesn't need the RFCOMM channel.
 */
object Diagnostics {
    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")

    /** Saves the report to Downloads. [done] gets its Uri, or null on failure, on the main thread. */
    fun run(context: Context, done: (Uri?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShellService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .version(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                thread {
                    val uri = try {
                        collect(context, IShell.Stub.asInterface(binder))
                    } catch (e: Exception) {
                        Log.w("Budi", "Diagnostics failed", e)
                        null
                    }
                    Shizuku.unbindUserService(args, this, true)
                    main.post { done(uri) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: RuntimeException) {
            Log.w("Budi", "Can't start the shell service", e)
            done(null)
        }
    }

    private fun collect(context: Context, shell: IShell): Uri {
        val script = context.resources.openRawResource(R.raw.diagnostics).bufferedReader().use { it.readText() }
        val file = File(context.cacheDir, "diagnostics.txt")
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).use { shell.run(script, it) }

        // Swap full Bluetooth addresses for stable placeholders before the report leaves the phone.
        val macs = mutableMapOf<String, String>()
        val report = MAC.replace(file.readText()) { macs.getOrPut(it.value.uppercase()) { "MAC-${macs.size + 1}" } }
        file.delete()

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "budi-diagnostics-${System.currentTimeMillis()}.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(report.toByteArray()) }
        return uri
    }
}
