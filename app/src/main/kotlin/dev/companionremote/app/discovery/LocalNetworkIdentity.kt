package dev.companionremote.app.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dev.companionremote.app.data.KeystoreHmac
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64

/** An opaque, per-install identity for the currently attached physical LAN. */
internal data class LocalNetworkSnapshot(
    val fingerprint: String,
    val network: Network,
) {
    val networkHandle: Long get() = network.networkHandle
}

/**
 * Canonical input to the keyed fingerprint. Address strings exist only for
 * this in-memory calculation and are never persisted or logged.
 */
internal data class NetworkFingerprintMaterial(
    val transports: Set<String>,
    val prefixes: Set<String>,
    val gateways: Set<String>,
    val dnsServers: Set<String>,
) {
    fun canonicalBytes(): ByteArray? {
        if (transports.isEmpty() || prefixes.isEmpty()) return null
        // A prefix by itself is too generic on common private LANs. Require at
        // least one router- or resolver-derived anchor from LinkProperties.
        if (gateways.isEmpty() && dnsServers.isEmpty()) return null
        return buildString {
            append("network-fingerprint-v1\n")
            appendCanonical("transport", transports)
            appendCanonical("prefix", prefixes)
            appendCanonical("gateway", gateways)
            appendCanonical("dns", dnsServers)
        }.toByteArray(Charsets.UTF_8)
    }

    private fun StringBuilder.appendCanonical(label: String, values: Set<String>) {
        values.sorted().forEach { value ->
            append(label).append('=').append(value).append('\n')
        }
    }
}

/** Domain-separated keyed fingerprints for LAN and HAP device identities. */
internal object PrivateIdentityFingerprint {
    fun network(material: NetworkFingerprintMaterial, signer: (ByteArray) -> ByteArray): String? =
        material.canonicalBytes()?.let { fingerprint("network-v1", it, signer) }

    fun device(deviceIdentifier: ByteArray, signer: (ByteArray) -> ByteArray): String? {
        if (deviceIdentifier.isEmpty()) return null
        return fingerprint("apple-tv-hap-id-v1", deviceIdentifier, signer)
    }

    private fun fingerprint(
        domain: String,
        payload: ByteArray,
        signer: (ByteArray) -> ByteArray,
    ): String {
        val domainBytes = domain.toByteArray(Charsets.UTF_8)
        val input = ByteArray(domainBytes.size + 1 + payload.size)
        domainBytes.copyInto(input)
        payload.copyInto(input, domainBytes.size + 1)
        return "h1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(signer(input))
    }
}

/**
 * Reads only LinkProperties/NetworkCapabilities. SSID, BSSID and location
 * APIs are intentionally not used.
 */
internal class LocalNetworkIdentity(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)

    fun current(): LocalNetworkSnapshot? {
        if (!appContext.hasLocalNetworkPermission()) return null
        val network = currentPhysicalNetwork() ?: return null
        return snapshot(network)
    }

    /** Recomputes every fingerprint input for this exact Android Network. */
    fun snapshot(network: Network): LocalNetworkSnapshot? {
        if (!appContext.hasLocalNetworkPermission()) return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.isPhysicalLan()) return null
        val links = connectivityManager.getLinkProperties(network) ?: return null
        val material = links.toFingerprintMaterial(capabilities)
        val fingerprint = runCatching {
            PrivateIdentityFingerprint.network(material, KeystoreHmac::sign)
        }.getOrNull() ?: return null
        return LocalNetworkSnapshot(fingerprint, network)
    }

    fun deviceFingerprint(deviceIdentifier: ByteArray): String? =
        runCatching {
            PrivateIdentityFingerprint.device(deviceIdentifier, KeystoreHmac::sign)
        }.getOrNull()

    private fun currentPhysicalNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val candidates = buildList {
            if (active != null) add(active)
            connectivityManager.allNetworks.forEach { if (it != active) add(it) }
        }
        return candidates.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return@firstOrNull false
            capabilities.isPhysicalLan()
        }
    }

    private fun NetworkCapabilities.isPhysicalLan(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            (
                hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )

    private fun LinkProperties.toFingerprintMaterial(
        capabilities: NetworkCapabilities,
    ): NetworkFingerprintMaterial {
        val transports = buildSet {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        }
        val prefixes = linkAddresses.mapNotNullTo(mutableSetOf()) { link ->
            val address = link.address
            if (!address.isUsableLanAddress()) return@mapNotNullTo null
            val masked = maskPrefix(address.address, link.prefixLength)
            addressToken(masked, link.prefixLength)
        }
        val gateways = routes.mapNotNullTo(mutableSetOf()) { route ->
            if (!route.isDefaultRoute) return@mapNotNullTo null
            route.gateway?.takeIf { it.isUsableLanAddress() }?.let { addressToken(it) }
        }
        val dns = dnsServers.mapNotNullTo(mutableSetOf()) { address ->
            address.takeIf { it.isUsableLanAddress() }?.let { addressToken(it) }
        }
        return NetworkFingerprintMaterial(transports, prefixes, gateways, dns)
    }

    private fun InetAddress.isUsableLanAddress(): Boolean =
        (this is Inet4Address || this is Inet6Address) &&
            !isAnyLocalAddress && !isLoopbackAddress && !isMulticastAddress &&
            // Link-local addresses are common to almost every LAN and add no identity.
            !isLinkLocalAddress

    companion object {
        internal fun maskPrefix(address: ByteArray, prefixLength: Int): ByteArray {
            require(prefixLength in 0..address.size * 8) { "invalid prefix length" }
            val result = address.copyOf()
            val wholeBytes = prefixLength / 8
            val remainder = prefixLength % 8
            if (remainder != 0 && wholeBytes < result.size) {
                val mask = (0xff shl (8 - remainder)) and 0xff
                result[wholeBytes] = (result[wholeBytes].toInt() and mask).toByte()
            }
            val firstZeroByte = wholeBytes + if (remainder == 0) 0 else 1
            for (index in firstZeroByte until result.size) result[index] = 0
            return result
        }

        private fun addressToken(address: InetAddress): String = addressToken(address.address, null)

        internal fun addressToken(address: ByteArray, prefixLength: Int?): String {
            val family = when (address.size) {
                4 -> "4"
                16 -> "6"
                else -> throw IllegalArgumentException("unsupported address family")
            }
            val hex = address.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return buildString {
                append(family).append(':').append(hex)
                if (prefixLength != null) append('/').append(prefixLength)
            }
        }
    }
}
