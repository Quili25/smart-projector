package mx.quili.smartprojector.sender.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Encodes the mirrored screen to H.264.
 *
 * The encoder's input is a `Surface` that a `VirtualDisplay` draws into, so frames never
 * touch the CPU: capture, scale and encode all happen on the GPU and video hardware.
 */
class ScreenEncoder(
    val width: Int,
    val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
) : Closeable {

    /** Receives encoder output on the draining thread. */
    interface Sink {
        /** SPS/PPS, delivered once before any frame. */
        fun onCodecConfig(codecSpecificData: ByteArray)

        /** One access unit. The buffer is only valid for the duration of the call. */
        fun onFrame(payload: ByteBuffer, presentationTimeUs: Long, isKeyFrame: Boolean)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)

    private val bufferInfo = MediaCodec.BufferInfo()

    /** The surface a VirtualDisplay should render into. Valid until [close]. */
    val inputSurface: Surface

    @Volatile
    private var draining = false

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)

            // A still screen produces no frames at all, which looks identical to a
            // frozen connection and leaves a late joiner with nothing to decode.
            // Repeating the last frame keeps the pipeline warm for a few kbit/s.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_AFTER_US)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw e
        }
    }

    fun start() {
        codec.start()
        draining = true
    }

    /**
     * Pumps encoder output into [sink] until [stop] is called or the stream ends.
     *
     * Blocking: call from a dedicated thread.
     */
    fun drain(sink: Sink) {
        while (draining) {
            val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)

            when {
                index >= 0 -> {
                    emit(index, sink)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // On most devices csd-0/csd-1 arrive here rather than as a
                    // CODEC_CONFIG buffer, so both paths must feed the sink.
                    emitFormatConfig(sink)
                }

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                else -> Log.w(TAG, "Unexpected dequeue result $index")
            }
        }
    }

    private fun emit(index: Int, sink: Sink) {
        val buffer = codec.getOutputBuffer(index)
        if (buffer == null) {
            codec.releaseOutputBuffer(index, false)
            return
        }

        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)

        try {
            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (isConfig) {
                val csd = ByteArray(bufferInfo.size)
                buffer.get(csd)
                sink.onCodecConfig(csd)
            } else if (bufferInfo.size > 0) {
                val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                sink.onFrame(buffer, bufferInfo.presentationTimeUs, isKeyFrame)
            }
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun emitFormatConfig(sink: Sink) {
        val format = codec.outputFormat
        val sps = format.getByteBuffer("csd-0")
        val pps = format.getByteBuffer("csd-1")
        if (sps == null) return

        val spsBytes = ByteArray(sps.remaining()).also { sps.get(it) }
        val ppsBytes = pps?.let { ByteArray(it.remaining()).also { bytes -> it.get(bytes) } }

        sink.onCodecConfig(if (ppsBytes == null) spsBytes else spsBytes + ppsBytes)
    }

    /**
     * Asks the encoder for an immediate keyframe.
     *
     * Used when a receiver joins mid-stream: without an IDR it has nothing to start
     * decoding from and would show a black screen until the next scheduled one.
     */
    fun requestKeyFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH) return
        runCatching {
            codec.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        }
    }

    fun stop() {
        draining = false
    }

    override fun close() {
        draining = false
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEQUEUE_TIMEOUT_US = 100_000L

        /** One second: fast recovery after a dropped frame without wasting bandwidth. */
        private const val I_FRAME_INTERVAL_SECONDS = 1

        private const val REPEAT_FRAME_AFTER_US = 100_000L

        /**
         * Bitrate for a LAN link, where bandwidth is plentiful and quality matters more
         * than compression. Roughly 0.2 bits per pixel per frame, clamped to a range
         * that neither starves 480p nor floods the projector's Wi-Fi at 1080p.
         */
        fun recommendedBitRate(width: Int, height: Int, frameRate: Int): Int =
            ((width.toLong() * height * frameRate) / 5)
                .coerceIn(2_000_000L, 12_000_000L)
                .toInt()
    }
}
