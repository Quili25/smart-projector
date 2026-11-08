package mx.quili.smartprojector.sender.media

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import mx.quili.smartprojector.sender.core.SenderPrefs

/**
 * Keeps the phone's media output silent while its audio plays on the projector.
 *
 * Playback capture copies app audio at unity gain whatever the phone's volume (verified
 * on the CPH2531), so muting the music stream removes the echo without touching what the
 * projector receives. Only media is affected: notifications and calls were never captured
 * and keep sounding on the phone.
 *
 * Any volume change unmutes the stream, including the volume keys and the system slider,
 * so the mute is re-applied for as long as the cast holds it. Volume is then controlled
 * from the projector. On [release] the phone gets the last volume the user picked.
 *
 * The mute is recorded in [SenderPrefs] before it is applied, so a cast that dies midway
 * still gets its volume back via [release] the next time the app starts.
 */
class PhoneMute(context: Context, private val prefs: SenderPrefs) {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var holding = false

    private val guard = object : Runnable {
        override fun run() {
            if (!holding) return
            if (!audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) mute()
            handler.postDelayed(this, GUARD_INTERVAL_MS)
        }
    }

    /** Mutes now and keeps it muted until [release]. Callable from any thread. */
    fun engage() {
        if (audioManager.isVolumeFixed || holding) return
        holding = true
        if (!audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) mute()
        handler.post(guard)
    }

    /** Undoes [engage]. Safe to call any number of times, including with nothing to undo. */
    fun release() {
        holding = false
        handler.removeCallbacks(guard)
        if (!prefs.mediaMutedByCast) return
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            Log.i(TAG, "Phone media volume restored")
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not unmute the phone", e)
        }
        prefs.mediaMutedByCast = false
    }

    /**
     * A mute the user set before casting is not recorded, so it is never undone. Once the
     * stream has been unmuted during the cast, though, restoring sound at the end matches
     * what the user last asked for.
     */
    private fun mute() {
        prefs.mediaMutedByCast = true
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            Log.i(TAG, "Phone media muted while casting audio")
        } catch (e: SecurityException) {
            // Do Not Disturb policies can refuse volume changes on some builds.
            Log.w(TAG, "Could not mute the phone; audio will play on both devices", e)
            holding = false
            prefs.mediaMutedByCast = false
        }
    }

    private companion object {
        const val TAG = "PhoneMute"

        /** Short enough that a volume press leaks barely a moment of sound on the phone. */
        const val GUARD_INTERVAL_MS = 200L
    }
}
