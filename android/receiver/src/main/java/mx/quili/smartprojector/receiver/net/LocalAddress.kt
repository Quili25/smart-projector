package mx.quili.smartprojector.receiver.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds the address a sender on the same network would use to reach us. */
object LocalAddress {

    /**
     * @return the first site-local IPv4 address of an up, non-loopback interface, or
     *         null when the device is offline.
     */
    fun findIpv4(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
            .getOrNull() ?: return null

        // Prefer Wi-Fi: a projector may also expose virtual interfaces (usb0, p2p0)
        // whose addresses are useless to a phone on the LAN.
        val candidates = interfaces.toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }

        for (nic in candidates) {
            for (address in nic.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
