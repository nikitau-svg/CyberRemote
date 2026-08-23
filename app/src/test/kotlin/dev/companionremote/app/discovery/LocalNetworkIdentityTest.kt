package dev.companionremote.app.discovery

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `continuity anchor survives DNS churn while strict v1 remains strict`() {
        val first = material(gateway = "4:c0a83201")
        val changedDns = first.copy(dnsServers = setOf("4:09090909"))

        assertNotEquals(
            PrivateIdentityFingerprint.network(first, signer),
            PrivateIdentityFingerprint.network(changedDns, signer),
        )
        assertEquals(
            PrivateIdentityFingerprint.networkContinuity(first, signer),
            PrivateIdentityFingerprint.networkContinuity(changedDns, signer),
        )
    }

    @Test
    fun `stable IPv4 continuity survives unrelated IPv6 addition`() {
        val ipv4Only = material(gateway = "4:c0a83201")
        val dualStack = ipv4Only.copy(
            prefixes = ipv4Only.prefixes + "6:fd000000000000000000000000000000/64",
            gateways = ipv4Only.gateways + "6:fd000000000000000000000000000001",
            continuityGateways = ipv4Only.continuityGateways +
                "6:fe800000000000000000000000000001",
        )
        val before = PrivateIdentityFingerprint.networkContinuity(ipv4Only, signer)
        val after = PrivateIdentityFingerprint.networkContinuity(dualStack, signer)

        assertTrue(before.intersect(after).isNotEmpty())
        assertNotEquals(
            PrivateIdentityFingerprint.network(ipv4Only, signer),
            PrivateIdentityFingerprint.network(dualStack, signer),
        )
    }

    @Test
    fun `different prefix or gateway has no continuity intersection`() {
        val home = material(gateway = "4:c0a83201")
        val changedGateway = home.copy(
            gateways = setOf("4:c0a832fe"),
            continuityGateways = setOf("4:c0a832fe"),
        )
        val changedPrefix = home.copy(prefixes = setOf("4:c0a83300/24"))
        val expected = PrivateIdentityFingerprint.networkContinuity(home, signer)

        assertTrue(
            expected.intersect(
                PrivateIdentityFingerprint.networkContinuity(changedGateway, signer),
            ).isEmpty(),
        )
        assertTrue(
            expected.intersect(
                PrivateIdentityFingerprint.networkContinuity(changedPrefix, signer),
            ).isEmpty(),
        )
    }

    @Test
    fun `continuity requires a same-family prefix and gateway`() {
        val crossFamilyOnly = material(gateway = "4:c0a83201").copy(
            continuityGateways = setOf("6:fe800000000000000000000000000001"),
        )

        assertTrue(
            PrivateIdentityFingerprint.networkContinuity(crossFamilyOnly, signer).isEmpty(),
        )
    }

    @Test
    fun `continuity has no DNS fallback`() {
        val dnsOnly = material(gateway = "4:c0a83201").copy(
            gateways = emptySet(),
            continuityGateways = emptySet(),
            dnsServers = setOf("4:01010101"),
        )

        assertNotNull(PrivateIdentityFingerprint.network(dnsOnly, signer))
        assertTrue(PrivateIdentityFingerprint.networkContinuity(dnsOnly, signer).isEmpty())
    }

    @Test
    fun `IPv6 link-local gateway can anchor continuity without changing strict v1`() {
        val strictMaterial = NetworkFingerprintMaterial(
            transports = setOf("wifi"),
            prefixes = setOf("6:fd000000000000000000000000000000/64"),
            gateways = emptySet(),
            dnsServers = setOf("6:20014860486000000000000000008888"),
        )
        val withLinkLocalContinuity = strictMaterial.copy(
            continuityGateways = setOf("6:fe800000000000000000000000000001"),
        )

        assertEquals(
            PrivateIdentityFingerprint.network(strictMaterial, signer),
            PrivateIdentityFingerprint.network(withLinkLocalContinuity, signer),
        )
        assertTrue(
            PrivateIdentityFingerprint.networkContinuity(strictMaterial, signer).isEmpty(),
        )
        assertTrue(
            PrivateIdentityFingerprint.networkContinuity(
                withLinkLocalContinuity,
                signer,
            ).isNotEmpty(),
        )
    }

    @Test
    fun `continuity generation stays bounded for pathological link properties`() {
        val prefixes = (0 until 256).mapTo(linkedSetOf()) { index ->
            "4:${index.toString(16).padStart(8, '0')}/24"
        }
        val gateways = (0 until 256).mapTo(linkedSetOf()) { index ->
            "4:${(index + 0x10000).toString(16).padStart(8, '0')}"
        }
        val material = NetworkFingerprintMaterial(
            transports = linkedSetOf("wifi", "ethernet"),
            prefixes = prefixes,
            gateways = gateways,
            dnsServers = emptySet(),
        )

        val fingerprints = PrivateIdentityFingerprint.networkContinuity(material, signer)
        val reordered = PrivateIdentityFingerprint.networkContinuity(
            material.copy(
                transports = material.transports.reversed().toSet(),
                prefixes = material.prefixes.reversed().toSet(),
                gateways = material.gateways.reversed().toSet(),
                continuityGateways = material.continuityGateways.reversed().toSet(),
            ),
            signer,
        )

        assertEquals(16, fingerprints.size)
        assertEquals(fingerprints, reordered)
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
