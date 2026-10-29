package io.github.sammeeeeeeee.budi

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.UUID

/**
 * Galaxy Buds serial protocol, as reverse-engineered by GalaxyBudsClient
 * (github.com/timschneeb/GalaxyBudsClient). Frame: FD [size:2 LE] [id] [payload] [crc16:2 LE] DD,
 * where size covers id + payload + crc. Budi never opens the link itself: Samsung's plugin logs
 * every frame it receives ("RECV++ : FD … DD") and sends ("SENT-- : FD … DD"), and Budi reads those.
 */
object Buds {
    private val SPP_UUID: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")

    private const val SOM = 0xFD
    private const val EOM = 0xDD
    private const val MSG_ACK = 0x42
    private const val MSG_EXTENDED_STATUS = 0x61
    private const val MSG_NOISE_CONTROLS_UPDATE = 0x77
    private const val MSG_NOISE_CONTROLS = 0x78

    private val RECEIVED = Regex("""RECV\+\+ ?: ?((?:[0-9A-Fa-f]{2} ?)+)""")
    private val SENT = Regex("""SENT-- ?: ?((?:[0-9A-Fa-f]{2} ?)+)""")

    /** Samsung's buds plugins: whichever owns the buds' link logs the frames it exchanges. */
    private val SAMSUNG_PLUGINS = listOf(
        "com.samsung.accessory.paranmgr", // Buds3 Pro
        "com.samsung.accessory.jellymgr", // Buds3
        "com.samsung.accessory.budsunitemgr", // Buds4 and newer
        "com.samsung.accessory.zenithmgr",
        "com.samsung.accessory.berrymgr",
        "com.samsung.accessory.pearlmgr",
        "com.samsung.accessory.neobeanmgr",
        "com.samsung.accessory.popcornmgr",
        "com.samsung.accessory.fridaymgr",
    )

    fun isBuds(device: BluetoothDevice): Boolean = try {
        device.uuids?.any { it.uuid == SPP_UUID } ?: (device.name?.startsWith("Galaxy Buds") == true)
    } catch (e: SecurityException) {
        false
    }

    /** Whether paired buds are connected, or null if Android won't say. */
    fun connected(context: Context): Boolean? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            HiddenApiBypass.addHiddenApiExemptions("L")
            val isConnected = BluetoothDevice::class.java.getMethod("isConnected") // a system API
            adapter?.bondedDevices?.filter(::isBuds)?.any { isConnected.invoke(it) == true }
        } catch (e: Exception) {
            null
        }
    }

    /** The installed, enabled Samsung plugins. */
    fun samsungPlugins(context: Context): List<String> = SAMSUNG_PLUGINS.filter { pkg ->
        try {
            context.packageManager.getApplicationInfo(pkg, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** The bytes in a plugin's "RECV++ : FD 06 50 … DD" log line, or null for any other line. */
    fun received(message: String): ByteArray? = bytes(RECEIVED, message)

    /** The bytes in a plugin's "SENT-- : FD 04 00 … DD" log line, or null for any other line. */
    fun sent(message: String): ByteArray? = bytes(SENT, message)

    private fun bytes(line: Regex, message: String): ByteArray? = line.find(message)?.groupValues?.get(1)
        ?.replace(" ", "")?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

    /**
     * The noise mode a frame from the buds reports: the extended status on connect, an update
     * after a change on the buds, or the buds' acknowledgement of a mode set from the phone
     * ([MSG_ACK] echoes the request's id, then its value).
     */
    fun reported(id: Int, payload: ByteArray): NoiseMode? = when (id) {
        MSG_EXTENDED_STATUS -> payload.getOrNull(12)
        MSG_NOISE_CONTROLS_UPDATE -> payload.getOrNull(0)
        MSG_ACK -> payload.getOrNull(1)?.takeIf { payload[0].toInt() == MSG_NOISE_CONTROLS }
        else -> null
    }?.let { NoiseMode.of(it.toInt()) }

    /** The plugin logs frames longer than about 40 bytes cut short: "FD … .. .. .. … DD". */
    fun cutShort(message: String) = message.contains("..")

    /** The mode in the head of a cut-short extended status, which holds it before the cut. */
    fun reportedInHead(head: ByteArray): NoiseMode? = head.getOrNull(16)
        ?.takeIf { (head[0].toInt() and 0xFF) == SOM && (head[3].toInt() and 0xFF) == MSG_EXTENDED_STATUS }
        ?.let { NoiseMode.of(it.toInt()) }

    /** The noise mode a frame from the plugin sets. */
    fun requested(id: Int, payload: ByteArray): NoiseMode? =
        payload.getOrNull(0)?.takeIf { id == MSG_NOISE_CONTROLS }?.let { NoiseMode.of(it.toInt()) }

    /** CRC-16/XMODEM. */
    fun crc16(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        }
        return crc and 0xFFFF
    }

    /**
     * Reassembles frames from a byte stream and skips anything that fails its checksum. A frame cut
     * short is dropped as soon as a complete one follows it, so it can't hold up the rest.
     */
    class FrameReader(private val onFrame: (id: Int, payload: ByteArray) -> Unit) {
        private var buf = ByteArray(1024)
        private var len = 0

        fun feed(data: ByteArray, count: Int) {
            if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
            System.arraycopy(data, 0, buf, len, count)
            len += count

            var start = 0
            while (true) {
                while (start < len && u(start) != SOM) start++
                if (len - start < 3) break
                val end = end(start)
                when {
                    end > 0 -> {
                        onFrame(u(start + 3), buf.copyOfRange(start + 4, end - 2))
                        start = end + 1
                    }
                    end == BAD -> start++
                    else -> start = (start + 1 until len).firstOrNull { u(it) == SOM && end(it) > 0 } ?: break
                }
            }
            System.arraycopy(buf, start, buf, 0, len - start)
            len -= start
        }

        /** The index of the EOM of the frame at [start], [BAD] if there isn't one, or [SHORT] if it isn't all here yet. */
        private fun end(start: Int): Int {
            if (len - start < 3) return SHORT
            val size = (u(start + 1) or (u(start + 2) shl 8)) and 0x3FF
            if (size < 3) return BAD
            val end = start + size + 3
            if (end >= len) return SHORT
            val crc = u(end - 2) or (u(end - 1) shl 8)
            return if (u(end) == EOM && crc16(buf, start + 3, end - 2) == crc) end else BAD
        }

        private fun u(i: Int) = buf[i].toInt() and 0xFF

        private companion object {
            const val BAD = -1
            const val SHORT = -2
        }
    }
}

enum class NoiseMode(val wire: Int, val icon: Int, val label: Int) {
    OFF(0, R.drawable.ic_noise_off, R.string.mode_off),
    ANC(1, R.drawable.ic_noise_anc, R.string.mode_anc),
    AMBIENT(2, R.drawable.ic_noise_ambient, R.string.mode_ambient),
    ADAPTIVE(3, R.drawable.ic_noise_adaptive, R.string.mode_adaptive);

    companion object {
        fun of(wire: Int) = entries.firstOrNull { it.wire == wire }
    }
}
