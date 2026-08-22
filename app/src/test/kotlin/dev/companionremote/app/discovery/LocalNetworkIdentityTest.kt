package dev.companionremote.app.discovery

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalNetworkIdentityTest {
    private val signer: (ByteArray) -> ByteArray = { payload ->
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32) { 0x5a }, "HmacSHA256"))
            doFinal(payload)
        }
    }

    @Test
    fun `canonical fingerprint ignores collection order`() {
        val first = NetworkFingerprintMaterial(
            transports = setOf("wifi"),
            prefixes = linkedSetOf("6:fd000000000000000000000000000000/64", "4:c0a83200/24"),
            gateways = linkedSetOf("6:fd000000000000000000000000000001", "4:c0a83201"),
            dnsServers = linkedSetOf("4:01010101", "4:08080808"),
        )
        val reordered = NetworkFingerprintMaterial(
            transports = setOf("wifi"),
            prefixes = linkedSetOf("4:c0a83200/24", "6:fd000000000000000000000000000000/64"),
            gateways = linkedSetOf("4:c0a83201", "6:fd000000000000000000000000000001"),
            dnsServers = linkedSetOf("4:08080808", "4:01010101"),
        )

        assertEquals(
            PrivateIdentityFingerprint.network(first, signer),
            PrivateIdentityFingerprint.network(reordered, signer),
        )
    }

    @Test
    fun `a different LAN anchor produces a different opaque fingerprint`() {
        val home = material(gateway = "4:c0a83201")
        val elsewhere = material(gateway = "4:c0a832fe")
        val homeFingerprint = PrivateIdentityFingerprint.network(home, signer)
        val elsewhereFingerprint = PrivateIdentityFingerprint.network(elsewhere, signer)

        assertNotNull(homeFingerprint)
        assertNotEquals(homeFingerprint, elsewhereFingerprint)
        // Nothing resembling an address is embedded in the persisted value.
        assertEquals(true, homeFingerprint!!.startsWith("h1:"))
        assertEquals(false, homeFingerprint.contains("c0a832"))
    }

    @Test
    fun `fingerprint fails closed without prefix and router or DNS anchor`() {
        assertNull(
            PrivateIdentityFingerprint.network(
                material(gateway = "4:c0a83201").copy(prefixes = emptySet()),
                signer,
            ),
        )
        assertNull(
            PrivateIdentityFingerprint.network(
                material(gateway = "4:c0a83201").copy(
                    gateways = emptySet(),
                    dnsServers = emptySet(),
                ),
                signer,
            ),
        )
    }

    @Test
    fun `device identity is domain separated from network identity`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val device = PrivateIdentityFingerprint.device(payload, signer)
        val network = PrivateIdentityFingerprint.network(
            NetworkFingerprintMaterial(
                transports = setOf("wifi"),
                prefixes = setOf("4:01020304/32"),
                gateways = setOf("4:01020304"),
                dnsServers = emptySet(),
            ),
            signer,
        )
        assertNotEquals(device, network)
    }

    @Test
    fun `host bits are removed from IPv4 and IPv6 prefixes`() {
        assertArrayEquals(
            byteArrayOf(192.toByte(), 168.toByte(), 50, 0),
            LocalNetworkIdentity.maskPrefix(
                byteArrayOf(192.toByte(), 168.toByte(), 50, 123),
                24,
            ),
        )
        val ipv6 = ByteArray(16) { 0xff.toByte() }
        assertArrayEquals(
            byteArrayOf(
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
            LocalNetworkIdentity.maskPrefix(ipv6, 64),
        )
    }

    private fun material(gateway: String) = NetworkFingerprintMaterial(
        transports = setOf("wifi"),
        prefixes = setOf("4:c0a83200/24"),
        gateways = setOf(gateway),
        dnsServers = setOf("4:01010101"),
    )
}
