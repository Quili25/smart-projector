package mx.quili.smartprojector.receiver.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import mx.quili.smartprojector.protocol.Protocol
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Hardware H.264 decoder rendering straight onto the projector's surface.
 *
 * Uses the synchronous MediaCodec API on the session thread. The asynchronous callback
 * API would buy nothing here: there is exactly one stream, and the thread that reads the
 * socket is the natural place to feed the codec. Decoded frames are released to the
 * surface as soon as they come out rather than being scheduled against their timestamp,
 * because for mirroring the freshest frame matters more than even pacing.
 */
class VideoDecoder(
    surface: Surface,
    width: Int,
    height: Int,
    codecSpecificData: ByteArray,
) : Closeable {

    private val codec: MediaCodec = MediaCodec.createDecoderByType(MIME_TYPE)

    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var running = false

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            // SPS/PPS. Supplying them up front lets the codec start on the first frame
            // instead of waiting to parse them out of the stream.
            setByteBuffer("csd-0", ByteBuffer.wrap(codecSpecificData))

            // Sized for a keyframe at this resolution. Some vendor decoders default to
            // input buffers smaller than an IDR, and an oversized frame ends the session.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize(width, height))

            // ACodec treats an unsupported low-latency request as a fatal configure
            // error rather than ignoring it; the Allwinner decoder in the H713 is one
            // that refuses. Only ask when the codec advertises the feature.
            if (supportsLowLatency(codec)) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_REALTIME)
            }
        }

        try {
            codec.configure(format, surface, null, 0)
            codec.start()
            running = true
        } catch (e: Exception) {
            // configure/start can throw for an unsupported profile or a dead surface;
            // never leak the codec in that case.
            runCatching { codec.release() }
            throw e
        }
    }

    /**
     * Feeds one access unit.
     *
     * [fill] receives the codec's input buffer and must write exactly [length] bytes,
     * which lets the caller copy straight from the socket with no intermediate array.
     *
     * @return false when no input buffer became available even after draining output;
     *         the caller must discard the payload.
     */
    fun submit(
        presentationTimeUs: Long,
        isKeyframe: Boolean,
        length: Int,
        fill: (ByteBuffer) -> Unit,
    ): Boolean {
        check(running) { "Decoder is closed" }

        val index = dequeueInput()
        if (index < 0) return false

        val buffer = requireNotNull(codec.getInputBuffer(index)) {
            "Codec returned a null input buffer for index $index"
        }
        buffer.clear()

        if (buffer.remaining() < length) {
            // Returning the buffer keeps the codec's accounting intact before we bail.
            codec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0)
            throw IllegalStateException("Input buffer holds ${buffer.remaining()}, frame is $length")
        }

        fill(buffer)

        val flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        codec.queueInputBuffer(index, 0, length, presentationTimeUs, flags)
        return true
    }

    /**
     * Input runs out when decoded frames are still waiting to be released to the surface,
     * so one drain-and-retry recovers most stalls. Dropping instead costs far more: a lost
     * P-frame corrupts every picture until the next keyframe, up to a second later.
     */
    private fun dequeueInput(): Int {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index >= 0) return index
        render()
        return codec.dequeueInputBuffer(RETRY_INPUT_TIMEOUT_US)
    }

    /**
     * Drains every ready frame and shows only the newest.
     *
     * Older frames are released without rendering: queueing them on the surface would
     * hold the codec's output buffers until the display consumed each one, and a decoder
     * with no free output buffers stops taking input. Cheap to call after each submit.
     */
    fun render() {
        if (!running) return

        var newest = NONE
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                index >= 0 -> {
                    if (newest != NONE) codec.releaseOutputBuffer(newest, false)
                    if (bufferInfo.size > 0) {
                        newest = index
                    } else {
                        codec.releaseOutputBuffer(index, false)
                        newest = NONE
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Output format is now ${codec.outputFormat}")
                }

                else -> break
            }
        }
        if (newest != NONE) codec.releaseOutputBuffer(newest, true)
    }

    override fun close() {
        if (!running) return
        running = false
        // stop() can throw if the codec already faulted; release() must still happen.
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val TAG = "VideoDecoder"
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC

        fun supportsLowLatency(codec: MediaCodec): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            return runCatching {
                codec.codecInfo.getCapabilitiesForType(MIME_TYPE)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
        }

        /** One byte per pixel, which comfortably exceeds an H.264 IDR at mirroring bitrates. */
        fun maxInputSize(width: Int, height: Int): Int =
            (width * height).coerceIn(MIN_INPUT_BYTES, Protocol.MAX_PAYLOAD_BYTES)

        const val MIN_INPUT_BYTES = 512 * 1024

        /**
         * Short waits keep the session thread responsive: a blocked dequeue would also
         * stall the socket reader and let the sender's buffers pile up.
         */
        const val INPUT_TIMEOUT_US = 10_000L
        const val RETRY_INPUT_TIMEOUT_US = 40_000L
        const val OUTPUT_TIMEOUT_US = 0L
        const val NONE = -1

        /** MediaFormat.KEY_PRIORITY: 0 means realtime. */
        const val PRIORITY_REALTIME = 0
    }
}
