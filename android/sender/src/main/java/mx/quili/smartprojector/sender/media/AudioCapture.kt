package mx.quili.smartprojector.sender.media

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Captures what other apps are playing and encodes it to AAC-LC.
 *
 * Uses playback capture, so only audio the source app allows to be captured comes
 * through: media and games by default, never calls. Apps that opt out (some DRM players)
 * simply contribute silence.
 *
 * Runs on its own thread because [AudioRecord.read] blocks for a full chunk; sharing the
 * video drain thread would delay every frame by up to that much.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioCapture @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(
    projection: MediaProjection,
) : Closeable {

    /** Receives encoder output on the capture thread. */
    interface Sink {
        /** AudioSpecificConfig, delivered before any frame. */
        fun onAudioConfig(sampleRate: Int, channelCount: Int, codecSpecificData: ByteArray)

        /** One AAC access unit. The buffer is only valid for the duration of the call. */
        fun onAudioFrame(payload: ByteBuffer, presentationTimeUs: Long)
    }

    private val record: AudioRecord
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    init {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw UnsupportedOperationException("PCM capture unsupported ($minBuffer)")

        record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * RECORD_BUFFER_CHUNKS))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw UnsupportedOperationException("Playback capture could not be initialized")
        }

        codec = try {
            createEncoder()
        } catch (e: Exception) {
            record.release()
            throw e
        }
    }

    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
        }
        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (e: Exception) {
            encoder.release()
            throw e
        }
        return encoder
    }

    /** Starts capturing on a background thread. Failures end audio only, never the cast. */
    fun start(sink: Sink) {
        check(thread == null) { "Already started" }
        running = true
        record.startRecording()
        thread = Thread({ pump(sink) }, "smartcast-audio").apply {
            isDaemon = true
            start()
        }
    }

    private fun pump(sink: Sink) {
        var framesCaptured = 0L
        var startUs = -1L

        try {
            while (running) {
                val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(index)) { "Null input buffer $index" }
                    buffer.clear()
                    val wanted = minOf(buffer.remaining(), CHUNK_BYTES).let { it - it % BYTES_PER_FRAME }

                    val read = record.read(buffer, wanted, AudioRecord.READ_BLOCKING)
                    if (read < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, 0)
                        throw IllegalStateException("AudioRecord.read failed with $read")
                    }

                    if (startUs < 0) startUs = System.nanoTime() / 1_000
                    val presentationTimeUs = startUs + framesCaptured * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(index, 0, read, presentationTimeUs, 0)
                    framesCaptured += read / BYTES_PER_FRAME
                }
                drain(sink)
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Audio capture ended", e)
        } finally {
            running = false
        }
    }

    private fun drain(sink: Sink) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                index >= 0 -> emit(index, sink)

                // Some encoders publish the AudioSpecificConfig only here.
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    codec.outputFormat.getByteBuffer("csd-0")?.let { csd ->
                        sink.onAudioConfig(SAMPLE_RATE, CHANNELS, ByteArray(csd.remaining()).also { csd.get(it) })
                    }
                }

                else -> return
            }
        }
    }

    private fun emit(index: Int, sink: Sink) {
        try {
            val buffer = codec.getOutputBuffer(index) ?: return
            if (bufferInfo.size <= 0) return
            buffer.position(bufferInfo.offset)
            buffer.limit(bufferInfo.offset + bufferInfo.size)

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                sink.onAudioConfig(SAMPLE_RATE, CHANNELS, ByteArray(bufferInfo.size).also { buffer.get(it) })
            } else {
                sink.onAudioFrame(buffer, bufferInfo.presentationTimeUs)
            }
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    override fun close() {
        running = false
        runCatching { record.stop() }
        // The codec must outlive the thread that is still using it.
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { record.release() }
    }

    private companion object {
        const val TAG = "AudioCapture"
        const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC

        /** Playback capture mixes at the device rate; 48 kHz avoids a resample on most phones. */
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 2

        /** Transparent for AAC-LC stereo and negligible next to the video stream. */
        const val BIT_RATE = 128_000

        /** One AAC frame worth of PCM (1024 samples), so each read yields one access unit. */
        const val CHUNK_BYTES = 1024 * BYTES_PER_FRAME
        const val RECORD_BUFFER_CHUNKS = 8

        const val INPUT_TIMEOUT_US = 20_000L
        const val OUTPUT_TIMEOUT_US = 0L
        const val STOP_TIMEOUT_MS = 500L
    }
}
