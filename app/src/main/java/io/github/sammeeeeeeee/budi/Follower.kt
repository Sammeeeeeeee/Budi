package io.github.sammeeeeeeee.budi

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
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
 * and tells Budi's app each new mode. Nothing talks to the buds or to Samsung's plugin.
 */
class Follower : IFollower.Stub() {
    private val readers = mutableListOf<Reader>()
    private val trace = ArrayDeque<String>()
    private var receiver = ""
    @Volatile private var mode: NoiseMode? = null
    private var told: NoiseMode? = null

    init {
        StatusBar.clear()
    }

    override fun follow(plugins: Array<String>, receiver: String) {
        synchronized(this) {
            readers.forEach { it.close() }
            readers.clear()
            this.receiver = receiver
            told = null // tell the app again once the replay has found the mode
            note("Following ${plugins.joinToString()}")
            plugins.mapTo(readers) { Reader(it).apply { start() } }
        }
    }

    override fun mode() = mode?.wire ?: -1

    override fun trace() = synchronized(trace) { trace.joinToString("\n") }

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

    /** Sends the app the mode, if it's new to it. Budi's app shows it as a status bar icon. */
    @Synchronized
    private fun tell() {
        val mode = mode ?: return
        if (mode == told) return
        told = mode
        try {
            ProcessBuilder(
                "/system/bin/cmd", "activity", "broadcast", "--user", "current", "-n", receiver,
                "--receiver-foreground", "--include-stopped-packages", "--ei", ModeReceiver.EXTRA_MODE, mode.wire.toString(),
            ).redirectErrorStream(true).start().run {
                inputStream.readBytes()
                waitFor()
            }
        } catch (e: IOException) {
            note("Couldn't tell the app: $e")
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
                // The app hears only the outcome: the replay is over once the log goes quiet or
                // reaches the present.
                s.soTimeout = REPLAY_QUIET_MS
                s.outputStream.write("stream lids=0 pid=$pid".toByteArray())
                val started = System.currentTimeMillis()
                var replaying = true
                val caughtUp = {
                    replaying = false
                    s.soTimeout = CHECK_MS
                    tell()
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
                    if (!replaying) tell()
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
        const val TRACE_LINES = 80
        const val TRACE_WIDTH = 240
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
