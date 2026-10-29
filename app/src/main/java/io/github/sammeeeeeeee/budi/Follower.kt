package io.github.sammeeeeeeee.budi

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.view.WindowInsetsController
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Shizuku user service that runs as the shell user, outside Budi's app process, while the buds
 * are connected. Samsung's plugin logs every frame it exchanges with the buds. This streams those
 * lines straight from logd, filtered by the plugin's pid so it only wakes when the plugin logs,
 * and drives Budi's [StatusIcon]: the mode, and the icon colour the app on screen asked for.
 * Nothing talks to the buds or to Samsung's plugin.
 */
class Follower(private val context: Context) : IFollower.Stub() {
    private val readers = mutableListOf<Reader>()
    private val trace = ArrayDeque<String>()
    private val work = Handler(HandlerThread("budi-follower").apply { start() }.looper)
    private var receiver = ""
    @Volatile private var mode: NoiseMode? = null
    private var dark = false
    private var caughtUp = false
    private var icon: IStatusIcon? = null
    private var shown: Pair<NoiseMode, Boolean>? = null
    private var told: NoiseMode? = null
    private var watchingTasks = false
    private val lost = IBinder.DeathRecipient { onIconLost() }

    init {
        StatusBar.clear()
    }

    override fun follow(plugins: Array<String>, receiver: String) {
        synchronized(this) {
            readers.forEach { it.close() }
            readers.clear()
            this.receiver = receiver
            caughtUp = false // say nothing until the replay has found the mode
            shown = null
            told = null
            note("Following ${plugins.joinToString()}")
            plugins.mapTo(readers) { Reader(it).apply { start() } }
        }
    }

    override fun mode() = mode?.wire ?: -1

    override fun trace() = synchronized(trace) { trace.joinToString("\n") }

    override fun watch(icon: IStatusIcon) {
        synchronized(this) {
            if (this.icon?.asBinder() != icon.asBinder()) {
                this.icon?.let { old -> runCatching { old.asBinder().unlinkToDeath(lost, 0) } }
                try {
                    icon.asBinder().linkToDeath(lost, 0)
                } catch (e: RemoteException) {
                    return // already gone
                }
                this.icon = icon
                shown = null
            }
        }
        watchTasks()
        quietNotice()
        publish()
    }

    override fun destroy() {
        synchronized(this) { readers.forEach { it.close() } }
        exitProcess(0)
    }

    @Synchronized
    private fun onMode(new: NoiseMode, time: Long, source: String) {
        if (new == mode) return
        mode = new
        note("${new.name} from $source", time)
    }

    @Synchronized
    private fun onCaughtUp() {
        caughtUp = true
        publish()
    }

    /**
     * Sends Budi's icon the mode and colour, if they're new to it. With no icon yet, wakes Budi's
     * app with a broadcast only the shell may send, and the icon then asks to [watch].
     */
    @Synchronized
    private fun publish() {
        val mode = mode ?: return
        if (!caughtUp) return
        val icon = icon
        if (icon != null) {
            if (shown == mode to dark) return
            shown = mode to dark
            try {
                icon.show(mode.wire, dark)
            } catch (e: RemoteException) {
                // gone: onIconLost follows
            }
            return
        }
        if (told == mode) return
        told = mode
        try {
            ProcessBuilder(
                "/system/bin/cmd", "activity", "broadcast", "--user", "current", "-n", receiver,
                "--receiver-foreground", "--ei", ModeReceiver.EXTRA_MODE, mode.wire.toString(),
            ).redirectErrorStream(true).start().run {
                inputStream.readBytes()
                waitFor()
            }
        } catch (e: IOException) {
            note("Couldn't wake the app: $e")
        }
    }

    /** Budi's app went away while the buds are connected: bring it back, unless it was stopped. */
    private fun onIconLost() {
        synchronized(this) {
            icon = null
            shown = null
            told = null
        }
        note("Budi's app went away")
        work.postDelayed({ publish() }, REVIVE_MS)
    }

    /**
     * Status bar icons turn dark when the app on screen asks for it. Android copies each app's
     * request into its task description, so this follows which task is on top and reads it there.
     * Every task change pings [tasks]; it looks again once they settle.
     */
    private val tasks = object : Binder() {
        init {
            attachInterface(null, "android.app.ITaskStackListener")
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            work.removeCallbacks(recolour)
            work.postDelayed(recolour, SETTLE_MS)
            return true
        }
    }

    private val recolour = Runnable {
        @Suppress("DEPRECATION") // with REAL_GET_TASKS, which the shell holds, it still lists every task
        val top = try {
            context.getSystemService(ActivityManager::class.java).getRunningTasks(1).firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return@Runnable
        val dark = try {
            val appearance = ActivityManager.TaskDescription::class.java.getMethod("getSystemBarsAppearance").invoke(top.taskDescription) as Int
            appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0
        } catch (e: Exception) {
            return@Runnable
        }
        if (synchronized(this) { (dark != this.dark).also { this.dark = dark } }) {
            note("${if (dark) "Dark" else "Light"} icons for ${top.topActivity?.packageName}")
            publish()
        }
    }

    @Synchronized
    private fun watchTasks() {
        if (watchingTasks) return
        try {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
            val listener = Class.forName("android.app.ITaskStackListener\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, tasks)
            atm.javaClass.getMethod("registerTaskStackListener", Class.forName("android.app.ITaskStackListener"))
                .invoke(atm, listener)
            watchingTasks = true
            work.post(recolour)
        } catch (e: Exception) {
            note("Can't follow the app on screen: $e")
        }
    }

    /**
     * Android posts "Budi is displaying over other apps" for as long as the icon shows. Turns that
     * notice off for Budi alone, as the user could from the shade; Android keeps it off.
     */
    private fun quietNotice() {
        val pkg = ComponentName.unflattenFromString(receiver)?.packageName ?: return
        var quiet = false
        for ((i, delay) in NOTICE_TRIES_MS.withIndex()) {
            work.postDelayed({
                if (quiet) return@postDelayed
                try {
                    val notifications = NotificationManager::class.java.getMethod("getService").invoke(null)!!
                    val notice = NotificationChannel(NOTICE_PREFIX + pkg, pkg, NotificationManager.IMPORTANCE_NONE).apply {
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                    notifications.javaClass
                        .getMethod("updateNotificationChannelForPackage", String::class.java, Int::class.javaPrimitiveType, NotificationChannel::class.java)
                        .invoke(notifications, "android", Process.SYSTEM_UID, notice)
                    quiet = true
                    note("Android's overlay notice is off")
                } catch (e: Exception) {
                    // not posted yet: try again, then give up
                    if (i == NOTICE_TRIES_MS.lastIndex) note("Couldn't turn off Android's overlay notice: ${e.cause ?: e}")
                }
            }, delay)
        }
    }

    /** Keeps the latest lines for Budi's report. */
    private fun note(line: String, time: Long = System.currentTimeMillis()) {
        synchronized(trace) {
            if (trace.size == TRACE_LINES) trace.removeFirst()
            trace.addLast("${TIME.format(Date(time))} $line")
        }
    }

    /** Streams one plugin's log from logd and follows the frames in it. */
    private inner class Reader(private val plugin: String) : Thread("budi-$plugin") {
        @Volatile private var closed = false
        @Volatile private var socket: LocalSocket? = null

        fun close() {
            closed = true
            interrupt()
            try {
                socket?.shutdownInput()
            } catch (e: IOException) {
                // already closed
            }
        }

        override fun run() {
            var misses = 0
            try {
                while (!closed) {
                    val pid = pidOf(plugin)
                    if (pid == null) {
                        if (misses++ == 0) note("$plugin isn't running")
                        // Samsung starts it as the buds connect: look often at first, then rarely.
                        sleep(if (misses <= QUICK_LOOKS) RETRY_MS else IDLE_MS)
                        continue
                    }
                    misses = 0
                    note("$plugin is pid $pid")
                    try {
                        stream(pid)
                    } catch (e: Exception) {
                        // logd went away, or something unexpected: retry rather than die.
                        if (closed) break
                        note("$plugin: $e")
                        sleep(RETRY_MS)
                    }
                }
            } catch (e: InterruptedException) {
                // closed
            }
        }

        /** Returns once the plugin's process has gone. */
        private fun stream(pid: Int) {
            LocalSocket(LocalSocket.SOCKET_SEQPACKET).use { s ->
                socket = s
                if (closed) return
                s.connect(LocalSocketAddress("logdr", LocalSocketAddress.Namespace.RESERVED))
                // No tail or start: logd replays the buffer first, which recovers the current mode.
                // The icon hears only the outcome: the replay is over once the log goes quiet or
                // reaches the present.
                s.soTimeout = REPLAY_QUIET_MS
                s.outputStream.write("stream lids=0 pid=$pid".toByteArray())
                val started = System.currentTimeMillis()
                var replaying = true
                val caughtUp = {
                    replaying = false
                    s.soTimeout = CHECK_MS
                    onCaughtUp()
                }
                var time = 0L
                val fromBuds = Buds.FrameReader { id, payload ->
                    Buds.reported(id, payload)?.let { onMode(it, time, "buds' %02X".format(id)) }
                }
                val fromPlugin = Buds.FrameReader { id, payload ->
                    Buds.requested(id, payload)?.let { onMode(it, time, "plugin's %02X".format(id)) }
                }
                val buf = ByteArray(Logd.MAX_ENTRY)
                var checked = SystemClock.elapsedRealtime()
                while (!closed) {
                    val since = SystemClock.elapsedRealtime()
                    val size: Int? = try {
                        s.inputStream.read(buf)
                    } catch (e: IOException) {
                        if (closed) return
                        if (replaying) {
                            caughtUp()
                            continue
                        }
                        if (SystemClock.elapsedRealtime() - since < CHECK_MS / 2) throw e
                        null // a quiet spell
                    }
                    // Now and then, make sure the pid is still the plugin's: it restarts at times.
                    val now = SystemClock.elapsedRealtime()
                    if (now - checked >= CHECK_MS) {
                        if (!isRunning(pid, plugin)) return
                        checked = now
                    }
                    if (size == null) continue
                    if (size <= 0) throw IOException("logd ended the stream")
                    val entry = Logd.entry(buf, size) ?: continue
                    time = entry.time
                    if (replaying && time >= started) caughtUp()
                    val received = Buds.received(entry.message)
                    val sent = if (received == null) Buds.sent(entry.message) else null
                    if (received != null || sent != null || NOISE.containsMatchIn(entry.message)) {
                        note(entry.message.trim().take(TRACE_WIDTH), time)
                    }
                    when {
                        received != null && Buds.cutShort(entry.message) ->
                            Buds.reportedInHead(received)?.let { onMode(it, time, "buds' 61") }
                        received != null -> fromBuds.feed(received, received.size)
                        sent != null -> fromPlugin.feed(sent, sent.size)
                    }
                    if (!replaying) publish()
                }
            }
        }
    }

    private companion object {
        const val RETRY_MS = 5_000L
        const val IDLE_MS = 60_000L
        const val QUICK_LOOKS = 12
        const val REPLAY_QUIET_MS = 500
        const val CHECK_MS = 30_000
        const val SETTLE_MS = 120L
        const val REVIVE_MS = 3_000L
        const val TRACE_LINES = 80
        const val TRACE_WIDTH = 240
        const val NOTICE_PREFIX = "com.android.server.wm.AlertWindowNotification - "
        val NOTICE_TRIES_MS = longArrayOf(500, 2_000, 6_000)
        val NOISE = Regex("noiseControls|NOISE_CONTROLS|NOISE_REDUCTION")
        val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

        fun pidOf(pkg: String): Int? = File("/proc").list()?.firstNotNullOfOrNull { dir ->
            dir.toIntOrNull()?.takeIf { isRunning(it, pkg) }
        }

        fun isRunning(pid: Int, pkg: String) = try {
            String(File("/proc/$pid/cmdline").readBytes()).substringBefore('\u0000') == pkg
        } catch (e: IOException) {
            false
        }
    }
}

/** logd's reader protocol: see system/logging/liblog/include/log/log_read.h. */
internal object Logd {
    /** LOGGER_ENTRY_MAX_LEN + 1: logd rejects reads into anything smaller. */
    const val MAX_ENTRY = 5 * 1024 + 1

    class Entry(val time: Long, val message: String)

    /** A text entry: header (hdr_size bytes, the time at 12), then priority, tag\0, message\0. */
    fun entry(buf: ByteArray, size: Int): Entry? {
        if (size < 20) return null
        val header = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = header.getShort(2).toInt() and 0xFFFF
        if (headerSize < 20 || headerSize >= size) return null
        val time = (header.getInt(12).toLong() and 0xFFFFFFFFL) * 1000 + (header.getInt(16).toLong() and 0xFFFFFFFFL) / 1_000_000
        val tagEnd = (headerSize + 1 until size).firstOrNull { buf[it] == 0.toByte() } ?: return null
        val end = (tagEnd + 1 until size).firstOrNull { buf[it] == 0.toByte() } ?: size
        return Entry(time, String(buf, tagEnd + 1, end - tagEnd - 1))
    }
}
