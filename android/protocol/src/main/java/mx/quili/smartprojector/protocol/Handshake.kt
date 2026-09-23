package mx.quili.smartprojector.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * First message: the sender identifies itself and proves it is allowed to cast.
 *
 * @param deviceId stable per-installation identifier used to remember a pairing.
 * @param pin the code shown on the projector, or empty when already paired.
 */
data class Hello(
    val protocolVersion: Int,
    val deviceId: String,
    val deviceName: String,
    val pin: String,
)

/** The receiver's answer, including the display geometry the sender should encode for. */
data class Welcome(
    val status: HandshakeStatus,
    val receiverName: String,
    val width: Int,
    val height: Int,
    val maxFps: Int,
)

/**
 * Sends [hello].
 *
 * Callers must complete the handshake before starting the media threads, so this is
 * intentionally not synchronized against the media writer.
 */
fun PacketWriter.writeHello(hello: Hello) {
    output.write(Protocol.MAGIC)
    output.writeShort(hello.protocolVersion)
    output.writeLengthPrefixed(hello.deviceId)
    output.writeLengthPrefixed(hello.deviceName)
    output.writeLengthPrefixed(hello.pin)
    output.flush()
}

fun PacketReader.readHello(): Hello {
    input.expectMagic()
    val version = input.readUnsignedShort()
    val deviceId = input.readLengthPrefixed()
    val deviceName = input.readLengthPrefixed()
    val pin = input.readLengthPrefixed()
    return Hello(version, deviceId, deviceName, pin)
}

fun PacketWriter.writeWelcome(welcome: Welcome) {
    output.write(Protocol.MAGIC)
    output.writeByte(welcome.status.code)
    output.writeShort(welcome.width)
    output.writeShort(welcome.height)
    output.writeByte(welcome.maxFps)
    output.writeLengthPrefixed(welcome.receiverName)
    output.flush()
}

fun PacketReader.readWelcome(): Welcome {
    input.expectMagic()
    val statusCode = input.readUnsignedByte()
    val width = input.readUnsignedShort()
    val height = input.readUnsignedShort()
    val maxFps = input.readUnsignedByte()
    val receiverName = input.readLengthPrefixed()

    val status = HandshakeStatus.fromCode(statusCode)
        ?: throw ProtocolException("Unknown handshake status $statusCode")
    return Welcome(status, receiverName, width, height, maxFps)
}

/**
 * Verifies the stream starts with our magic.
 *
 * Port scanners and unrelated clients do reach this port on a home network; failing
 * here keeps them from being mistaken for a malformed sender.
 */
private fun DataInputStream.expectMagic() {
    val actual = ByteArray(Protocol.MAGIC.size)
    readFully(actual)
    if (!actual.contentEquals(Protocol.MAGIC)) {
        throw ProtocolException("Bad magic; not a SmartCast peer")
    }
}

private fun DataOutputStream.writeLengthPrefixed(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= Protocol.MAX_STRING_BYTES) {
        "String of ${bytes.size} bytes exceeds ${Protocol.MAX_STRING_BYTES}"
    }
    writeShort(bytes.size)
    write(bytes)
}

private fun DataInputStream.readLengthPrefixed(): String {
    val length = readUnsignedShort()
    if (length > Protocol.MAX_STRING_BYTES) {
        throw ProtocolException("String of $length bytes exceeds ${Protocol.MAX_STRING_BYTES}")
    }
    val bytes = ByteArray(length)
    readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}
