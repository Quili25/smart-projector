package mx.quili.smartprojector.receiver.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import mx.quili.smartprojector.protocol.Protocol

/**
 * Publishes the receiver over mDNS so senders find it without typing an IP.
 *
 * Also holds a multicast lock: Wi-Fi power saving drops multicast traffic by default,
 * which makes the projector invisible to discovery a few seconds after the screen
 * settles — the classic "it worked, now it doesn't" report.
 */
class MdnsAdvertiser(context: Context) {

    private val appContext = context.applicationContext

    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val multicastLock =
        (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock(MULTICAST_LOCK_TAG)
            .apply { setReferenceCounted(false) }

    private var listener: NsdManager.RegistrationListener? = null

    /** Name the network actually accepted, which may be suffixed on a collision. */
    @Volatile
    var registeredName: String? = null
        private set

    fun start(serviceName: String, port: Int = Protocol.DEFAULT_PORT) {
        if (listener != null) return

        if (!multicastLock.isHeld) {
            multicastLock.acquire()
        }

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = Protocol.SERVICE_TYPE
            this.port = port
        }

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                Log.i(TAG, "Registered as '${info.serviceName}'")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed with code $errorCode")
                registeredName = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Unregistered '${info.serviceName}'")
                registeredName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregistration failed with code $errorCode")
            }
        }

        listener = registrationListener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun stop() {
        listener?.let {
            // Unregistering an already-dead listener throws; the service is going away
            // regardless, so a failure here must not mask the real shutdown path.
            runCatching { nsdManager.unregisterService(it) }
                .onFailure { e -> Log.w(TAG, "Unregister failed", e) }
        }
        listener = null
        registeredName = null

        if (multicastLock.isHeld) {
            multicastLock.release()
        }
    }

    private companion object {
        const val TAG = "MdnsAdvertiser"
        const val MULTICAST_LOCK_TAG = "smartcast-mdns"
    }
}
