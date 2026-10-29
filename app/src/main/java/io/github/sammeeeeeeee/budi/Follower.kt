package io.github.sammeeeeeeee.budi

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * Shizuku user service that runs as the shell user, outside Budi's app process, while the buds
 * are connected. Samsung's plugin logs every frame it receives from the buds. This streams those
 * lines straight from logd, filtered by the plugin's pid so it only wakes when the plugin logs,
 * and shows the noise mode in the status bar. Nothing talks to the buds or to Samsung's plugin.
 */
class Follower : IFollower.Stub() {
    private val statusBar: StatusBar? by lazy { runCatching { StatusBar.direct() }.getOrNull() }
    private val readers = mutableListOf<Reader>()
    private var iconPackage = ""
    private var icons = IntArray(0)
    private var labels = emptyArray<String>()
    @Volatile private var mode: NoiseMode? = null

    override fun follow(plugins: Array<String>, iconPackage: String, icons: IntArray, labels: Array<String>) {
        synchronized(this) {
            readers.forEach { it.close() }
            readers.clear()
            this.iconPackage = iconPackage
            this.icons = icons
            this.labels = labels
            plugins.mapTo(readers) { Reader(it).apply { start() } }
        }
    }

    override fun mode() = mode?.wire ?: -1

    override fun destroy() {
        synchronized(this) { readers.forEach { it.close() } }
        statusBar?.hide()
        exitProcess(0)
    }

    @Synchronized
    private fun onMode(new: NoiseMode) {
        if (new == mode) return
        mode = new
        statusBar?.show(iconPackage, icons[new.ordinal], labels[new.ordinal])
    }

    /** Streams one plugin's log from logd and feeds the frames it received to [onMode]. */
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
            var wait = RETRY_MS
            try {
                while (!closed) {
                    val pid = pidOf(plugin)
                    if (pid == null) {
                        // Not running yet (or not this phone's plugin): back off up to the quiet period.
                        sleep(wait)
                        wait = (wait * 2).coerceAtMost(QUIET_MS.toLong())
                        continue
                    }
                    wait = RETRY_MS
                    try {
                        stream(pid)
                    } catch (e: IOException) {
                        // logd unavailable: retry below
                    }
                    if (!closed) sleep(RETRY_MS)
                }
            } catch (e: InterruptedException) {
                // closed
            }
        }

        /** Returns when the plugin restarts or logd drops the connection. */
        private fun stream(pid: Int) {
            LocalSocket(LocalSocket.SOCKET_SEQPACKET).use { s ->
                socket = s
                if (closed) return
                s.connect(LocalSocketAddress("logdr", LocalSocketAddress.Namespace.RESERVED))
                s.soTimeout = QUIET_MS
                // No tail or start: logd replays the buffer first, which recovers the current mode.
                s.outputStream.write("stream lids=0 pid=$pid".toByteArray())
                val frames = Buds.FrameReader { id, payload -> Buds.noiseMode(id, payload)?.let(::onMode) }
                val entry = ByteArray(Logd.MAX_ENTRY)
                while (!closed) {
                    val since = SystemClock.elapsedRealtime()
                    val size = try {
                        s.inputStream.read(entry)
                    } catch (e: IOException) {
                        // A read that times out after a quiet period: check the plugin hasn't restarted.
                        if (closed || SystemClock.elapsedRealtime() - since < QUIET_MS / 2 || pidOf(plugin) != pid) return
                        continue
                    }
                    if (size <= 0) return
                    Logd.message(entry, size)?.let(Buds::received)?.let { frames.feed(it, it.size) }
                }
            }
        }
    }

    private companion object {
        const val RETRY_MS = 5_000L
        const val QUIET_MS = 300_000

        fun pidOf(pkg: String): Int? = File("/proc").list()?.firstNotNullOfOrNull { dir ->
            dir.toIntOrNull()?.takeIf { pid ->
                try {
                    String(File("/proc/$pid/cmdline").readBytes()).substringBefore('\u0000') == pkg
                } catch (e: IOException) {
                    false
                }
            }
        }
    }
}

/** logd's reader protocol: see system/logging/liblog/include/log/log_read.h. */
internal object Logd {
    /** LOGGER_ENTRY_MAX_LEN + 1: logd rejects reads into anything smaller. */
    const val MAX_ENTRY = 5 * 1024 + 1

    /** The message of a text entry: header (hdr_size bytes), then priority, tag\0, message\0. */
    fun message(entry: ByteArray, size: Int): String? {
        if (size < 4) return null
        val header = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getShort(2).toInt() and 0xFFFF
        if (header < 4 || header >= size) return null
        val tagEnd = (header + 1 until size).firstOrNull { entry[it] == 0.toByte() } ?: return null
        val end = (tagEnd + 1 until size).firstOrNull { entry[it] == 0.toByte() } ?: size
        return String(entry, tagEnd + 1, end - tagEnd - 1)
    }
}
