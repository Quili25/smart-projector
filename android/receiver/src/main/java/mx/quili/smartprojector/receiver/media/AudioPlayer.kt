package mx.quili.smartprojector.receiver.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import mx.quili.smartprojector.protocol.AudioConfig
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Decodes the sender's AAC stream and plays it as soon as it arrives.
 *
 * Runs on the session thread like [VideoDecoder], so nothing here may block: input waits
 * are a few milliseconds and PCM goes to the [AudioTrack] with non-blocking writes. A
 * codec or track failure only disables audio ([failed]); the picture must keep going.
 */
class AudioPlayer(config: AudioConfig) : Closeable {

    private val codec: MediaCodec = MediaCodec.createDecoderByType(MIME_TYPE)
    private val bufferInfo = MediaCodec.BufferInfo()

    private var output: Output? = null

    private var chunksDropped = 0L
    private var underruns = 0L

    /** True once audio hit an unrecoverable error; the caller should close this player. */
    var failed = false
        private set

    init {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, config.sampleRate, config.channelCount).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config.codecSpecificData))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            output = Output(config.sampleRate, config.channelCount)
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw e
        }
    }

    /**
     * Feeds one AAC access unit. [fill] must write exactly [length] bytes.
     *
     * @return true when the payload was consumed; false means the caller must skip it.
     */
    fun submit(presentationTimeUs: Long, length: Int, fill: (ByteBuffer) -> Unit): Boolean {
        if (failed) return false

        val index = guarded { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) } ?: return false
        if (index < 0) {
            guarded { drain() }
            return false
        }

        val buffer = guarded { codec.getInputBuffer(index) }?.apply { clear() } ?: return false
        if (buffer.remaining() < length) {
            guarded { codec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0) }
            Log.w(TAG, "Audio frame of $length bytes exceeds the ${buffer.remaining()}-byte input buffer")
            return false
        }

        fill(buffer)
        guarded {
            codec.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
            drain()
        }
        return true
    }

    private fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                index >= 0 -> {
                    try {
                        val pcm = codec.getOutputBuffer(index)
                        if (pcm != null && bufferInfo.size > 0) {
                            pcm.position(bufferInfo.offset)
                            pcm.limit(bufferInfo.offset + bufferInfo.size)
                            play(pcm)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormat(codec.outputFormat)

                else -> return
            }
        }
    }

    /** HE-AAC or a mismatched header can change the real PCM shape after the first frame. */
    private fun onOutputFormat(format: MediaFormat) {
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
        ) {
            throw IllegalStateException("Unsupported PCM encoding in $format")
        }
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val current = output
        if (current != null && current.sampleRate == sampleRate && current.channels == channels) return

        Log.i(TAG, "Audio output is now ${sampleRate}Hz x$channels")
        current?.release()
        // Cleared first so a failed rebuild never leaves a released track in place.
        output = null
        output = Output(sampleRate, channels)
    }

    private fun play(pcm: ByteBuffer) {
        val out = output ?: return
        out.widenOnceStarted()

        val chunkFrames = pcm.remaining() / out.bytesPerFrame
        val queued = AudioQueuePolicy.queuedFrames(out.framesWritten, out.track.playbackHeadPosition)

        when (out.policy.decide(queued, chunkFrames)) {
            AudioQueuePolicy.Action.DROP -> {
                chunksDropped++
                return
            }

            AudioQueuePolicy.Action.PRIME_THEN_WRITE -> {
                if (out.framesWritten > 0) underruns++
                out.prime(chunkFrames)
            }

            AudioQueuePolicy.Action.WRITE -> Unit
        }
        out.write(pcm)
    }

    /**
     * The failures MediaCodec and AudioTrack report at runtime (CodecException is an
     * IllegalStateException; the track builder throws the other two). None may end the
     * session. Anything else is a bug and propagates.
     */
    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: IllegalStateException) {
        disable(e)
    } catch (e: IllegalArgumentException) {
        disable(e)
    } catch (e: UnsupportedOperationException) {
        disable(e)
    }

    private fun disable(cause: RuntimeException): Nothing? {
        if (!failed) Log.w(TAG, "Audio disabled after a codec or track error", cause)
        failed = true
        return null
    }

    override fun close() {
        Log.i(TAG, "Audio stopped: $chunksDropped chunks dropped to stay in sync, $underruns underruns")
        output?.release()
        output = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /**
     * One AudioTrack and the bookkeeping that belongs to its clock.
     *
     * A streaming track stays silent until it holds a full buffer (or, from Android 12,
     * its start threshold), and it re-enters that state after an underrun long enough
     * to disable it. With the buffer sized for headroom that would mean 300 ms of delay
     * before the first sound, or no sound at all once the policy caps the queue lower.
     * So each start shrinks the effective buffer to the cushion, fills it, and widens it
     * back to the cap as soon as the playback head moves.
     */
    private class Output(val sampleRate: Int, val channels: Int) {

        val bytesPerFrame = channels * BYTES_PER_SAMPLE
        val track: AudioTrack
        val policy: AudioQueuePolicy
        private val silence: ByteBuffer

        var framesWritten = 0L
            private set

        /** Playback head when the cushion went in; null once the track is running. */
        private var headAtPrime: Int? = null

        init {
            require(channels in 1..2) { "Unsupported channel count $channels" }
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) throw IllegalStateException("No PCM output for ${sampleRate}Hz x$channels ($minBytes)")

            // Allocated with headroom above the policy cap; the effective size is set per start.
            val capacityBytes = maxOf(
                minBytes,
                AudioQueuePolicy.framesFor(TRACK_BUFFER_US, sampleRate) * bytesPerFrame,
            )

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(capacityBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            try {
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack could not be initialized" }
                policy = AudioQueuePolicy(sampleRate, capacityFrames = track.bufferSizeInFrames)
                silence = ByteBuffer.allocateDirect(policy.maxQueuedFrames * bytesPerFrame)
                track.play()
            } catch (e: RuntimeException) {
                track.release()
                throw e
            }
        }

        /** Arms the start threshold and tops the queue up so the next chunk starts playback. */
        fun prime(chunkFrames: Int) {
            val threshold = track.setBufferSizeInFrames(policy.startFrames)
            if (threshold < 0) throw IllegalStateException("setBufferSizeInFrames failed with $threshold")

            val frames = policy.silenceBefore(chunkFrames, threshold).coerceAtMost(policy.maxQueuedFrames)
            write(silence.duplicate().apply { limit(frames * bytesPerFrame) })
            headAtPrime = track.playbackHeadPosition
        }

        fun widenOnceStarted() {
            val head = headAtPrime ?: return
            if (track.playbackHeadPosition == head) return
            val size = track.setBufferSizeInFrames(policy.maxQueuedFrames)
            if (size < 0) throw IllegalStateException("setBufferSizeInFrames failed with $size")
            headAtPrime = null
        }

        fun write(pcm: ByteBuffer) {
            val written = track.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) throw IllegalStateException("AudioTrack.write failed with $written")
            framesWritten += written / bytesPerFrame
        }

        fun release() {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    private companion object {
        const val TAG = "AudioPlayer"
        const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        const val BYTES_PER_SAMPLE = 2

        const val TRACK_BUFFER_US = 300_000L

        /** Short: a blocked dequeue would stall the socket reader and the video with it. */
        const val INPUT_TIMEOUT_US = 5_000L
        const val OUTPUT_TIMEOUT_US = 0L
    }
}
