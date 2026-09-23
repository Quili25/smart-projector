package mx.quili.smartprojector.receiver.core

import android.content.Context
import mx.quili.smartprojector.protocol.Pairing

/**
 * Decides which senders may cast.
 *
 * A device pairs once by typing the code shown on the projector; afterwards its id is
 * remembered so the user is not asked again. The code is regenerated after too many bad
 * attempts so that repeatedly guessing never converges on a fixed target.
 *
 * Thread-safe: authorization runs on whichever thread accepted the connection.
 */
class PairingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    private var currentPin: String = Pairing.generatePin()

    private var failedAttempts: Int = 0

    /** The code currently displayed on screen. */
    val pin: String
        get() = synchronized(lock) { currentPin }

    fun isPaired(deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        return synchronized(lock) { pairedIds().contains(deviceId) }
    }

    /**
     * Authorizes a sender, remembering it on success.
     *
     * @return true when the sender may proceed.
     */
    fun authorize(deviceId: String, providedPin: String): Boolean {
        if (deviceId.isBlank()) return false

        synchronized(lock) {
            if (pairedIds().contains(deviceId)) return true

            if (Pairing.matches(currentPin, providedPin)) {
                remember(deviceId)
                failedAttempts = 0
                return true
            }

            failedAttempts++
            if (failedAttempts >= Pairing.MAX_ATTEMPTS) {
                currentPin = Pairing.generatePin()
                failedAttempts = 0
            }
            return false
        }
    }

    /** Drops every remembered sender and issues a new code. */
    fun reset() {
        synchronized(lock) {
            prefs.edit().remove(KEY_PAIRED).apply()
            currentPin = Pairing.generatePin()
            failedAttempts = 0
        }
    }

    private fun pairedIds(): Set<String> =
        prefs.getStringSet(KEY_PAIRED, emptySet()) ?: emptySet()

    private fun remember(deviceId: String) {
        // The returned set must not be mutated in place; SharedPreferences does not
        // define behaviour for that and it silently fails to persist on some builds.
        val updated = pairedIds().toMutableSet().apply { add(deviceId) }
        prefs.edit().putStringSet(KEY_PAIRED, updated).apply()
    }

    private companion object {
        const val PREFS_NAME = "smartcast_pairing"
        const val KEY_PAIRED = "paired_device_ids"
    }
}
