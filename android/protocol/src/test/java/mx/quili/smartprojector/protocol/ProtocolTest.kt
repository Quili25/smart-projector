package mx.quili.smartprojector.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer

class PacketRoundTripTest {

    @Test
    fun `video frame survives a round trip`() {
        val payload = ByteArray(1024) { (it % 251).toByte() }

        val sink = ByteArrayOutputStream()
        PacketWriter(sink).write(
            type = PacketType.VIDEO_FRAME,
            flags = PacketFlags.KEYFRAME,
            presentationTimeUs = 1_234_567L,
            payload = payload,
        )

        val reader = PacketReader(ByteArrayInputStream(sink.toByteArray()))
        val header = reader.readHeader()

        assertEquals(PacketType.VIDEO_FRAME, header.type)
        assertTrue(header.isKeyframe)
        assertEquals(1_234_567L, header.presentationTimeUs)
        assertEquals(payload.size, header.payloadLength)
        assertArrayEquals(payload, reader.readPayload(header.payloadLength))
    }

    @Test
    fun `empty payload is valid`() {
        val sink = ByteArrayOutputStream()
        PacketWriter(sink).write(type = PacketType.PING)

        val reader = PacketReader(ByteArrayInputStream(sink.toByteArray()))
        val header = reader.readHeader()

        assertEquals(PacketType.PING, header.type)
        assertEquals(0, header.payloadLength)
        assertFalse(header.isKeyframe)
    }

    @Test
    fun `payload reads into a direct buffer`() {
        val payload = ByteArray(70_000) { (it % 97).toByte() }

        val sink = ByteArrayOutputStream()
        PacketWriter(sink).write(
            type = PacketType.VIDEO_FRAME,
            presentationTimeUs = 0L,
            payload = payload,
        )

        val reader = PacketReader(ByteArrayInputStream(sink.toByteArray()))
        val header = reader.readHeader()
        // Direct buffers have no backing array, which is the case that exercises the
        // chunked staging path on both ends.
        val target = ByteBuffer.allocateDirect(header.payloadLength)
        reader.readPayloadInto(target, header.payloadLength)
        target.flip()

        val actual = ByteArray(header.payloadLength)
        target.get(actual)
        assertArrayEquals(payload, actual)
    }

    @Test
    fun `writing from a direct buffer produces identical bytes`() {
        val payload = ByteArray(5_000) { (it % 31).toByte() }
        val source = ByteBuffer.allocateDirect(payload.size).apply {
            put(payload)
            flip()
        }

        val sink = ByteArrayOutputStream()
        PacketWriter(sink).write(PacketType.VIDEO_FRAME, PacketFlags.NONE, 42L, source)

        val reader = PacketReader(ByteArrayInputStream(sink.toByteArray()))
        val header = reader.readHeader()
        assertEquals(42L, header.presentationTimeUs)
        assertArrayEquals(payload, reader.readPayload(header.payloadLength))
    }

    @Test
    fun `skip keeps the stream aligned`() {
        val sink = ByteArrayOutputStream()
        val writer = PacketWriter(sink)
        writer.write(PacketType.AUDIO_FRAME, payload = ByteArray(40_000) { 7 })
        writer.write(PacketType.PING)

        val reader = PacketReader(ByteArrayInputStream(sink.toByteArray()))
        val first = reader.readHeader()
        reader.skipPayload(first.payloadLength)

        assertEquals(PacketType.PING, reader.readHeader().type)
    }
}

class MalformedInputTest {

    /** Builds a raw header so we can express what a hostile peer would send. */
    private fun header(type: Int, flags: Int, reserved: Int, pts: Long, length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        java.io.DataOutputStream(out).apply {
            writeByte(type)
            writeByte(flags)
            writeShort(reserved)
            writeLong(pts)
            writeInt(length)
            flush()
        }
        return out.toByteArray()
    }

    @Test
    fun `unknown packet type is rejected`() {
        val reader = PacketReader(ByteArrayInputStream(header(0x7F, 0, 0, 0, 0)))
        assertThrows(ProtocolException::class.java) { reader.readHeader() }
    }

    @Test
    fun `non-zero reserved field is rejected`() {
        val reader = PacketReader(ByteArrayInputStream(header(PacketType.PING.code, 0, 1, 0, 0)))
        assertThrows(ProtocolException::class.java) { reader.readHeader() }
    }

    @Test
    fun `oversized payload is refused before allocating`() {
        val bytes = header(PacketType.VIDEO_FRAME.code, 0, 0, 0, Protocol.MAX_PAYLOAD_BYTES + 1)
        val reader = PacketReader(ByteArrayInputStream(bytes))
        assertThrows(ProtocolException::class.java) { reader.readHeader() }
    }

    @Test
    fun `negative payload length is refused`() {
        val reader = PacketReader(ByteArrayInputStream(header(PacketType.VIDEO_FRAME.code, 0, 0, 0, -1)))
        assertThrows(ProtocolException::class.java) { reader.readHeader() }
    }

    @Test
    fun `truncated stream reports end of file`() {
        val reader = PacketReader(ByteArrayInputStream(ByteArray(4)))
        assertThrows(EOFException::class.java) { reader.readHeader() }
    }

    @Test
    fun `reading into an undersized buffer is refused`() {
        val sink = ByteArrayOutputStream()
        PacketWriter(sink).write(PacketType.VIDEO_FRAME, payload = ByteArray(100))

        val reader = PacketReader(ByteArrayInputStream(sink.toByteArray()))
        val header = reader.readHeader()
        assertThrows(ProtocolException::class.java) {
            reader.readPayloadInto(ByteBuffer.allocate(10), header.payloadLength)
        }
    }
}

class HandshakeTest {

    @Test
    fun `hello survives a round trip`() {
        val hello = Hello(Protocol.VERSION, "device-abc", "Pixel de Omar", "123456")

        val sink = ByteArrayOutputStream()
        PacketWriter(sink).writeHello(hello)

        assertEquals(hello, PacketReader(ByteArrayInputStream(sink.toByteArray())).readHello())
    }

    @Test
    fun `welcome survives a round trip`() {
        val welcome = Welcome(HandshakeStatus.ACCEPTED, "Proyector", 1280, 720, 30)

        val sink = ByteArrayOutputStream()
        PacketWriter(sink).writeWelcome(welcome)

        assertEquals(welcome, PacketReader(ByteArrayInputStream(sink.toByteArray())).readWelcome())
    }

    @Test
    fun `non-ascii device names survive a round trip`() {
        val hello = Hello(Protocol.VERSION, "id", "Televisión Español ñ", "")

        val sink = ByteArrayOutputStream()
        PacketWriter(sink).writeHello(hello)

        assertEquals(hello, PacketReader(ByteArrayInputStream(sink.toByteArray())).readHello())
    }

    @Test
    fun `stream without our magic is rejected`() {
        val garbage = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        val reader = PacketReader(ByteArrayInputStream(garbage))
        assertThrows(ProtocolException::class.java) { reader.readHello() }
    }

    @Test
    fun `rejection status survives a round trip`() {
        val welcome = Welcome(HandshakeStatus.REJECTED_PIN, "Proyector", 0, 0, 0)

        val sink = ByteArrayOutputStream()
        PacketWriter(sink).writeWelcome(welcome)

        val decoded = PacketReader(ByteArrayInputStream(sink.toByteArray())).readWelcome()
        assertEquals(HandshakeStatus.REJECTED_PIN, decoded.status)
    }

    @Test
    fun `over-long string is refused at write time`() {
        val hello = Hello(Protocol.VERSION, "id", "x".repeat(Protocol.MAX_STRING_BYTES + 1), "")
        assertThrows(IllegalArgumentException::class.java) {
            PacketWriter(ByteArrayOutputStream()).writeHello(hello)
        }
    }
}

class PairingTest {

    @Test
    fun `generated pin is well formed`() {
        repeat(200) {
            val pin = Pairing.generatePin()
            assertEquals(Pairing.PIN_LENGTH, pin.length)
            assertTrue("unexpected pin: $pin", Pairing.isWellFormed(pin))
        }
    }

    @Test
    fun `generated pins are not constant`() {
        val seen = (1..100).map { Pairing.generatePin() }.toSet()
        assertTrue("generator produced $seen", seen.size > 1)
    }

    @Test
    fun `matching pin is accepted`() {
        assertTrue(Pairing.matches("123456", "123456"))
    }

    @Test
    fun `wrong pin is rejected`() {
        assertFalse(Pairing.matches("123456", "123457"))
        assertFalse(Pairing.matches("123456", "654321"))
    }

    @Test
    fun `length mismatch is rejected`() {
        assertFalse(Pairing.matches("123456", ""))
        assertFalse(Pairing.matches("123456", "12345"))
        assertFalse(Pairing.matches("123456", "1234567"))
    }

    @Test
    fun `malformed pins are detected`() {
        assertFalse(Pairing.isWellFormed(""))
        assertFalse(Pairing.isWellFormed("12345"))
        assertFalse(Pairing.isWellFormed("12345a"))
        assertTrue(Pairing.isWellFormed("000000"))
    }
}

class VideoConfigTest {

    @Test
    fun `config survives a round trip`() {
        // A realistic SPS/PPS pair: start code, SPS, start code, PPS.
        val csd = byteArrayOf(
            0, 0, 0, 1, 103, 66, 0, 41,
            0, 0, 0, 1, 104, 0xCE.toByte(), 6, 82,
        )
        val config = VideoConfig(1280, 720, csd)

        val decoded = VideoConfig.decode(config.encode())

        assertEquals(1280, decoded.width)
        assertEquals(720, decoded.height)
        assertArrayEquals(csd, decoded.codecSpecificData)
        assertEquals(config, decoded)
    }

    @Test
    fun `portrait geometry survives a round trip`() {
        val config = VideoConfig(720, 1600, byteArrayOf(1, 2, 3))
        val decoded = VideoConfig.decode(config.encode())

        assertEquals(720, decoded.width)
        assertEquals(1600, decoded.height)
    }

    @Test
    fun `payload without codec data is rejected`() {
        // 1280x720 geometry and nothing after it.
        assertThrows(ProtocolException::class.java) {
            VideoConfig.decode(byteArrayOf(5, 0, 2, 0xD0.toByte()))
        }
    }

    @Test
    fun `truncated payload is rejected`() {
        assertThrows(ProtocolException::class.java) {
            VideoConfig.decode(byteArrayOf(5, 0))
        }
    }

    @Test
    fun `zero geometry is rejected`() {
        // width = 0, height = 720, plus one byte of codec data
        assertThrows(ProtocolException::class.java) {
            VideoConfig.decode(byteArrayOf(0, 0, 2, 0xD0.toByte(), 9))
        }
    }

    @Test
    fun `empty codec data is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoConfig(1280, 720, ByteArray(0))
        }
    }
}
