package mx.quili.smartprojector.updates

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri

/**
 * Hands the APK URL to the browser, which downloads it and offers it to Android's package
 * installer. Neither app installs packages itself, so neither needs REQUEST_INSTALL_PACKAGES.
 */
object UpdateDownload {

    private const val TAG = "UpdateDownload"

    /**
     * True when something on the device can open the link.
     *
     * A bare projector image may ship without a browser, and telling the user to press OK on
     * a button that cannot do anything is worse than not offering it.
     */
    fun isAvailable(context: Context, update: AvailableUpdate): Boolean =
        intentFor(update).resolveActivity(context.packageManager) != null

    fun open(context: Context, update: AvailableUpdate): Boolean = try {
        context.startActivity(intentFor(update).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: android.content.ActivityNotFoundException) {
        Log.w(TAG, "No hay navegador para abrir ${update.apkUrl}", e)
        false
    }

    private fun intentFor(update: AvailableUpdate): Intent =
        Intent(Intent.ACTION_VIEW, update.apkUrl.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
}
