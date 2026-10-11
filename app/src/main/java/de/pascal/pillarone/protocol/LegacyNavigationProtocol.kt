package de.pascal.pillarone.protocol

data class LegacyNavigationHint(
    val active: Boolean,
    val distance: String,
    val instruction: String,
    val maneuver: Int,
)

/**
 * Existing FF00/FF01 firmware format, not the future versioned app protocol.
 * There is no handshake, expiry or application-level acknowledgement.
 */
object LegacyNavigationProtocol {
    const val MAX_PAYLOAD_BYTES = 20

    fun testTurn(): ByteArray = encode(LegacyNavigationHint(true, "200 m", "Rechts", 2))

    fun off(): ByteArray = "OFF".toByteArray(Charsets.UTF_8)

    fun encode(hint: LegacyNavigationHint): ByteArray {
        require(hint.maneuver in 0..255) { "Maneuver must fit an unsigned byte." }
        requireValidField(hint.distance)
        requireValidField(hint.instruction)
        val text = "${if (hint.active) 1 else 0}|${hint.distance}|${hint.instruction}|${hint.maneuver}"
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Payload exceeds the MTU 23 write limit." }
        return payload
    }

    private fun requireValidField(value: String) {
        require(value.none { it == '|' || it.isISOControl() }) { "Invalid protocol delimiter or control character." }
    }
}
