package de.pascal.pillarone.protocol

import org.junit.Assert.*
import org.junit.Test

class MediaBridgeCodecTest {
    @Test fun exactFrameAndClear() {
        assertArrayEquals(byteArrayOf(1, 1, 0x34, 0x12, 0, 1, 1, 1, 65, 66),
            MediaBridgeCodec.frames(MediaSnapshot("A", "B", true), 0x1234).single())
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 1, 0, 0),
            MediaBridgeCodec.frames(MediaSnapshot(), 0).single())
    }

    @Test fun maximumAndSequentialHeaders() {
        val frames = MediaBridgeCodec.frames(MediaSnapshot("T".repeat(100), "A".repeat(100), true), 65535)
        assertEquals(11, frames.size)
        frames.forEachIndexed { index, frame ->
            assertEquals(if (index == 10) 14 else 20, frame.size)
            assertArrayEquals(byteArrayOf(1, 1, -1, -1, index.toByte(), 11, 63, 63), frame.copyOf(8))
        }
        assertEquals("T".repeat(63) + "A".repeat(63),
            frames.flatMap { it.drop(8) }.toByteArray().toString(Charsets.UTF_8))
    }

    @Test fun utf8TruncatesOnlyAtCodePointBoundary() {
        for (symbol in listOf("\u00e4", "\u20ac", "\ud83d\ude80")) {
            val title = "x".repeat(61) + symbol + "suffix"
            val bytes = MediaBridgeCodec.frames(MediaSnapshot(title, symbol.repeat(40)), 1)
                .flatMap { it.drop(8) }.toByteArray()
            val expectedTitle = if (symbol.toByteArray().size <= 2) "x".repeat(61) + symbol else "x".repeat(61)
            assertTrue(bytes.toString(Charsets.UTF_8).startsWith(expectedTitle))
            assertFalse(bytes.toString(Charsets.UTF_8).contains('\ufffd'))
            assertTrue(bytes.size <= 126)
        }
    }

    @Test fun actionsAreStrictAndLittleEndian() {
        for (action in 1..5) assertEquals(MediaBridgeCodec.Control(action, 0x1234),
            MediaBridgeCodec.control(byteArrayOf(1, action.toByte(), 0x34, 0x12)))
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 0, 0, 0), byteArrayOf(2, 1, 0, 0),
            byteArrayOf(1, 6, 0, 0), byteArrayOf(1, 1, 0, 0, 0))) assertNull(MediaBridgeCodec.control(bytes))
    }

    @Test fun controlsDeduplicateWrapAndResetPerConnection() {
        val deduplicator = MediaControlDeduplicator()
        fun accept(id: Int) = deduplicator.accept(MediaBridgeCodec.Control(1, id))
        assertTrue(accept(65534))
        assertFalse(accept(65534))
        assertFalse(accept(65533))
        assertTrue(accept(65535))
        assertTrue(accept(0))
        assertTrue(accept(1))
        assertFalse(accept(65535))
        deduplicator.reset()
        assertTrue(accept(1))
    }
}