package mx.quili.smartprojector.sender.core

import android.content.Context
import java.util.UUID

/**
 * Durable sender identity and the set of receivers already paired with this phone.
 *
 * Remembering pairings locally is what lets the second and later connections skip the
 * code entirely: the receiver recognises the device id, so the sender knows not to
 * bother the user with a prompt.
 */
class SenderPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Random per-installation id.
     *
     * Deliberately not the hardware id: a random value is enough to recognise this
     * phone and avoids handing a persistent device identifier to anything on the LAN.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    fun isPaired(receiverKey: String): Boolean =
        pairedKeys().contains(receiverKey)

    fun remember(receiverKey: String) {
        val updated = pairedKeys().toMutableSet().apply { add(receiverKey) }
        prefs.edit().putStringSet(KEY_PAIRED, updated).apply()
    }

    /** Called when a receiver refuses a pairing we believed was still valid. */
    fun forget(receiverKey: String) {
        val updated = pairedKeys().toMutableSet().apply { remove(receiverKey) }
        prefs.edit().putStringSet(KEY_PAIRED, updated).apply()
    }

    private fun pairedKeys(): Set<String> =
        prefs.getStringSet(KEY_PAIRED, emptySet()) ?: emptySet()

    private companion object {
        const val PREFS_NAME = "smartcast_sender"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PAIRED = "paired_receivers"
    }
}
