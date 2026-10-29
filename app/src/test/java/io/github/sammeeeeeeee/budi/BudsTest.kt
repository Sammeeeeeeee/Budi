package io.github.sammeeeeeeee.budi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BudsTest {

    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun read(vararg chunks: ByteArray): List<Pair<Int, ByteArray>> {
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val reader = Buds.FrameReader { id, payload -> frames += id to payload }
        chunks.forEach { reader.feed(it, it.size) }
        return frames
    }

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
        val (id, payload) = read(adaptive).single()
        assertEquals(NoiseMode.ADAPTIVE, Buds.noiseMode(id, payload))
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
        assertEquals(null, Buds.noiseMode(0x60, byteArrayOf(1)))
        assertEquals(null, Buds.noiseMode(0x77, byteArrayOf(9)))
        assertEquals(null, Buds.noiseMode(0x61, ByteArray(5)))
    }
}
