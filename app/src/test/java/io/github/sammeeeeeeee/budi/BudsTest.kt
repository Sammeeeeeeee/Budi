package io.github.sammeeeeeeee.budi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BudsTest {

    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun read(vararg chunks: ByteArray): List<Pair<Int, ByteArray>> {
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val reader = Buds.FrameReader { id, payload -> frames += id to payload }
        chunks.forEach { reader.feed(it, it.size) }
        return frames
    }

    private fun mode(hex: String) = read(bytes(hex)).single().let { (id, payload) -> Buds.noiseMode(id, payload) }

    // Extended status frame quoted in GalaxyBudsClient's Crc16.cs (CRC 0xF30F, sent little-endian).
    private val extendedStatus = bytes("FD 11 00 61 02 00 4B 5F 01 00 00 00 01 05 00 02 00 13 0F F3 DD")
    private val adaptive = bytes("FD 04 00 77 03 AD A1 DD")

    @Test
    fun decodesFrameSplitAcrossReads() {
        val frames = read(extendedStatus.copyOfRange(0, 7), extendedStatus.copyOfRange(7, extendedStatus.size))
        assertEquals(1, frames.size)
        assertEquals(0x61, frames[0].first)
        assertArrayEquals(extendedStatus.copyOfRange(4, 18), frames[0].second)
        assertEquals(NoiseMode.OFF, Buds.noiseMode(frames[0].first, frames[0].second))
    }

    @Test
    fun decodesNoiseControlsUpdate() {
        assertEquals(NoiseMode.ADAPTIVE, mode("FD 04 00 77 03 AD A1 DD"))
    }

    @Test
    fun decodesAcknowledgedModeSetFromThePhone() {
        assertEquals(NoiseMode.AMBIENT, mode("FD 05 50 42 78 02 7F D2 DD"))
    }

    @Test
    fun ignoresOtherAcknowledgements() {
        // Captured from a Galaxy Buds3 Pro on One UI 9: Samsung's plugin setting 360 Audio and the EQ.
        assertNull(mode("FD 06 50 42 7C 01 00 AC 1D DD"))
        assertNull(mode("FD 05 90 42 86 01 D2 D2 DD"))
    }

    @Test
    fun skipsNoiseAndBadChecksums() {
        val corrupt = adaptive.copyOf().also { it[4] = 0x01 }
        val frames = read(bytes("00 DD 12"), corrupt, adaptive, adaptive)
        assertEquals(2, frames.size)
        frames.forEach { assertEquals(NoiseMode.ADAPTIVE, Buds.noiseMode(it.first, it.second)) }
    }

    @Test
    fun ignoresOtherMessagesAndUnknownModes() {
        assertNull(Buds.noiseMode(0x60, byteArrayOf(1)))
        assertNull(Buds.noiseMode(0x77, byteArrayOf(9)))
        assertNull(Buds.noiseMode(0x61, ByteArray(5)))
    }

    @Test
    fun readsFramesFromPluginLogLines() {
        assertArrayEquals(bytes("FD 06 50 42 7C 01 00 AC 1D DD"), Buds.received("RECV++ : FD 06 50 42 7C 01 00 AC 1D DD "))
        assertNull(Buds.received("onSppMessage() : 42"))
    }

    @Test
    fun parsesLogdEntries() {
        val payload = byteArrayOf(4) + "Paran_SppRecvThread".toByteArray() + 0 + "RECV++ : FD 04 00 77 03 AD A1 DD ".toByteArray() + 0
        val entry = ByteBuffer.allocate(28 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(payload.size.toShort()).putShort(28).putInt(28093).putInt(5725)
            .putInt(0).putInt(0).putInt(0).putInt(10626).put(payload).array()
        val message = Logd.message(entry, entry.size)
        assertEquals("RECV++ : FD 04 00 77 03 AD A1 DD ", message)
        assertEquals(NoiseMode.ADAPTIVE, read(Buds.received(message!!)!!).single().let { Buds.noiseMode(it.first, it.second) })
    }
}
