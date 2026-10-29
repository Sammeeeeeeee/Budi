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

    private fun reported(hex: String) = read(bytes(hex)).single().let { (id, payload) -> Buds.reported(id, payload) }

    private fun requested(hex: String) = read(bytes(hex)).single().let { (id, payload) -> Buds.requested(id, payload) }

    // Extended status frame quoted in GalaxyBudsClient's Crc16.cs (CRC 0xF30F, sent little-endian).
    private val extendedStatus = bytes("FD 11 00 61 02 00 4B 5F 01 00 00 00 01 05 00 02 00 13 0F F3 DD")
    private val adaptive = bytes("FD 04 00 77 03 AD A1 DD")

    @Test
    fun decodesFrameSplitAcrossReads() {
        val frames = read(extendedStatus.copyOfRange(0, 7), extendedStatus.copyOfRange(7, extendedStatus.size))
        assertEquals(1, frames.size)
        assertEquals(0x61, frames[0].first)
        assertArrayEquals(extendedStatus.copyOfRange(4, 18), frames[0].second)
        assertEquals(NoiseMode.OFF, Buds.reported(frames[0].first, frames[0].second))
    }

    @Test
    fun decodesNoiseControlsUpdate() {
        assertEquals(NoiseMode.ADAPTIVE, reported("FD 04 00 77 03 AD A1 DD"))
    }

    @Test
    fun decodesAcknowledgedModeSetFromThePhone() {
        assertEquals(NoiseMode.AMBIENT, reported("FD 05 50 42 78 02 7F D2 DD"))
        assertEquals(NoiseMode.ADAPTIVE, reported("FD 05 50 42 78 03 5E C2 DD"))
    }

    @Test
    fun decodesModeSetByThePlugin() {
        assertEquals(NoiseMode.ADAPTIVE, requested("FD 04 00 78 03 93 B1 DD"))
        // Captured from a Galaxy Buds3 Pro on One UI 9: Samsung's plugin setting the ANC level.
        assertNull(requested("FD 04 00 83 04 4F 0E DD"))
    }

    @Test
    fun ignoresOtherAcknowledgements() {
        // Captured from a Galaxy Buds3 Pro on One UI 9: Samsung's plugin setting 360 Audio, the EQ and the ANC level.
        assertNull(reported("FD 06 50 42 7C 01 00 AC 1D DD"))
        assertNull(reported("FD 05 90 42 86 01 D2 D2 DD"))
        assertNull(reported("FD 05 90 42 83 04 82 7D DD"))
    }

    @Test
    fun skipsNoiseAndBadChecksums() {
        val corrupt = adaptive.copyOf().also { it[4] = 0x01 }
        val frames = read(bytes("00 DD 12"), corrupt, adaptive, adaptive)
        assertEquals(2, frames.size)
        frames.forEach { assertEquals(NoiseMode.ADAPTIVE, Buds.reported(it.first, it.second)) }
    }

    @Test
    fun dropsAFrameCutShortOnceACompleteOneFollows() {
        // A logged line that ends mid-frame, claiming far more bytes than ever come.
        val frames = read(bytes("FD FF 03 61 02 00"), adaptive)
        assertEquals(NoiseMode.ADAPTIVE, frames.single().let { Buds.reported(it.first, it.second) })
    }

    @Test
    fun ignoresOtherMessagesAndUnknownModes() {
        assertNull(Buds.reported(0x60, byteArrayOf(1)))
        assertNull(Buds.reported(0x77, byteArrayOf(9)))
        assertNull(Buds.reported(0x61, ByteArray(5)))
        assertNull(Buds.requested(0x78, ByteArray(0)))
    }

    @Test
    fun readsFramesFromPluginLogLines() {
        assertArrayEquals(bytes("FD 06 50 42 7C 01 00 AC 1D DD"), Buds.received("RECV++ : FD 06 50 42 7C 01 00 AC 1D DD "))
        assertArrayEquals(bytes("FD 04 00 83 04 4F 0E DD"), Buds.sent("SENT-- : FD 04 00 83 04 4F 0E DD "))
        assertNull(Buds.received("SENT-- : FD 04 00 83 04 4F 0E DD "))
        assertNull(Buds.sent("onSppMessage() : 42"))
    }

    @Test
    fun parsesLogdEntries() {
        val payload = byteArrayOf(4) + "Paran_SppRecvThread".toByteArray() + 0 + "RECV++ : FD 04 00 77 03 AD A1 DD ".toByteArray() + 0
        val entry = ByteBuffer.allocate(28 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(payload.size.toShort()).putShort(28).putInt(28093).putInt(5725)
            .putInt(1_793_273_757).putInt(711_000_000).putInt(0).putInt(10626).put(payload).array()
        val parsed = Logd.entry(entry, entry.size)!!
        assertEquals(1_793_273_757_711L, parsed.time)
        assertEquals("RECV++ : FD 04 00 77 03 AD A1 DD ", parsed.message)
        assertEquals(NoiseMode.ADAPTIVE, read(Buds.received(parsed.message)!!).single().let { Buds.reported(it.first, it.second) })
    }
}
