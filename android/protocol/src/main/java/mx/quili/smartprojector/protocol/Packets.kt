package mx.quili.smartprojector.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Fixed 16-byte frame header.
 *
 * Layout (big endian):
 * ```
 * 0  u8   type
 * 1  u8   flags
 * 2  u16  reserved (must be 0)
 * 4  i64  presentationTimeUs
 * 12 u32  payloadLength
 * ```
 */
data class PacketHeader(
    val type: PacketType,
    val flags: Int,
    val presentationTimeUs: Long,
    val payloadLength: Int,
) {
    val isKeyframe: Boolean
        get() = (flags and PacketFlags.KEYFRAME) != 0
}

/**
 * Reads frames from a peer.
 *
 * Media payloads can be read straight into a decoder's input buffer via
 * [readPayloadInto], which keeps the hot path free of per-frame allocations. That
 * matters on this hardware: the projector has ~1 GB of RAM, and allocating a fresh
 * array for every frame at 30 fps would keep the collector busy for no reason.
 *
 * Not thread-safe; a connection should be read from a single thread.
 */
class PacketReader(source: InputStream) : Closeable {

    /**
     * Internal so the handshake helpers in `Handshake.kt` read through this same
     * buffered stream. Opening a second reader over the socket would strand any bytes
     * this one had already buffered.
     */
    internal val input = DataInputStream(BufferedInputStream(source, STREAM_BUFFER_BYTES))

    /** Reusable staging buffer for copying payloads into direct ByteBuffers. */
    private val scratch = ByteArray(SCRATCH_BYTES)

    /**
     * Reads the next header.
     *
     * @throws EOFException when the peer closed the connection cleanly.
     * @throws ProtocolException when the peer violated the wire format.
     */
    fun readHeader(): PacketHeader {
        val typeCode = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        val reserved = input.readUnsignedShort()
        val presentationTimeUs = input.readLong()
        val length = input.readInt()

        if (reserved != 0) {
            throw ProtocolException("Reserved header field must be zero, got $reserved")
        }
        val type = PacketType.fromCode(typeCode)
            ?: throw ProtocolException("Unknown packet type 0x${typeCode.toString(16)}")
        // A negative length means the peer sent a value above 2^31; both that and an
        // oversized length are refused before any allocation happens.
        if (length < 0 || length > Protocol.MAX_PAYLOAD_BYTES) {
            throw ProtocolException("Payload length $length outside [0, ${Protocol.MAX_PAYLOAD_BYTES}]")
        }

        return PacketHeader(type, flags, presentationTimeUs, length)
    }

    /** Reads a payload into a freshly allocated array. For small control payloads. */
    fun readPayload(length: Int): ByteArray {
        if (length == 0) return EMPTY
        val data = ByteArray(length)
        input.readFully(data)
        return data
    }

    /**
     * Reads exactly [length] bytes into [target] at its current position.
     *
     * @throws ProtocolException if the destination cannot hold the payload.
     */
    fun readPayloadInto(target: ByteBuffer, length: Int) {
        if (target.remaining() < length) {
            throw ProtocolException("Destination buffer holds ${target.remaining()}, need $length")
        }
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, scratch.size)
            input.readFully(scratch, 0, chunk)
            target.put(scratch, 0, chunk)
            remaining -= chunk
        }
    }

    /** Discards a payload we cannot handle, keeping the stream in sync. */
    fun skipPayload(length: Int) {
        var remaining = length.toLong()
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                // skip() can legitimately return 0; fall back to a read so we either
                // make progress or detect a closed stream instead of spinning.
                val chunk = minOf(remaining, scratch.size.toLong()).toInt()
                input.readFully(scratch, 0, chunk)
                remaining -= chunk
            } else {
                remaining -= skipped
            }
        }
    }

    override fun close() {
        input.close()
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val SCRATCH_BYTES = 32 * 1024
        val EMPTY = ByteArray(0)
    }
}

/**
 * Writes frames to a peer.
 *
 * Synchronized because the media thread and the heartbeat thread both write to the
 * same connection; interleaving two frames would corrupt the stream irrecoverably.
 */
class PacketWriter(sink: OutputStream) : Closeable {

    /** Internal for the same reason as [PacketReader.input]. */
    internal val output = DataOutputStream(BufferedOutputStream(sink, STREAM_BUFFER_BYTES))

    @Synchronized
    fun write(
        type: PacketType,
        flags: Int = PacketFlags.NONE,
        presentationTimeUs: Long = 0L,
        payload: ByteArray = EMPTY,
        offset: Int = 0,
        length: Int = payload.size,
    ) {
        require(offset >= 0 && length >= 0 && offset + length <= payload.size) {
            "Invalid payload range offset=$offset length=$length size=${payload.size}"
        }
        writeHeader(type, flags, presentationTimeUs, length)
        output.write(payload, offset, length)
        output.flush()
    }

    /** Writes an encoder output buffer without copying it into an intermediate array. */
    @Synchronized
    fun write(
        type: PacketType,
        flags: Int,
        presentationTimeUs: Long,
        payload: ByteBuffer,
    ) {
        val length = payload.remaining()
        writeHeader(type, flags, presentationTimeUs, length)

        if (payload.hasArray()) {
            output.write(payload.array(), payload.arrayOffset() + payload.position(), length)
            payload.position(payload.position() + length)
        } else {
            val chunk = ByteArray(minOf(length, SCRATCH_BYTES))
            var remaining = length
            while (remaining > 0) {
                val n = minOf(remaining, chunk.size)
                payload.get(chunk, 0, n)
                output.write(chunk, 0, n)
                remaining -= n
            }
        }
        output.flush()
    }

    private fun writeHeader(type: PacketType, flags: Int, presentationTimeUs: Long, length: Int) {
        require(length <= Protocol.MAX_PAYLOAD_BYTES) {
            "Payload of $length bytes exceeds ${Protocol.MAX_PAYLOAD_BYTES}"
        }
        output.writeByte(type.code)
        output.writeByte(flags)
        output.writeShort(0)
        output.writeLong(presentationTimeUs)
        output.writeInt(length)
    }

    override fun close() {
        output.close()
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val SCRATCH_BYTES = 32 * 1024
        val EMPTY = ByteArray(0)
    }
}
