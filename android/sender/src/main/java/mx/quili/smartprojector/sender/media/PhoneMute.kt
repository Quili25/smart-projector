package mx.quili.smartprojector.sender.media

import android.content.Context
import android.media.AudioManager
import android.util.Log
import mx.quili.smartprojector.sender.core.SenderPrefs

/**
 * Silences the phone's media output while its audio plays on the projector.
 *
 * Playback capture copies app audio at unity gain whatever the phone's volume (verified
 * on the CPH2531), so muting the music stream removes the echo without touching what the
 * projector receives. Only media is affected: notifications and calls were never captured
 * and keep sounding on the phone.
 *
 * The mute is recorded in [SenderPrefs] before it is applied, so a cast that dies midway
 * still gets its volume back via [release] the next time the app starts.
 */
class PhoneMute(context: Context, private val prefs: SenderPrefs) {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    fun engage() {
        if (audioManager.isVolumeFixed) return
        // A mute the user set themselves is theirs: leave it, and never undo it later.
        if (!prefs.mediaMutedByCast && audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) return

        prefs.mediaMutedByCast = true
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            Log.i(TAG, "Phone media muted while casting audio")
        } catch (e: SecurityException) {
            // Do Not Disturb policies can refuse volume changes on some builds.
            Log.w(TAG, "Could not mute the phone; audio will play on both devices", e)
            prefs.mediaMutedByCast = false
        }
    }

    /** Undoes [engage]. Safe to call any number of times, including with nothing to undo. */
    fun release() {
        if (!prefs.mediaMutedByCast) return
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            Log.i(TAG, "Phone media volume restored")
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not unmute the phone", e)
        }
        prefs.mediaMutedByCast = false
    }

    private companion object {
        const val TAG = "PhoneMute"
    }
}
