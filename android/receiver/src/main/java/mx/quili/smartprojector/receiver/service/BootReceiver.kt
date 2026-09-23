package mx.quili.smartprojector.receiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import mx.quili.smartprojector.receiver.ui.ReceiverActivity

/**
 * Brings the receiver up with the projector.
 *
 * Starting the foreground service on boot is always permitted. Bringing the UI to the
 * front is not: since Android 10 a background app may only start an activity when it
 * holds "display over other apps". The deployment script grants that over ADB, so the
 * attempt is made and its failure is tolerated — without the permission the user simply
 * opens the app from the launcher, and the service is listening either way.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        ReceiverService.start(context)

        val launch = Intent(context, ReceiverActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(launch) }
            .onFailure { Log.i(TAG, "Could not auto-open the UI: ${it.message}") }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
