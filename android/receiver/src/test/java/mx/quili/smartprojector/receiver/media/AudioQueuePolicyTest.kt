package mx.quili.smartprojector.receiver.media

import mx.quili.smartprojector.receiver.media.AudioQueuePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioQueuePolicyTest {

    private val rate = 48_000
    private val chunk = 1_024
    private val policy = AudioQueuePolicy(rate, capacityFrames = 14_400)

    @Test
    fun `an empty queue is primed before the first chunk`() {
        assertEquals(Action.PRIME_THEN_WRITE, policy.decide(queuedFrames = 0, chunkFrames = chunk))
        assertEquals(2_880, policy.startFrames)
    }

    @Test
    fun `silence plus the chunk exactly reaches the start threshold`() {
        assertEquals(2_880 - chunk, policy.silenceBefore(chunk, startThreshold = 2_880))
        // The track may round the threshold up; the cushion follows what it reports.
        assertEquals(3_072 - chunk, policy.silenceBefore(chunk, startThreshold = 3_072))
    }

    @Test
    fun `a chunk larger than the threshold needs no silence`() {
        assertEquals(0, policy.silenceBefore(chunkFrames = 4_096, startThreshold = 2_880))
    }

    @Test
    fun `a healthy queue just takes the chunk`() {
        assertEquals(Action.WRITE, policy.decide(queuedFrames = 4_000, chunkFrames = chunk))
    }

    @Test
    fun `a chunk that would exceed the cap is dropped`() {
        // 160 ms at 48 kHz is 7680 frames.
        assertEquals(Action.WRITE, policy.decide(queuedFrames = 7_680L - chunk, chunkFrames = chunk))
        assertEquals(Action.DROP, policy.decide(queuedFrames = 7_680L - chunk + 1, chunkFrames = chunk))
    }

    @Test
    fun `a small track buffer lowers the cap`() {
        val small = AudioQueuePolicy(rate, capacityFrames = 4_096)
        assertEquals(4_096, small.maxQueuedFrames)
        assertEquals(Action.DROP, small.decide(queuedFrames = 3_500, chunkFrames = chunk))
    }

    @Test
    fun `a buffer too small for the cushion is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioQueuePolicy(rate, capacityFrames = 1_000)
        }
    }

    @Test
    fun `queued frames are the written frames not yet played`() {
        assertEquals(1_500L, AudioQueuePolicy.queuedFrames(framesWritten = 10_000, playbackHead = 8_500))
    }

    @Test
    fun `queued frames survive the playback head wrapping`() {
        // Written has passed 2^32 frames; the head's unsigned counter wrapped to a small value.
        val written = (1L shl 32) + 300
        val head = -200 // 2^32 - 200 as an unsigned 32-bit value.
        assertEquals(500L, AudioQueuePolicy.queuedFrames(written, head))
    }

    @Test
    fun `duration converts to frames`() {
        assertEquals(2_880, AudioQueuePolicy.framesFor(60_000, rate))
        assertEquals(2_646, AudioQueuePolicy.framesFor(60_000, 44_100))
    }
}
