package io.github.sammeeeeeeee.budi

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Looks for a way to follow the noise control mode while Samsung's manager owns the buds:
 * - a shell report (Bluetooth metadata, settings, the manager's components, broadcasts, logs);
 * - Samsung's buds widget, hosted off-screen for [RECORD_MS] to see if it mirrors the mode;
 * - contact sheets of Samsung's own icons, to load the exact One UI glyphs by name.
 */
object Diagnostics {
    private const val SYSTEMUI = "com.android.systemui"
    private const val RECORD_MS = 20_000L
    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")
    private val main = Handler(Looper.getMainLooper())

    /** Saves everything to Downloads. [done] gets the files (empty on failure), on the main thread. */
    fun run(context: Context, manager: String, done: (List<Uri>) -> Unit) {
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShellService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .version(1)
        val connection = object : ServiceConnection {
            private var started = false

            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (started) return
                started = true
                thread {
                    val files = try {
                        collect(context, manager, IShell.Stub.asInterface(binder))
                    } catch (e: Exception) {
                        Log.w("Budi", "Diagnostics failed", e)
                        emptyList()
                    }
                    Shizuku.unbindUserService(args, this, true)
                    main.post { done(files) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: RuntimeException) {
            Log.w("Budi", "Can't start the shell service", e)
            done(emptyList())
        }
    }

    private fun collect(context: Context, manager: String, shell: IShell): List<Uri> {
        val grant = exec(context, shell, "appwidget grantbind --package ${context.packageName} --user current 2>&1")
        val started = SystemClock.elapsedRealtime()
        val probe = onMain { runCatching { WidgetProbe(context, manager).apply { start() } } }
        val script = context.resources.openRawResource(R.raw.diagnostics).bufferedReader().use { it.readText() }
        val shellReport = exec(context, shell, "P=$manager\n$script")
        SystemClock.sleep((RECORD_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
        val widgetReport = probe.fold({ onMain { it.stop() } }, { "failed: $it" })

        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val report = "Budi $version, manager $manager\n$shellReport\n===== widgets\ngrantbind: $grant\n$widgetReport"

        val stamp = System.currentTimeMillis()
        val files = mutableListOf(save(context, "budi-$stamp.txt", "text/plain") { it.write(pseudonymise(report).toByteArray()) })
        for (pkg in listOf(manager, SYSTEMUI)) {
            try {
                IconSheet.render(context, pkg).forEach { page ->
                    files += save(context, "budi-$stamp-icons-${files.size}.png", "image/png") {
                        page.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    page.recycle()
                }
            } catch (e: Exception) {
                Log.w("Budi", "Icon sheet for $pkg failed", e)
            }
        }
        return files
    }

    private fun exec(context: Context, shell: IShell, script: String): String {
        val file = File(context.cacheDir, "shell.txt")
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).use { shell.run(script, it) }
        return file.readText().also { file.delete() }
    }

    /** Swaps full Bluetooth addresses for stable placeholders before the report leaves the phone. */
    private fun pseudonymise(report: String): String {
        val macs = mutableMapOf<String, String>()
        return MAC.replace(report) { macs.getOrPut(it.value.uppercase()) { "MAC-${macs.size + 1}" } }
    }

    private fun save(context: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use(write)
        return uri
    }

    private fun <T> onMain(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }
}
