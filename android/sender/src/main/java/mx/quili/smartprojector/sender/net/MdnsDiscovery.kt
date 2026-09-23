package mx.quili.smartprojector.sender.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import mx.quili.smartprojector.protocol.Protocol
import mx.quili.smartprojector.sender.core.DiscoveredReceiver
import java.util.ArrayDeque

/**
 * Finds receivers advertising over mDNS.
 *
 * Resolution is serialized through a queue: `NsdManager` on older releases only tolerates
 * one in-flight `resolveService` call and answers a concurrent one with
 * FAILURE_ALREADY_ACTIVE, which silently loses whichever receiver was being resolved.
 */
class MdnsDiscovery(
    context: Context,
    private val onReceiversChanged: (List<DiscoveredReceiver>) -> Unit,
) {

    private val appContext = context.applicationContext

    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val multicastLock =
        (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock(MULTICAST_LOCK_TAG)
            .apply { setReferenceCounted(false) }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val found = linkedMapOf<String, DiscoveredReceiver>()

    private val pendingResolves = ArrayDeque<NsdServiceInfo>()

    private var resolving = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discoveryListener != null) return

        if (!multicastLock.isHeld) {
            multicastLock.acquire()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                mainHandler.post { enqueueResolve(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                mainHandler.post {
                    if (found.remove(info.serviceName) != null) {
                        publish()
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $errorCode")
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
                .onFailure { e -> Log.w(TAG, "Stop discovery failed", e) }
        }
        discoveryListener = null

        pendingResolves.clear()
        resolving = false
        found.clear()

        if (multicastLock.isHeld) {
            multicastLock.release()
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        pendingResolves.addLast(info)
        drainResolveQueue()
    }

    /**
     * `resolveService` is deprecated in favour of `registerServiceInfoCallback`, which
     * needs API 34. The projector runs API 30, so the deprecated call is the only one
     * that works on the hardware this app exists for; it remains functional on newer
     * releases.
     */
    @Suppress("DEPRECATION")
    private fun drainResolveQueue() {
        if (resolving) return
        val next = pendingResolves.pollFirst() ?: return

        resolving = true
        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                mainHandler.post {
                    val host = info.host?.hostAddress
                    if (host != null) {
                        found[info.serviceName] = DiscoveredReceiver(
                            name = info.serviceName,
                            host = host,
                            port = info.port,
                        )
                        publish()
                    }
                    resolving = false
                    drainResolveQueue()
                }
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for '${info.serviceName}': $errorCode")
                mainHandler.post {
                    resolving = false
                    drainResolveQueue()
                }
            }
        })
    }

    private fun publish() {
        onReceiversChanged(found.values.toList())
    }

    private companion object {
        const val TAG = "MdnsDiscovery"
        const val MULTICAST_LOCK_TAG = "smartcast-discovery"
    }
}
