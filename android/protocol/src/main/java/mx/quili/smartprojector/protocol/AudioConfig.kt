package mx.quili.smartprojector.protocol

/**
 * Payload of a [PacketType.AUDIO_CONFIG] packet: the PCM shape of an AAC-LC stream and
 * its AudioSpecificConfig.
 *
 * The decoder could recover rate and channels from the AudioSpecificConfig alone, but
 * carrying them explicitly lets the receiver validate the stream before touching a codec
 * and size its output without parsing ASC bit fields.
 *
 * Layout (big endian): `u32 sampleRate | u8 channelCount | AudioSpecificConfig bytes`.
 */
class AudioConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val codecSpecificData: ByteArray,
) {

    init {
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "Invalid sample rate $sampleRate" }
        require(channelCount in 1..MAX_CHANNELS) { "Invalid channel count $channelCount" }
        require(codecSpecificData.size in 1..MAX_CSD_BYTES) {
            "Codec-specific data must be 1..$MAX_CSD_BYTES bytes, got ${codecSpecificData.size}"
        }
    }

    fun encode(): ByteArray {
        val out = ByteArray(HEADER_BYTES + codecSpecificData.size)
        out[0] = (sampleRate ushr 24).toByte()
        out[1] = (sampleRate ushr 16).toByte()
        out[2] = (sampleRate ushr 8).toByte()
        out[3] = sampleRate.toByte()
        out[4] = channelCount.toByte()
        codecSpecificData.copyInto(out, HEADER_BYTES)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioConfig) return false
        return sampleRate == other.sampleRate &&
            channelCount == other.channelCount &&
            codecSpecificData.contentEquals(other.codecSpecificData)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channelCount
        result = 31 * result + codecSpecificData.contentHashCode()
        return result
    }

    override fun toString(): String =
        "AudioConfig(${sampleRate}Hz, ${channelCount}ch, csd=${codecSpecificData.size} bytes)"

    companion object {
        private const val HEADER_BYTES = 5

        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 96_000

        /** Stereo is all the projector's speaker and any HDMI sink here will reproduce. */
        const val MAX_CHANNELS = 2

        /** An AAC-LC AudioSpecificConfig is 2 bytes; extensions stay well under this. */
        const val MAX_CSD_BYTES = 64

        fun decode(payload: ByteArray): AudioConfig {
            if (payload.size <= HEADER_BYTES) {
                throw ProtocolException("AUDIO_CONFIG payload is ${payload.size} bytes, too short")
            }
            val sampleRate = ((payload[0].toInt() and 0xFF) shl 24) or
                ((payload[1].toInt() and 0xFF) shl 16) or
                ((payload[2].toInt() and 0xFF) shl 8) or
                (payload[3].toInt() and 0xFF)
            val channelCount = payload[4].toInt() and 0xFF
            val csd = payload.copyOfRange(HEADER_BYTES, payload.size)

            // Same bounds as the constructor, but garbage from a peer is an expected
            // condition here, not a programming error.
            if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
                throw ProtocolException("AUDIO_CONFIG sample rate $sampleRate out of range")
            }
            if (channelCount !in 1..MAX_CHANNELS) {
                throw ProtocolException("AUDIO_CONFIG channel count $channelCount out of range")
            }
            if (csd.size > MAX_CSD_BYTES) {
                throw ProtocolException("AUDIO_CONFIG codec data is ${csd.size} bytes, too long")
            }
            return AudioConfig(sampleRate, channelCount, csd)
        }
    }
}
