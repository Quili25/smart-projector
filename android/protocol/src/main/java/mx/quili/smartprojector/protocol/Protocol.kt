package mx.quili.smartprojector.protocol

/**
 * Wire-level constants shared by the sender (phone) and the receiver (projector).
 *
 * The transport is a single TCP connection on the local network carrying both control
 * and media. TCP is the right trade-off here: on a LAN the added latency over UDP is
 * small compared to the encoder/decoder pipeline, and losing a slice of an H.264 frame
 * produces far worse artifacts than waiting for a retransmit.
 */
object Protocol {

    /** Identifies our traffic and rejects anything else that dials the port. */
    val MAGIC: ByteArray = "SMCASTv1".toByteArray(Charsets.US_ASCII)

    const val VERSION: Int = 1

    /** mDNS service type advertised by the receiver. */
    const val SERVICE_TYPE: String = "_smartcast._tcp"

    /** Default listening port. Chosen in the dynamic range to avoid well-known clashes. */
    const val DEFAULT_PORT: Int = 47820

    /**
     * Upper bound for a single payload. A 1080p keyframe is well under 1 MB; the limit
     * exists so a malicious or corrupt peer cannot make us allocate arbitrary memory.
     */
    const val MAX_PAYLOAD_BYTES: Int = 4 * 1024 * 1024

    /** Upper bound for length-prefixed strings in the handshake. */
    const val MAX_STRING_BYTES: Int = 512

    /** Fixed frame header size: type, flags, reserved, pts, length. */
    const val HEADER_BYTES: Int = 16

    /** Idle time after which a peer is considered gone. */
    const val HEARTBEAT_INTERVAL_MS: Long = 2_000
    const val CONNECTION_TIMEOUT_MS: Int = 8_000

    /** Handshake must complete quickly; a stalled peer must not hold the single session slot. */
    const val HANDSHAKE_TIMEOUT_MS: Int = 10_000
}

/** Packet kinds carried after the handshake completes. */
enum class PacketType(val code: Int) {
    /** Codec-specific data (H.264 SPS/PPS). Must precede the first video frame. */
    VIDEO_CONFIG(0x01),

    /** One encoded H.264 access unit. */
    VIDEO_FRAME(0x02),

    /** AAC-LC stream shape and AudioSpecificConfig, see [AudioConfig]. Precedes audio frames. */
    AUDIO_CONFIG(0x03),

    /** One raw AAC access unit (no ADTS header). */
    AUDIO_FRAME(0x04),

    PING(0x10),
    PONG(0x11),

    /** Graceful shutdown from either side. */
    BYE(0x20);

    companion object {
        private val BY_CODE: Map<Int, PacketType> = entries.associateBy { it.code }

        fun fromCode(code: Int): PacketType? = BY_CODE[code]
    }
}

/** Bit flags carried in the frame header. */
object PacketFlags {
    const val NONE: Int = 0

    /** Payload is an H.264 IDR frame; a decoder may start here. */
    const val KEYFRAME: Int = 1 shl 0
}

/** Result of the receiver's handshake decision. */
enum class HandshakeStatus(val code: Int) {
    ACCEPTED(0),

    /** PIN missing or wrong. */
    REJECTED_PIN(1),

    /** Another sender already holds the session. */
    REJECTED_BUSY(2),

    /** Protocol version mismatch. */
    REJECTED_VERSION(3);

    companion object {
        private val BY_CODE: Map<Int, HandshakeStatus> = entries.associateBy { it.code }

        fun fromCode(code: Int): HandshakeStatus? = BY_CODE[code]
    }
}

/** Raised when a peer sends something that violates the wire format. */
class ProtocolException(message: String) : java.io.IOException(message)
