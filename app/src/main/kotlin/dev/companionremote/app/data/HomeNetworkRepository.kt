package dev.companionremote.app.data

import android.content.Context
import android.net.Network
import dev.companionremote.app.discovery.DiscoveredAtv
import dev.companionremote.app.discovery.LocalNetworkIdentity
import dev.companionremote.app.discovery.LocalNetworkSnapshot

/** A short-lived capability proving that automatic LAN access is allowed. */
internal class HomeNetworkAuthorization(
    val binding: HomeNetworkBinding,
    val network: Network,
    internal val strictFingerprint: String,
    private val identity: LocalNetworkIdentity,
) {
    /** A Network handle alone is insufficient: link properties can change in-place. */
    fun isStillValid(): Boolean {
        val current = identity.snapshot(network) ?: return false
        return HomeNetworkPolicy.allowsAutomaticAccess(binding, current.fingerprint)
    }

    fun isFor(candidate: Network): Boolean = network == candidate
}

/**
 * A possible successor to a Network that was strictly authorized earlier in this process.
 * It intentionally exposes no cached endpoint and grants no automatic-access capability.
 */
internal class HomeNetworkContinuityCandidate(
    val network: Network,
    /** Exact persisted value used for endpoint lookup and the eventual promotion CAS. */
    internal val persistedBinding: HomeNetworkBinding,
    internal val originStrictFingerprint: String,
    internal val candidateStrictFingerprint: String,
    private val candidateNetworkHandle: Long,
    private val identity: LocalNetworkIdentity,
) {
    fun isStillCandidate(): Boolean {
        val current = identity.snapshot(network) ?: return false
        return matchesSnapshot(current)
    }

    internal fun matchesSnapshot(current: LocalNetworkSnapshot): Boolean {
        if (
            network.networkHandle != candidateNetworkHandle ||
            current.networkHandle != candidateNetworkHandle ||
            current.fingerprint != candidateStrictFingerprint
        ) {
            return false
        }
        return HomeNetworkPolicy.isContinuityCandidate(
            persistedBinding,
            originStrictFingerprint,
            current.continuityFingerprints,
        )
    }
}

/**
 * Owns the automatic-access boundary: a cached endpoint may only be used, and
 * its service name may only be resolved, while the bound LAN is current.
 */
internal class HomeNetworkRepository(context: Context) {
    private val settingsRepository = SettingsRepository(context)
    private val identity = LocalNetworkIdentity(context)

    suspend fun automaticAuthorization(): HomeNetworkAuthorization? {
        val binding = settingsRepository.homeNetworkBinding() ?: return null
        val current = identity.matchingAny(binding.exactFingerprints()) ?: return null
        return strictAuthorization(binding, current)
    }

    suspend fun automaticAuthorization(deviceIdentifier: ByteArray): HomeNetworkAuthorization? {
        val binding = settingsRepository.homeNetworkBinding() ?: return null
        val current = identity.matchingAny(binding.exactFingerprints()) ?: return null
        val deviceFingerprint = identity.deviceFingerprint(deviceIdentifier) ?: return null
        return strictAuthorization(binding, current, deviceFingerprint)
    }

    /**
     * Returns only a continuity candidate. Callers must already hold their own in-memory proof of
     * an earlier strict authorization and must not use this result to access the cached endpoint.
     */
    suspend fun continuityCandidate(
        origin: HomeNetworkAuthorization,
    ): HomeNetworkContinuityCandidate? {
        val binding = settingsRepository.homeNetworkBinding() ?: return null
        if (
            !HomeNetworkPolicy.isContinuityOriginEquivalent(
                originBinding = origin.binding,
                originStrictFingerprint = origin.strictFingerprint,
                persistedBinding = binding,
            )
        ) {
            return null
        }
        val originRecord = binding.networkFingerprintRecords.firstOrNull { record ->
            record.exactFingerprint == origin.strictFingerprint
        } ?: return null
        val current = identity.matchingContinuity(
            originRecord.continuityFingerprints,
        ) ?: return null
        if (
            !HomeNetworkPolicy.isContinuityCandidate(
                binding,
                origin.strictFingerprint,
                current.continuityFingerprints,
            )
        ) {
            return null
        }
        return HomeNetworkContinuityCandidate(
            network = current.network,
            persistedBinding = binding,
            originStrictFingerprint = origin.strictFingerprint,
            candidateStrictFingerprint = current.fingerprint,
            candidateNetworkHandle = current.networkHandle,
            identity = identity,
        )
    }

    /** Cached endpoint to be used only for the caller's bounded HAP pair-verify attempt. */
    fun continuityDevice(candidate: HomeNetworkContinuityCandidate): DiscoveredAtv =
        device(candidate.persistedBinding)

    /** Reject mismatched/corrupt stored credentials before touching the candidate LAN. */
    fun continuityDeviceMatches(
        candidate: HomeNetworkContinuityCandidate,
        deviceIdentifier: ByteArray,
    ): Boolean {
        val fingerprint = identity.deviceFingerprint(deviceIdentifier) ?: return false
        return HomeNetworkPolicy.matchesDeviceIdentity(candidate.persistedBinding, fingerprint)
    }

    /**
     * Promote a continuity candidate only after the caller has completed HAP pair-verify with the
     * stored credentials and the supplied device identifier. This method performs no HAP traffic;
     * `verifiedDevice` must describe the endpoint that the caller just authenticated.
     */
    suspend fun promoteAfterHapPairVerify(
        candidate: HomeNetworkContinuityCandidate,
        deviceIdentifier: ByteArray,
        verifiedDevice: DiscoveredAtv,
    ): HomeNetworkAuthorization? {
        if (
            verifiedDevice.name.isBlank() ||
            verifiedDevice.host.isBlank() ||
            verifiedDevice.port !in 1..65535
        ) {
            return null
        }
        if (!candidate.isStillCandidate()) return null
        val storedBinding = settingsRepository.homeNetworkBinding() ?: return null
        if (storedBinding != candidate.persistedBinding) return null
        val deviceFingerprint = identity.deviceFingerprint(deviceIdentifier) ?: return null
        if (!HomeNetworkPolicy.matchesDeviceIdentity(storedBinding, deviceFingerprint)) return null
        val current = identity.snapshot(candidate.network) ?: return null
        if (!candidate.matchesSnapshot(current)) return null
        val promotedBinding = storedBinding
            .withVerifiedNetworkVariant(
                exactFingerprint = current.fingerprint,
                continuityFingerprints = current.continuityFingerprints,
            ).copy(
                deviceName = verifiedDevice.name,
                deviceModel = verifiedDevice.model ?: storedBinding.deviceModel,
                endpointHost = verifiedDevice.host,
                endpointPort = verifiedDevice.port,
            )
        if (
            !settingsRepository.replaceHomeNetworkBindingIf(
                expected = candidate.persistedBinding,
                replacement = promotedBinding,
            )
        ) {
            return null
        }
        val verifiedCurrent = identity.snapshot(candidate.network) ?: return null
        return strictAuthorization(promotedBinding, verifiedCurrent, deviceFingerprint)
    }

    /** Bind only after HAP pair-setup or pair-verify has authenticated the TV. */
    suspend fun bind(device: DiscoveredAtv, deviceIdentifier: ByteArray): Boolean {
        val current = identity.current() ?: return false
        val deviceFingerprint = identity.deviceFingerprint(deviceIdentifier) ?: return false
        settingsRepository.setHomeNetworkBinding(
            HomeNetworkBinding(
                deviceName = device.name,
                deviceModel = device.model,
                endpointHost = device.host,
                endpointPort = device.port,
                deviceFingerprint = deviceFingerprint,
                networkFingerprint = current.fingerprint,
                continuityFingerprints = current.continuityFingerprints,
            ),
        )
        return true
    }

    /** Refresh an ephemeral Companion endpoint without changing either identity. */
    suspend fun updateEndpointIfAuthorized(
        device: DiscoveredAtv,
        deviceIdentifier: ByteArray,
    ): Boolean {
        val authorization = automaticAuthorization(deviceIdentifier) ?: return false
        return settingsRepository.replaceHomeNetworkBindingIf(
            expected = authorization.binding,
            replacement = authorization.binding.copy(
                deviceName = device.name,
                deviceModel = device.model ?: authorization.binding.deviceModel,
                endpointHost = device.host,
                endpointPort = device.port,
            ),
        )
    }

    suspend fun clearIfDeviceName(name: String) {
        settingsRepository.clearHomeNetworkBindingIf(name)
    }

    fun device(binding: HomeNetworkBinding): DiscoveredAtv = DiscoveredAtv(
        name = binding.deviceName,
        host = binding.endpointHost,
        port = binding.endpointPort,
        model = binding.deviceModel,
    )

    private suspend fun strictAuthorization(
        binding: HomeNetworkBinding,
        current: LocalNetworkSnapshot,
        expectedDeviceFingerprint: String? = null,
    ): HomeNetworkAuthorization? {
        if (
            !HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                current.fingerprint,
                expectedDeviceFingerprint,
            )
        ) {
            return null
        }
        val migrated = binding.migrateContinuityAfterStrictMatch(
            current.fingerprint,
            current.continuityFingerprints,
        )
        if (
            migrated != binding &&
            !settingsRepository.replaceHomeNetworkBindingIf(binding, migrated)
        ) {
            // Another repository instance may have completed the same legacy ->
            // v3 migration first. Re-read and accept it only if the strict
            // network and optional TV identity still match this snapshot.
            val latest = settingsRepository.homeNetworkBinding() ?: return null
            if (
                !HomeNetworkPolicy.allowsAutomaticAccess(
                    latest,
                    current.fingerprint,
                    expectedDeviceFingerprint,
                )
            ) {
                return null
            }
            return HomeNetworkAuthorization(
                latest,
                current.network,
                current.fingerprint,
                identity,
            )
        }
        return HomeNetworkAuthorization(
            migrated,
            current.network,
            current.fingerprint,
            identity,
        )
    }
}
