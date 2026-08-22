package dev.companionremote.app.data

import android.content.Context
import dev.companionremote.app.discovery.DiscoveredAtv
import dev.companionremote.app.discovery.LocalNetworkIdentity

/** A short-lived capability proving that automatic LAN access is allowed. */
internal class HomeNetworkAuthorization(
    val binding: HomeNetworkBinding,
    private val networkHandle: Long,
    private val identity: LocalNetworkIdentity,
) {
    fun isStillValid(): Boolean = identity.currentNetworkHandle() == networkHandle
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
        val current = identity.current() ?: return null
        if (!HomeNetworkPolicy.allowsAutomaticAccess(binding, current.fingerprint)) return null
        return HomeNetworkAuthorization(binding, current.networkHandle, identity)
    }

    suspend fun automaticAuthorization(deviceIdentifier: ByteArray): HomeNetworkAuthorization? {
        val binding = settingsRepository.homeNetworkBinding() ?: return null
        val current = identity.current() ?: return null
        val deviceFingerprint = identity.deviceFingerprint(deviceIdentifier) ?: return null
        if (
            !HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                current.fingerprint,
                deviceFingerprint,
            )
        ) {
            return null
        }
        return HomeNetworkAuthorization(binding, current.networkHandle, identity)
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
        settingsRepository.setHomeNetworkBinding(
            authorization.binding.copy(
                deviceName = device.name,
                deviceModel = device.model ?: authorization.binding.deviceModel,
                endpointHost = device.host,
                endpointPort = device.port,
            ),
        )
        return true
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
}
