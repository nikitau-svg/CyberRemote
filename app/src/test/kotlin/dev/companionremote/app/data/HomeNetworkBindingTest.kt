package dev.companionremote.app.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeNetworkBindingTest {
    private val binding = HomeNetworkBinding(
        deviceName = "Living Room 电视",
        deviceModel = "AppleTV14,1",
        endpointHost = "192.0.2.42",
        endpointPort = 49152,
        deviceFingerprint = "h1:device",
        networkFingerprint = "h1:home",
        continuityFingerprints = setOf("h1:continuity-v4", "h1:continuity-v6"),
    )

    @Test
    fun `binding codec round trips unicode and endpoint`() {
        assertEquals(binding, HomeNetworkBindingCodec.decode(HomeNetworkBindingCodec.encode(binding)))
        val withoutContinuity = bindingWithSingleRecord(
            binding,
            exact = "h1:home",
            continuity = emptySet(),
        )
        assertEquals(
            withoutContinuity,
            HomeNetworkBindingCodec.decode(HomeNetworkBindingCodec.encode(withoutContinuity)),
        )
    }

    @Test
    fun `binding codec round trips multiple exact variants`() {
        val variants = binding
            .withVerifiedNetworkVariant(
                exactFingerprint = "h1:second-exact",
                continuityFingerprints = setOf("h1:second-anchor"),
            ).withVerifiedNetworkVariant(
                exactFingerprint = "h1:third-exact",
                continuityFingerprints = setOf("h1:third-anchor-v4", "h1:third-anchor-v6"),
            )

        assertEquals(
            variants,
            HomeNetworkBindingCodec.decode(HomeNetworkBindingCodec.encode(variants)),
        )
    }

    @Test
    fun `binding codec rejects malformed and trailing data`() {
        assertNull(HomeNetworkBindingCodec.decode("not base64!"))
        assertNull(HomeNetworkBindingCodec.decode(HomeNetworkBindingCodec.encode(binding) + "AA"))
    }

    @Test
    fun `binding codec decodes legacy v1 without continuity hints`() {
        val legacy = bindingWithSingleRecord(
            binding,
            exact = "h1:home",
            continuity = emptySet(),
        )

        assertEquals(legacy, HomeNetworkBindingCodec.decode(encodeLegacyV1(legacy)))
    }

    @Test
    fun `binding codec decodes legacy v2 into one bounded record`() {
        val decoded = HomeNetworkBindingCodec.decode(encodeLegacyV2(binding))

        assertEquals(binding, decoded)
        assertEquals(1, decoded?.networkFingerprintRecords?.size)

        val manyAnchors = (0 until 64).mapTo(linkedSetOf()) { "h1:legacy-anchor-$it" }
        val oversizedLegacy = bindingWithSingleRecord(
            binding,
            exact = "h1:home",
            continuity = manyAnchors,
        )
        val bounded = HomeNetworkBindingCodec.decode(encodeLegacyV2(oversizedLegacy))
        assertEquals(
            MAX_CONTINUITY_FINGERPRINTS_PER_RECORD,
            bounded?.continuityFingerprints?.size,
        )
    }

    @Test
    fun `automatic access requires both home network and expected TV identity`() {
        assertTrue(
            HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                currentNetworkFingerprint = "h1:home",
                expectedDeviceFingerprint = "h1:device",
            ),
        )
        assertFalse(
            HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                currentNetworkFingerprint = "h1:other-network",
                expectedDeviceFingerprint = "h1:device",
            ),
        )
        assertFalse(
            HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                currentNetworkFingerprint = "h1:home",
                expectedDeviceFingerprint = "h1:other-tv",
            ),
        )
        assertFalse(
            HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                currentNetworkFingerprint = null,
                expectedDeviceFingerprint = "h1:device",
            ),
        )
    }

    @Test
    fun `continuity match is a candidate and never strict authorization`() {
        assertTrue(
            HomeNetworkPolicy.isContinuityCandidate(
                binding,
                "h1:home",
                setOf("h1:continuity-v4"),
            ),
        )
        assertFalse(
            HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                currentNetworkFingerprint = "h1:different-strict-network",
                expectedDeviceFingerprint = "h1:device",
            ),
        )
        assertFalse(
            HomeNetworkPolicy.isContinuityCandidate(
                bindingWithSingleRecord(
                    binding,
                    exact = "h1:home",
                    continuity = emptySet(),
                ),
                "h1:home",
                setOf("h1:continuity-v4"),
            ),
        )
        assertTrue(HomeNetworkPolicy.matchesDeviceIdentity(binding, "h1:device"))
        assertFalse(HomeNetworkPolicy.matchesDeviceIdentity(binding, "h1:wrong-device"))
    }

    @Test
    fun `legacy binding gains continuity only after strict migration hook`() {
        val legacy = bindingWithSingleRecord(
            binding,
            exact = "h1:home",
            continuity = emptySet(),
        )
        val migrated = legacy.migrateContinuityAfterStrictMatch(
            "h1:home",
            setOf("h1:new-anchor"),
        )

        assertEquals(setOf("h1:new-anchor"), migrated.continuityFingerprints)
        assertEquals(
            binding,
            binding.migrateContinuityAfterStrictMatch(
                "h1:home",
                setOf("h1:replacement"),
            ),
        )
        assertEquals(
            legacy,
            legacy.migrateContinuityAfterStrictMatch("h1:home", emptySet()),
        )
    }

    @Test
    fun `strict authorization accepts every verified exact variant`() {
        val variants = binding.withVerifiedNetworkVariant(
            exactFingerprint = "h1:second-exact",
            continuityFingerprints = setOf("h1:second-anchor"),
        )

        assertTrue(HomeNetworkPolicy.allowsAutomaticAccess(variants, "h1:home"))
        assertTrue(HomeNetworkPolicy.allowsAutomaticAccess(variants, "h1:second-exact"))
        assertFalse(HomeNetworkPolicy.allowsAutomaticAccess(variants, "h1:unverified"))
    }

    @Test
    fun `verified variants use bounded LRU order`() {
        val fiveVariants = (2..5).fold(binding) { current, index ->
            current.withVerifiedNetworkVariant(
                exactFingerprint = "h1:exact-$index",
                continuityFingerprints = setOf("h1:anchor-$index"),
            )
        }
        assertEquals(
            listOf("h1:exact-2", "h1:exact-3", "h1:exact-4", "h1:exact-5"),
            fiveVariants.networkFingerprintRecords.map { it.exactFingerprint },
        )

        val touched = fiveVariants.withVerifiedNetworkVariant(
            exactFingerprint = "h1:exact-3",
            continuityFingerprints = setOf("h1:anchor-3-new"),
        )
        assertEquals(
            listOf("h1:exact-2", "h1:exact-4", "h1:exact-5", "h1:exact-3"),
            touched.networkFingerprintRecords.map { it.exactFingerprint },
        )
        assertEquals(setOf("h1:anchor-3-new"), touched.continuityFingerprints)
    }

    @Test
    fun `continuity anchors are scoped to origin exact variant`() {
        val variants = binding.withVerifiedNetworkVariant(
            exactFingerprint = "h1:second-exact",
            continuityFingerprints = setOf("h1:second-anchor"),
        )

        assertFalse(
            HomeNetworkPolicy.isContinuityCandidate(
                variants,
                originStrictFingerprint = "h1:home",
                currentContinuityFingerprints = setOf("h1:second-anchor"),
            ),
        )
        assertTrue(
            HomeNetworkPolicy.isContinuityCandidate(
                variants,
                originStrictFingerprint = "h1:second-exact",
                currentContinuityFingerprints = setOf("h1:second-anchor"),
            ),
        )
        assertFalse(
            HomeNetworkPolicy.isContinuityCandidate(
                variants,
                originStrictFingerprint = "h1:never-authorized",
                currentContinuityFingerprints = setOf("h1:second-anchor"),
            ),
        )
    }

    @Test
    fun `endpoint-only refresh preserves continuity origin`() {
        val endpointRefresh = binding.copy(
            deviceName = "Renamed Living Room",
            deviceModel = "AppleTV14,2",
            endpointHost = "192.0.2.99",
            endpointPort = 54321,
        )

        assertTrue(
            HomeNetworkPolicy.isContinuityOriginEquivalent(
                originBinding = binding,
                originStrictFingerprint = "h1:home",
                persistedBinding = endpointRefresh,
            ),
        )
        assertTrue(
            HomeNetworkPolicy.isContinuityOriginEquivalent(
                originBinding = binding,
                originStrictFingerprint = "h1:home",
                persistedBinding = endpointRefresh.withVerifiedNetworkVariant(
                    exactFingerprint = "h1:another-verified-exact",
                    continuityFingerprints = setOf("h1:another-anchor"),
                ),
            ),
        )
    }

    @Test
    fun `continuity origin rejects another TV or a rebind without origin exact`() {
        val anotherTv = binding.copy(deviceFingerprint = "h1:another-device")
        val reboundNetwork = bindingWithSingleRecord(
            binding,
            exact = "h1:new-home",
            continuity = setOf("h1:new-anchor"),
        )
        val changedOriginAnchors = bindingWithSingleRecord(
            binding,
            exact = "h1:home",
            continuity = setOf("h1:unrelated-anchor"),
        )

        assertFalse(
            HomeNetworkPolicy.isContinuityOriginEquivalent(
                originBinding = binding,
                originStrictFingerprint = "h1:home",
                persistedBinding = anotherTv,
            ),
        )
        assertFalse(
            HomeNetworkPolicy.isContinuityOriginEquivalent(
                originBinding = binding,
                originStrictFingerprint = "h1:home",
                persistedBinding = reboundNetwork,
            ),
        )
        assertFalse(
            HomeNetworkPolicy.isContinuityOriginEquivalent(
                originBinding = binding,
                originStrictFingerprint = "h1:home",
                persistedBinding = changedOriginAnchors,
            ),
        )
    }

    private fun encodeLegacyV1(value: HomeNetworkBinding): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(1)
                output.writeLegacyField(value.deviceName)
                output.writeBoolean(value.deviceModel != null)
                value.deviceModel?.let { model -> output.writeLegacyField(model) }
                output.writeLegacyField(value.endpointHost)
                output.writeInt(value.endpointPort)
                output.writeLegacyField(value.deviceFingerprint)
                output.writeLegacyField(value.networkFingerprint)
            }
            buffer.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun encodeLegacyV2(value: HomeNetworkBinding): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(2)
                output.writeLegacyField(value.deviceName)
                output.writeBoolean(value.deviceModel != null)
                value.deviceModel?.let { model -> output.writeLegacyField(model) }
                output.writeLegacyField(value.endpointHost)
                output.writeInt(value.endpointPort)
                output.writeLegacyField(value.deviceFingerprint)
                output.writeLegacyField(value.networkFingerprint)
                output.writeInt(value.continuityFingerprints.size)
                value.continuityFingerprints.sorted().forEach { fingerprint ->
                    output.writeLegacyField(fingerprint)
                }
            }
            buffer.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun bindingWithSingleRecord(
        source: HomeNetworkBinding,
        exact: String,
        continuity: Set<String>,
    ): HomeNetworkBinding = source.copy(
        networkFingerprint = exact,
        continuityFingerprints = continuity,
        networkFingerprintRecords = listOf(HomeNetworkFingerprintRecord(exact, continuity)),
    )

    private fun DataOutputStream.writeLegacyField(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
