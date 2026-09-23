package mx.quili.smartprojector.receiver.net

import android.util.Log
import mx.quili.smartprojector.protocol.HandshakeStatus
import mx.quili.smartprojector.protocol.PacketReader
import mx.quili.smartprojector.protocol.PacketType
import mx.quili.smartprojector.protocol.PacketWriter
import mx.quili.smartprojector.protocol.Protocol
import mx.quili.smartprojector.protocol.ProtocolException
import mx.quili.smartprojector.protocol.VideoConfig
import mx.quili.smartprojector.protocol.Welcome
import mx.quili.smartprojector.protocol.readHello
import mx.quili.smartprojector.protocol.writeWelcome
import mx.quili.smartprojector.receiver.core.PairingStore
import mx.quili.smartprojector.receiver.core.ReceiverState
import mx.quili.smartprojector.receiver.core.ReceiverStatus
import mx.quili.smartprojector.receiver.media.MirrorSurface
import mx.quili.smartprojector.receiver.media.VideoDecoder
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/** Display geometry advertised to senders so they encode at the panel's native size. */
data class DisplayGeometry(val width: Int, val height: Int, val maxFps: Int)

/**
 * Serves exactly one connected sender, start to finish, on the calling thread.
 *
 * Everything is torn down before [run] returns, including on failure, so the server loop
 * can accept the next sender without leaking a codec or a socket.
 */
class SenderSession(
    private val socket: Socket,
    private val pairing: PairingStore,
    private val receiverName: String,
    private val geometry: DisplayGeometry,
) {

    private var decoder: VideoDecoder? = null

    fun run() {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = Protocol.HANDSHAKE_TIMEOUT_MS

            val reader = PacketReader(socket.getInputStream())
            val writer = PacketWriter(socket.getOutputStream())

            val senderName = negotiate(reader, writer) ?: return

            socket.soTimeout = Protocol.CONNECTION_TIMEOUT_MS
            pump(reader, writer, senderName)
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Sender stopped responding", e)
        } catch (e: EOFException) {
            Log.i(TAG, "Sender disconnected")
        } catch (e: ProtocolException) {
            // Expected whenever something that is not our sender dials the port.
            Log.w(TAG, "Rejected malformed peer: ${e.message}")
        } catch (e: IOException) {
            Log.w(TAG, "Connection failed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Session failed", e)
            ReceiverStatus.publish(ReceiverState.Failed(e.message ?: "Error desconocido"))
        } finally {
            decoder?.close()
            decoder = null
            runCatching { socket.close() }
        }
    }

    /**
     * Runs the handshake.
     *
     * @return the sender's display name, or null when the connection was refused.
     */
    private fun negotiate(reader: PacketReader, writer: PacketWriter): String? {
        val hello = reader.readHello()

        if (hello.protocolVersion != Protocol.VERSION) {
            Log.w(TAG, "Version mismatch: sender speaks ${hello.protocolVersion}")
            writer.writeWelcome(rejection(HandshakeStatus.REJECTED_VERSION))
            return null
        }

        // Never log the code or the supplied guess.
        if (!pairing.authorize(hello.deviceId, hello.pin)) {
            Log.w(TAG, "Pairing refused for '${hello.deviceName}'")
            writer.writeWelcome(rejection(HandshakeStatus.REJECTED_PIN))
            return null
        }

        writer.writeWelcome(
            Welcome(
                status = HandshakeStatus.ACCEPTED,
                receiverName = receiverName,
                width = geometry.width,
                height = geometry.height,
                maxFps = geometry.maxFps,
            ),
        )

        val senderName = hello.deviceName.ifBlank { "Dispositivo" }
        ReceiverStatus.publish(ReceiverState.Connecting(senderName))
        Log.i(TAG, "Accepted '$senderName'")
        return senderName
    }

    private fun rejection(status: HandshakeStatus) = Welcome(
        status = status,
        receiverName = receiverName,
        width = 0,
        height = 0,
        maxFps = 0,
    )

    /** Reads frames until the sender leaves or the connection breaks. */
    private fun pump(reader: PacketReader, writer: PacketWriter, senderName: String) {
        while (!socket.isClosed) {
            val header = reader.readHeader()

            when (header.type) {
                PacketType.VIDEO_CONFIG -> {
                    val config = VideoConfig.decode(reader.readPayload(header.payloadLength))
                    startDecoder(config, senderName)
                }

                PacketType.VIDEO_FRAME -> {
                    val active = decoder
                    if (active == null) {
                        // Frames before the config packet cannot be decoded; dropping
                        // them keeps the stream aligned until the next config arrives.
                        reader.skipPayload(header.payloadLength)
                    } else {
                        feed(active, reader, header.presentationTimeUs, header.isKeyframe, header.payloadLength)
                    }
                }

                PacketType.PING -> {
                    reader.skipPayload(header.payloadLength)
                    writer.write(PacketType.PONG)
                }

                PacketType.BYE -> {
                    Log.i(TAG, "Sender said goodbye")
                    return
                }

                // Audio is not rendered yet; skipping keeps us interoperable with a
                // newer sender instead of killing the session.
                PacketType.AUDIO_CONFIG,
                PacketType.AUDIO_FRAME,
                PacketType.PONG,
                -> reader.skipPayload(header.payloadLength)
            }
        }
    }

    private fun feed(
        decoder: VideoDecoder,
        reader: PacketReader,
        presentationTimeUs: Long,
        isKeyframe: Boolean,
        length: Int,
    ) {
        val accepted = decoder.submit(presentationTimeUs, isKeyframe, length) { buffer ->
            reader.readPayloadInto(buffer, length)
        }

        if (!accepted) {
            // No input buffer was free. Draining may release one, but the payload has
            // to leave the socket either way or the stream desynchronizes.
            decoder.render()
            reader.skipPayload(length)
            Log.w(TAG, "Dropped a frame: decoder input was full")
            return
        }

        decoder.render()
    }

    /** Replaces the decoder whenever the sender announces a new format. */
    private fun startDecoder(config: VideoConfig, senderName: String) {
        decoder?.close()
        decoder = null

        val surface = MirrorSurface.await(SURFACE_TIMEOUT_MS)
        if (surface == null) {
            throw IOException("No rendering surface became available")
        }

        decoder = VideoDecoder(surface, config.width, config.height, config.codecSpecificData)
        ReceiverStatus.publish(ReceiverState.Mirroring(senderName, config.width, config.height))
        Log.i(TAG, "Decoding ${config.width}x${config.height} from '$senderName'")
    }

    private companion object {
        const val TAG = "SenderSession"

        /** Generous: the activity may still be starting when the sender connects. */
        const val SURFACE_TIMEOUT_MS = 15_000L
    }
}
