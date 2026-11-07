package mx.quili.smartprojector.receiver.net

import android.util.Log
import mx.quili.smartprojector.protocol.AudioConfig
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
import mx.quili.smartprojector.receiver.media.AudioPlayer
import mx.quili.smartprojector.receiver.media.LatencyGuard
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
    private var decoderConfig: VideoConfig? = null

    private val latency = LatencyGuard { System.nanoTime() / 1_000 }
    private var framesSkipped = 0L

    private var audio: AudioPlayer? = null
    private var audioConfig: AudioConfig? = null

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
            decoderConfig = null
            stopAudio()
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
                    // Frames before the config packet cannot be decoded, and frames the
                    // latency guard rejects would only add delay. Skipping still reads
                    // them off the socket so the stream stays aligned.
                    if (active != null && admit(header.presentationTimeUs, header.isKeyframe)) {
                        feed(active, reader, header.presentationTimeUs, header.isKeyframe, header.payloadLength)
                    } else {
                        reader.skipPayload(header.payloadLength)
                    }
                }

                PacketType.PING -> {
                    reader.skipPayload(header.payloadLength)
                    writer.write(PacketType.PONG)
                }

                PacketType.AUDIO_CONFIG -> {
                    startAudio(AudioConfig.decode(reader.readPayload(header.payloadLength)))
                }

                PacketType.AUDIO_FRAME -> playAudio(reader, header.presentationTimeUs, header.payloadLength)

                PacketType.BYE -> {
                    Log.i(TAG, "Sender said goodbye")
                    return
                }

                PacketType.PONG -> reader.skipPayload(header.payloadLength)
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
            // The payload has to leave the socket either way or the stream desynchronizes.
            reader.skipPayload(length)
            latency.onFrameLost()
            Log.w(TAG, "Decoder input full; waiting for the next keyframe")
            return
        }

        decoder.render()
    }

    /** Consults the latency guard and logs only when skipping starts or stops. */
    private fun admit(presentationTimeUs: Long, isKeyframe: Boolean): Boolean {
        val wasSkipping = latency.isSkipping
        val decode = latency.shouldDecode(presentationTimeUs, isKeyframe)

        if (!decode) {
            framesSkipped++
        } else if (wasSkipping && framesSkipped > 0) {
            Log.i(TAG, "Resumed on a keyframe after skipping $framesSkipped frames")
            framesSkipped = 0
        }
        return decode
    }

    /**
     * Replaces the decoder whenever the sender announces a new format.
     *
     * Encoders commonly report the same SPS/PPS twice at startup (as a format change and
     * as a codec-config buffer). Rebuilding for a repeat throws away the frames already
     * queued and stalls the stream while the hardware decoder reinitializes.
     */
    private fun startDecoder(config: VideoConfig, senderName: String) {
        if (decoder != null && config == decoderConfig) return

        decoder?.close()
        decoder = null
        decoderConfig = null

        val surface = MirrorSurface.await(SURFACE_TIMEOUT_MS)
        if (surface == null) {
            throw IOException("No rendering surface became available")
        }

        decoder = VideoDecoder(surface, config.width, config.height, config.codecSpecificData)
        decoderConfig = config
        latency.reset()
        framesSkipped = 0
        ReceiverStatus.publish(ReceiverState.Mirroring(senderName, config.width, config.height))
        Log.i(TAG, "Decoding ${config.width}x${config.height} from '$senderName'")
    }

    /** Audio is best effort: if it cannot start, the session carries on video-only. */
    private fun startAudio(config: AudioConfig) {
        if (audio != null && config == audioConfig) return
        stopAudio()

        audio = try {
            AudioPlayer(config)
        } catch (e: Exception) {
            Log.w(TAG, "Audio unavailable ($config); continuing without it", e)
            null
        }
        audioConfig = config.takeIf { audio != null }
        if (audio != null) Log.i(TAG, "Playing audio $config")
    }

    private fun playAudio(reader: PacketReader, presentationTimeUs: Long, length: Int) {
        val player = audio
        val consumed = player != null &&
            player.submit(presentationTimeUs, length) { buffer -> reader.readPayloadInto(buffer, length) }
        // Frames before the config, or ones the decoder had no room for, still have to
        // leave the socket so the stream stays aligned.
        if (!consumed) reader.skipPayload(length)
        if (player?.failed == true) stopAudio()
    }

    private fun stopAudio() {
        audio?.close()
        audio = null
        audioConfig = null
    }

    private companion object {
        const val TAG = "SenderSession"

        /** Generous: the activity may still be starting when the sender connects. */
        const val SURFACE_TIMEOUT_MS = 15_000L
    }
}
