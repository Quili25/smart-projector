package mx.quili.smartprojector.receiver.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
     * @return false when no input buffer became available in time; the caller should
     *         drain output and retry.
     */
    fun submit(
        presentationTimeUs: Long,
        isKeyframe: Boolean,
        length: Int,
        fill: (ByteBuffer) -> Unit,
    ): Boolean {
        check(running) { "Decoder is closed" }

        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
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

    /** Releases every ready frame to the surface. Cheap to call after each submit. */
    fun render() {
        if (!running) return

        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                index >= 0 -> {
                    val hasContent = bufferInfo.size > 0
                    codec.releaseOutputBuffer(index, hasContent)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Output format is now ${codec.outputFormat}")
                }

                else -> return
            }
        }
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

        /**
         * Short waits keep the session thread responsive: a blocked dequeue would also
         * stall the socket reader and let the sender's buffers pile up.
         */
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 0L

        /** MediaFormat.KEY_PRIORITY: 0 means realtime. */
        const val PRIORITY_REALTIME = 0
    }
}
