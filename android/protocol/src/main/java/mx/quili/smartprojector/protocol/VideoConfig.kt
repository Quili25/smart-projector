package mx.quili.smartprojector.protocol

/**
 * Payload of a [PacketType.VIDEO_CONFIG] packet.
 *
 * Geometry travels with the codec-specific data because they always change together:
 * rotating the phone produces a new SPS *and* new dimensions, and splitting them across
 * two packets creates a window where the decoder is configured with a mismatched pair.
 *
 * Layout (big endian): `u16 width | u16 height | csd bytes`.
 */
class VideoConfig(
    val width: Int,
    val height: Int,
    val codecSpecificData: ByteArray,
) {

    init {
        require(width in 1..MAX_DIMENSION) { "Invalid width $width" }
        require(height in 1..MAX_DIMENSION) { "Invalid height $height" }
        require(codecSpecificData.isNotEmpty()) { "Codec-specific data must not be empty" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(HEADER_BYTES + codecSpecificData.size)
        out[0] = (width shr 8).toByte()
        out[1] = width.toByte()
        out[2] = (height shr 8).toByte()
        out[3] = height.toByte()
        codecSpecificData.copyInto(out, HEADER_BYTES)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoConfig) return false
        return width == other.width &&
            height == other.height &&
            codecSpecificData.contentEquals(other.codecSpecificData)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + codecSpecificData.contentHashCode()
        return result
    }

    override fun toString(): String =
        "VideoConfig(${width}x$height, csd=${codecSpecificData.size} bytes)"

    companion object {
        private const val HEADER_BYTES = 4

        /** Well above any realistic display, low enough to reject nonsense early. */
        private const val MAX_DIMENSION = 7680

        fun decode(payload: ByteArray): VideoConfig {
            if (payload.size <= HEADER_BYTES) {
                throw ProtocolException("VIDEO_CONFIG payload is ${payload.size} bytes, too short")
            }
            val width = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val height = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
            val csd = payload.copyOfRange(HEADER_BYTES, payload.size)

            // The constructor's requires express the same bounds, but a peer sending
            // garbage is an expected condition here, not a programming error.
            if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
                throw ProtocolException("VIDEO_CONFIG geometry ${width}x$height out of range")
            }
            return VideoConfig(width, height, csd)
        }
    }
}
