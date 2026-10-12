package de.pascal.pillarone.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class MessageType(val wire: Int) {
    HELLO(1), HELLO_ACK(2), NAV_UPDATE(0x10), NAV_END(0x11),
    GET_MENU(0x20), MENU_STATE(0x21), SET_MENU(0x22), ACCELERATION_SAMPLE(0x30), ACK(0x80);

    companion object {
        fun fromWire(value: Int): MessageType =
            entries.firstOrNull { it.wire == value } ?: throw ProtocolException("Unknown message type")
    }
}

enum class AckStatus(val wire: Int) {
    OK(0), UNSUPPORTED_VERSION(1), INVALID_PAYLOAD(2), WRONG_SESSION(3),
    STALE_SEQUENCE(4), BUSY(5), UNSUPPORTED_COMMAND(6), NOT_AUTHORIZED(7),
    CONFLICT(8), STORAGE_FAILED(9);

    companion object {
        fun fromWire(value: Int): AckStatus =
            entries.firstOrNull { it.wire == value } ?: throw ProtocolException("Unknown ACK status")
    }
}

open class ProtocolException(message: String) : IllegalArgumentException(message)

data class V1Message(val type: MessageType, val session: Long, val sequence: Int, val payload: ByteArray)
data class V1Frame(
    val type: MessageType,
    val session: Long,
    val sequence: Int,
    val index: Int,
    val count: Int,
    val payload: ByteArray,
)

data class DeviceCapabilities(
    val deviceId: String,
    val bits: Int,
    val maxMessageBytes: Int,
    val maxTtlSeconds: Int,
) {
    val supportsMenu: Boolean get() = bits and 4 != 0
    val supportsAcceleration: Boolean get() = bits and 8 != 0
    val supportsAutomaticAcceleration: Boolean get() = supportsAcceleration && bits and 16 != 0
}

data class HelloAck(val status: AckStatus, val capabilities: DeviceCapabilities?)
data class CommandAck(val request: MessageType, val status: AckStatus)
data class NavigationHint(
    val ttlSeconds: Int,
    val distanceMetres: Long,
    val maneuver: Int,
    val roundaboutExit: Int = 0,
    val instruction: String,
    val street: String = "",
)

object V1Protocol {
    const val MAX_PAYLOAD = 192
    const val ASSEMBLY_MS = 2_000L
    const val RESPONSE_MS = 3_000L

    private fun valid(condition: Boolean, message: String) {
        if (!condition) throw ProtocolException(message)
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 255
    private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
    private fun ByteArray.u32(offset: Int): Long =
        (0..3).fold(0L) { value, index -> value or (u8(offset + index).toLong() shl (index * 8)) }

    private fun ByteArray.put(offset: Int, value: Long, bytes: Int) {
        repeat(bytes) { this[offset + it] = (value ushr (it * 8)).toByte() }
    }

    fun frames(message: V1Message): List<ByteArray> {
        valid(message.session in 1..0xffff_ffffL, "Session must be nonzero uint32")
        valid(message.sequence in 0..65535, "Sequence must be uint16")
        valid(message.payload.size <= MAX_PAYLOAD, "Payload too large")
        val count = maxOf(1, (message.payload.size + 7) / 8)
        return List(count) { index ->
            val payload = message.payload.copyOfRange(index * 8, minOf((index + 1) * 8, message.payload.size))
            ByteArray(12 + payload.size).apply {
                this[0] = 0x50
                this[1] = 0x31
                this[2] = 1
                this[3] = message.type.wire.toByte()
                put(4, message.session, 4)
                put(8, message.sequence.toLong(), 2)
                this[10] = index.toByte()
                this[11] = count.toByte()
                payload.copyInto(this, 12)
            }
        }
    }

    fun decodeFrame(bytes: ByteArray): V1Frame {
        valid(bytes.size in 12..20, "Invalid frame length")
        valid(bytes.u8(0) == 0x50 && bytes.u8(1) == 0x31 && bytes.u8(2) == 1, "Invalid magic/version")
        val type = MessageType.fromWire(bytes.u8(3))
        val session = bytes.u32(4)
        val count = bytes.u8(11)
        val index = bytes.u8(10)
        valid(session != 0L, "Zero session")
        valid(count in 1..24 && index < count, "Invalid fragment coordinates")
        val length = bytes.size - 12
        valid(
            if (index < count - 1) length == 8 else length in 1..8 || (count == 1 && length == 0),
            "Invalid fragment length",
        )
        return V1Frame(type, session, bytes.u16(8), index, count, bytes.copyOfRange(12, bytes.size))
    }

    fun helloAck(payload: ByteArray): HelloAck {
        valid(payload.size == 15, "Invalid HELLO_ACK length")
        val status = AckStatus.fromWire(payload.u8(0))
        valid(status.wire <= 7, "Invalid handshake status")
        if (status != AckStatus.OK) {
            valid(payload.drop(1).all { it == 0.toByte() }, "Invalid rejected HELLO_ACK")
            return HelloAck(status, null)
        }
        val id = payload.copyOfRange(1, 9)
        val bits = payload.u16(9)
        val maximum = payload.u16(11)
        val ttl = payload.u16(13)
        valid(id.any { it != 0.toByte() }, "Zero device ID")
        valid(bits and 3 == 3, "Required capabilities absent")
        valid(maximum in 10..MAX_PAYLOAD && ttl in 1..30, "Invalid capability bounds")
        return HelloAck(
            status,
            DeviceCapabilities(id.joinToString("") { "%02x".format(it.toInt() and 255) }, bits, maximum, ttl),
        )
    }

    fun commandAck(payload: ByteArray): CommandAck {
        valid(payload.size == 2, "Invalid ACK length")
        val type = MessageType.fromWire(payload.u8(0))
        valid(type in setOf(MessageType.NAV_UPDATE, MessageType.NAV_END, MessageType.GET_MENU, MessageType.SET_MENU), "Invalid ACK request type")
        val status = AckStatus.fromWire(payload.u8(1))
        valid(status.wire <= 7 || type == MessageType.GET_MENU || type == MessageType.SET_MENU, "Invalid command status")
        return CommandAck(type, status)
    }

    fun testTurn(): NavigationHint = NavigationHint(5, 200, 2, instruction = "Rechts")

    fun navigation(hint: NavigationHint, maximum: Int = MAX_PAYLOAD, maxTtl: Int = 30): ByteArray {
        valid(maximum in 10..MAX_PAYLOAD && maxTtl in 1..30, "Invalid negotiated limits")
        valid(hint.ttlSeconds in 1..maxTtl, "TTL outside negotiated limit")
        valid(hint.distanceMetres in 0..1_000_000L, "Distance out of range")
        valid(hint.maneuver in 0..7, "Unknown maneuver")
        valid(
            if (hint.maneuver == 3) hint.roundaboutExit in 1..16 else hint.roundaboutExit == 0,
            "Invalid roundabout exit",
        )
        val instruction = encodeText(hint.instruction)
        val street = encodeText(hint.street)
        valid(instruction.size in 1..96 && hint.instruction.isNotBlank(), "Invalid instruction")
        valid(street.size <= 64, "Street too long")
        valid(10 + instruction.size + street.size <= maximum, "Payload exceeds negotiated maximum")
        return ByteArray(10 + instruction.size + street.size).apply {
            put(0, hint.ttlSeconds.toLong(), 2)
            put(2, hint.distanceMetres, 4)
            this[6] = hint.maneuver.toByte()
            this[7] = hint.roundaboutExit.toByte()
            this[8] = instruction.size.toByte()
            this[9] = street.size.toByte()
            instruction.copyInto(this, 10)
            street.copyInto(this, 10 + instruction.size)
        }
    }

    fun decodeNavigation(payload: ByteArray, maximum: Int = MAX_PAYLOAD, maxTtl: Int = 30): NavigationHint {
        valid(payload.size >= 10, "Short navigation payload")
        val instructionLength = payload.u8(8)
        val streetLength = payload.u8(9)
        valid(payload.size == 10 + instructionLength + streetLength, "Navigation length mismatch")
        val hint = NavigationHint(
            payload.u16(0), payload.u32(2), payload.u8(6), payload.u8(7),
            decodeText(payload.copyOfRange(10, 10 + instructionLength)),
            decodeText(payload.copyOfRange(10 + instructionLength, payload.size)),
        )
        navigation(hint, maximum, maxTtl)
        return hint
    }

    private fun encodeText(text: String): ByteArray {
        valid(text.none { it.code in 0..31 || it.code in 127..159 }, "Control character")
        val buffer = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw ProtocolException("Invalid Unicode")
        }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun decodeText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw ProtocolException("Invalid UTF-8")
    }
}

/** One bounded assembly. Time is supplied by the caller's monotonic clock. */
class V1Reassembler(private val session: Long) {
    private var first: V1Frame? = null
    private var startedAt = 0L
    private var next = 0
    private var bytes = ByteArray(0)
    val expiresAt: Long? get() = if (first != null) startedAt + V1Protocol.ASSEMBLY_MS else null

    fun reset() {
        first = null
        next = 0
        bytes = ByteArray(0)
    }

    fun expire(now: Long): Boolean {
        if (first != null && now - startedAt >= V1Protocol.ASSEMBLY_MS) {
            reset()
            return true
        }
        return false
    }

    fun accept(frame: ByteArray, now: Long): V1Message? {
        expire(now)
        val decoded = try {
            V1Protocol.decodeFrame(frame)
        } catch (_: ProtocolException) {
            reset()
            return null
        }
        if (decoded.session != session) {
            reset()
            return null
        }
        if (decoded.index == 0) {
            reset()
            first = decoded
            startedAt = now
        }
        val head = first ?: return null
        if (decoded.index != next || decoded.type != head.type || decoded.sequence != head.sequence ||
            decoded.session != head.session || decoded.count != head.count
        ) {
            reset()
            return null
        }
        bytes += decoded.payload
        next++
        if (next != head.count) return null
        val message = V1Message(head.type, head.session, head.sequence, bytes)
        reset()
        return message
    }
}

/** A response may arrive before the final ATT callback; completion requires both. */
class V1Exchange(val request: V1Message) {
    val frames = V1Protocol.frames(request)
    var writtenFrames = 0
        private set
    var response: V1Message? = null
        private set
    val writesComplete: Boolean get() = writtenFrames == frames.size
    val complete: Boolean get() = writesComplete && response != null

    fun wroteFrame() {
        check(!writesComplete)
        writtenFrames++
    }

    fun accept(message: V1Message): Boolean {
        if (message.session != request.session || message.sequence != request.sequence) return false
        if (request.type == MessageType.GET_MENU && message.type == MessageType.MENU_STATE) {
            val entry = MenuProtocol.decodeEntry(message.payload)
            if (request.payload.size != 1 || entry.index != (request.payload[0].toInt() and 255)) return false
            if (response == null) response = message
            return true
        }
        val expected = if (request.type == MessageType.HELLO) MessageType.HELLO_ACK else MessageType.ACK
        if (message.type != expected) return false
        if (expected == MessageType.ACK) {
            val ack = V1Protocol.commandAck(message.payload)
            if (ack.request != request.type) return false
            if (request.type == MessageType.GET_MENU && ack.status == AckStatus.OK) {
                throw ProtocolException("GET_MENU requires a menu entry")
            }
        }
        if (expected == MessageType.HELLO_ACK) V1Protocol.helloAck(message.payload)
        if (response == null) response = message
        return true
    }
}
