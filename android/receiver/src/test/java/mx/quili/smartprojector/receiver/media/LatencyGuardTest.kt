package mx.quili.smartprojector.receiver.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FRAME_US = 33_333L

class LatencyGuardTest {

    private var clockUs = 1_000_000_000L
    private val guard = LatencyGuard(maxLagUs = 250_000L) { clockUs }

    /** A frame stamped [ptsUs] by the sender, arriving [delayUs] after a punctual one would. */
    private fun arrive(ptsUs: Long, keyframe: Boolean = false, delayUs: Long = 0): Boolean {
        clockUs = 1_000_000_000L + ptsUs + delayUs
        return guard.shouldDecode(ptsUs, keyframe)
    }

    @Test
    fun `waits for the first keyframe`() {
        assertFalse(arrive(0))
        assertTrue(arrive(FRAME_US, keyframe = true))
        assertTrue(arrive(2 * FRAME_US))
    }

    @Test
    fun `decodes a stream that keeps pace, jitter included`() {
        assertTrue(arrive(0, keyframe = true))
        for (i in 1..300) {
            assertTrue(arrive(i * FRAME_US, delayUs = (i % 5) * 20_000L))
        }
    }

    @Test
    fun `skips until a keyframe once the stream falls behind`() {
        assertTrue(arrive(0, keyframe = true))
        assertTrue(arrive(FRAME_US))

        assertFalse(arrive(2 * FRAME_US, delayUs = 400_000))
        assertTrue(guard.isSkipping)
        // Caught up, but a P-frame still cannot be decoded without its reference.
        assertFalse(arrive(3 * FRAME_US))

        assertTrue(arrive(4 * FRAME_US, keyframe = true))
        assertFalse(guard.isSkipping)
        assertTrue(arrive(5 * FRAME_US))
    }

    @Test
    fun `skips a keyframe that is itself late`() {
        assertTrue(arrive(0, keyframe = true))

        assertFalse(arrive(30 * FRAME_US, keyframe = true, delayUs = 900_000))
        assertTrue(arrive(60 * FRAME_US, keyframe = true))
    }

    @Test
    fun `accepts a lasting offset as clock drift instead of freezing`() {
        assertTrue(arrive(0, keyframe = true))

        val drift = 400_000L
        for (k in 1..LatencyGuard.MAX_KEYFRAMES_SKIPPED) {
            assertFalse(arrive(k * 30 * FRAME_US, keyframe = true, delayUs = drift))
        }
        val resume = (LatencyGuard.MAX_KEYFRAMES_SKIPPED + 1) * 30 * FRAME_US
        assertTrue(arrive(resume, keyframe = true, delayUs = drift))
        assertTrue(arrive(resume + FRAME_US, delayUs = drift))
    }

    @Test
    fun `a lost frame waits for the next keyframe`() {
        assertTrue(arrive(0, keyframe = true))
        guard.onFrameLost()

        assertFalse(arrive(FRAME_US))
        assertTrue(arrive(2 * FRAME_US, keyframe = true))
    }

    @Test
    fun `reset forgets the baseline and waits for a keyframe`() {
        assertTrue(arrive(0, keyframe = true))
        guard.reset()

        assertFalse(arrive(FRAME_US, delayUs = 5_000_000))
        assertTrue(arrive(2 * FRAME_US, keyframe = true, delayUs = 5_000_000))
    }
}
