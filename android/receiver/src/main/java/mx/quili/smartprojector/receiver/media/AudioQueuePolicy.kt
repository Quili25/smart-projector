package mx.quili.smartprojector.receiver.media

/**
 * Decides what to do with each decoded audio chunk so playback stays both smooth and live.
 *
 * Two clocks are involved, the phone's capture clock and the projector's DAC, and they
 * never run at exactly the same rate. Left alone, the queue in front of the DAC either
 * drains (clicks) or grows without bound (audio drifting behind the picture). So:
 *
 * - After an underrun, or at start, silence tops the queue up to [startFrames]. That
 *   cushion absorbs Wi-Fi jitter instead of underrunning again on the next late packet.
 * - A chunk that would push the queue past [maxQueuedFrames] is dropped. One 21 ms AAC
 *   frame is far less noticeable than audio that keeps sliding out of sync.
 */
class AudioQueuePolicy(
    sampleRate: Int,
    capacityFrames: Int,
    startUs: Long = DEFAULT_START_US,
    maxQueuedUs: Long = DEFAULT_MAX_QUEUED_US,
) {

    enum class Action { PRIME_THEN_WRITE, WRITE, DROP }

    val startFrames: Int = framesFor(startUs, sampleRate)

    val maxQueuedFrames: Int = minOf(framesFor(maxQueuedUs, sampleRate), capacityFrames)

    init {
        require(sampleRate > 0) { "Invalid sample rate $sampleRate" }
        require(startFrames < maxQueuedFrames) { "Start ($startFrames) must leave room below the cap ($maxQueuedFrames)" }
    }

    fun decide(queuedFrames: Long, chunkFrames: Int): Action = when {
        queuedFrames <= 0L -> Action.PRIME_THEN_WRITE
        queuedFrames + chunkFrames > maxQueuedFrames -> Action.DROP
        else -> Action.WRITE
    }

    /** Silence that, followed by the chunk, fills an empty queue exactly to [startThreshold]. */
    fun silenceBefore(chunkFrames: Int, startThreshold: Int): Int = (startThreshold - chunkFrames).coerceAtLeast(0)

    companion object {
        /** Covers typical 5 GHz jitter without adding a noticeable lip-sync offset. */
        const val DEFAULT_START_US = 60_000L

        /** Roughly where audio lagging the picture starts to be noticeable. */
        const val DEFAULT_MAX_QUEUED_US = 160_000L

        private const val UINT32_MASK = 0xFFFF_FFFFL

        fun framesFor(durationUs: Long, sampleRate: Int): Int = (durationUs * sampleRate / 1_000_000L).toInt()

        /**
         * Frames written but not yet played. `AudioTrack.getPlaybackHeadPosition` is an
         * unsigned 32-bit counter that wraps after about a day at 48 kHz, so the
         * difference is taken modulo 2^32.
         */
        fun queuedFrames(framesWritten: Long, playbackHead: Int): Long =
            ((framesWritten and UINT32_MASK) - (playbackHead.toLong() and UINT32_MASK)) and UINT32_MASK
    }
}
