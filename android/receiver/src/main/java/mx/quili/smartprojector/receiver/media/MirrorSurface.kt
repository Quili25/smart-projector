package mx.quili.smartprojector.receiver.media

import android.os.SystemClock
import android.view.Surface

/**
 * Hands the activity's rendering surface to the session thread.
 *
 * The socket is accepted on a background thread, but the surface only exists while the
 * activity is resumed and its `SurfaceView` has been laid out. The session therefore has
 * to wait for it, and must be told when it goes away so the decoder can be torn down
 * before the surface is destroyed — releasing a codec against a dead surface crashes in
 * native code.
 */
object MirrorSurface {

    private val lock = Object()

    private var surface: Surface? = null

    /** Publishes a new surface, or `null` when the current one is being destroyed. */
    fun publish(next: Surface?) {
        synchronized(lock) {
            surface = next
            lock.notifyAll()
        }
    }

    /**
     * Blocks until a surface is available.
     *
     * @return the surface, or `null` if [timeoutMs] elapsed first.
     */
    fun await(timeoutMs: Long): Surface? {
        synchronized(lock) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (surface == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                lock.wait(remaining)
            }
            return surface
        }
    }

    val current: Surface?
        get() = synchronized(lock) { surface }
}
