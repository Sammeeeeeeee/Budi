package io.github.sammeeeeeeee.budi

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import java.util.UUID

/**
 * Galaxy Buds serial protocol, as reverse-engineered by GalaxyBudsClient
 * (github.com/timschneeb/GalaxyBudsClient). Frame: FD [size:2 LE] [id] [payload] [crc16:2 LE] DD,
 * where size covers id + payload + crc.
 */
object Buds {
    val SPP_UUID: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")

    private const val SOM = 0xFD
    private const val EOM = 0xDD
    private const val MSG_EXTENDED_STATUS = 0x61
    private const val MSG_NOISE_CONTROLS_UPDATE = 0x77

    /** Samsung's companion plugins. While one is enabled it owns the buds' control channel. */
    private val SAMSUNG_MANAGERS = listOf(
        "com.samsung.accessory.paranmgr", // Buds3 Pro
        "com.samsung.accessory.jellymgr", // Buds3
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

    /** The enabled Samsung manager that would hold the channel, if any. */
    fun samsungManager(context: Context): String? = SAMSUNG_MANAGERS.firstOrNull { pkg ->
        try {
            context.packageManager.getApplicationInfo(pkg, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun noiseMode(id: Int, payload: ByteArray): NoiseMode? = when (id) {
        MSG_EXTENDED_STATUS -> payload.getOrNull(12)
        MSG_NOISE_CONTROLS_UPDATE -> payload.getOrNull(0)
        else -> null
    }?.let { NoiseMode.of(it.toInt()) }

    /** CRC-16/XMODEM. */
    fun crc16(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        }
        return crc and 0xFFFF
    }

    /** Reassembles frames from an RFCOMM stream; skips anything that fails its checksum. */
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
                val size = (u(start + 1) or (u(start + 2) shl 8)) and 0x3FF
                if (size < 3) {
                    start++
                    continue
                }
                val end = start + size + 3 // index of EOM
                if (end >= len) break
                val crc = u(end - 2) or (u(end - 1) shl 8)
                if (u(end) == EOM && crc16(buf, start + 3, end - 2) == crc) {
                    onFrame(u(start + 3), buf.copyOfRange(start + 4, end - 2))
                    start = end + 1
                } else {
                    start++
                }
            }
            System.arraycopy(buf, start, buf, 0, len - start)
            len -= start
        }

        private fun u(i: Int) = buf[i].toInt() and 0xFF
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
