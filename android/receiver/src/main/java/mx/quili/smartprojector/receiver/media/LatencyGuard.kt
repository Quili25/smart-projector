package mx.quili.smartprojector.receiver.media

/**
 * Keeps mirroring live by discarding frames once the stream falls behind.
 *
 * TCP never loses data, so anything that slows the receiver down (a decoder hiccup, the
 * display holding on to buffers) becomes delay that only ever grows. Lag is measured as
 * how far `arrival time - sender timestamp` has risen above the best value seen: the two
 * clocks are unrelated, but their difference stays constant while the stream keeps pace.
 *
 * Decoding always resumes on a keyframe. A P-frame whose reference was skipped smears the
 * picture until the next IDR, which looks far worse than a brief freeze.
 *
 * Not thread-safe; owned by the session thread.
 */
class LatencyGuard(
    private val maxLagUs: Long = DEFAULT_MAX_LAG_US,
    private val nowUs: () -> Long,
) {

    private var baselineUs = Long.MAX_VALUE
    private var awaitingKeyframe = true
    private var keyframesSkipped = 0

    /** True while frames are being discarded until a usable keyframe arrives. */
    val isSkipping: Boolean get() = awaitingKeyframe

    fun shouldDecode(presentationTimeUs: Long, isKeyframe: Boolean): Boolean {
        val offsetUs = nowUs() - presentationTimeUs
        if (offsetUs < baselineUs) baselineUs = offsetUs
        val behind = offsetUs - baselineUs > maxLagUs

        if (!awaitingKeyframe && !behind) return true
        awaitingKeyframe = true

        if (!isKeyframe) return false
        if (behind && keyframesSkipped < MAX_KEYFRAMES_SKIPPED) {
            keyframesSkipped++
            return false
        }
        // Still behind after skipping several keyframes is not a backlog, which drains in
        // milliseconds once decoding stops: it is the two clocks drifting apart. Accept the
        // new offset instead of freezing forever.
        if (behind) baselineUs = offsetUs

        awaitingKeyframe = false
        keyframesSkipped = 0
        return true
    }

    /** A frame never reached the decoder, so every frame until the next IDR is unusable. */
    fun onFrameLost() {
        awaitingKeyframe = true
    }

    /** For a freshly configured decoder, which can only start from a keyframe. */
    fun reset() {
        baselineUs = Long.MAX_VALUE
        awaitingKeyframe = true
        keyframesSkipped = 0
    }

    companion object {
        /** Above ordinary Wi-Fi jitter, below the point where delay becomes noticeable. */
        const val DEFAULT_MAX_LAG_US = 250_000L

        const val MAX_KEYFRAMES_SKIPPED = 3
    }
}
