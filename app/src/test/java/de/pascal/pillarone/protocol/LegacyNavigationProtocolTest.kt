package de.pascal.pillarone.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyNavigationProtocolTest {
    @Test
    fun testTurnMatchesFirmwareFixture() {
        val bytes = LegacyNavigationProtocol.testTurn()
        assertArrayEquals("1|200 m|Rechts|2".toByteArray(Charsets.UTF_8), bytes)
        assertEquals(16, bytes.size)
    }

    @Test
    fun offMatchesFirmwareFixture() {
        assertArrayEquals("OFF".toByteArray(Charsets.UTF_8), LegacyNavigationProtocol.off())
    }

    @Test
    fun inactiveHintUsesZero() {
        assertEquals("0|1 m|Links|1", encode(false, "1 m", "Links", 1))
    }

    @Test
    fun acceptsExactlyTwentyBytes() {
        assertEquals(20, encode(true, "12345", "1234567890", 2).toByteArray(Charsets.UTF_8).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPayload() {
        encode(true, "123456", "1234567890", 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun checksUtf8BytesRatherThanCharacters() {
        encode(true, "12345", "\u00e4\u00e4\u00e4\u00e4\u00e4\u00e4", 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDelimiterInjection() {
        encode(true, "200 m", "R|OFF", 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsControlCharacters() {
        encode(true, "200 m", "R\n", 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeManeuver() {
        encode(true, "200 m", "Rechts", -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverflowingManeuver() {
        encode(true, "200 m", "Rechts", 256)
    }

    private fun encode(active: Boolean, distance: String, instruction: String, maneuver: Int) =
        LegacyNavigationProtocol.encode(LegacyNavigationHint(active, distance, instruction, maneuver))
            .toString(Charsets.UTF_8)
}
