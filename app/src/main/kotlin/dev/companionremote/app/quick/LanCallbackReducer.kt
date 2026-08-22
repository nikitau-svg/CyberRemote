package dev.companionremote.app.quick

/** Only capability fields that can change whether a Network is a usable physical LAN. */
internal data class LanCapsKey(
    val notVpn: Boolean,
    val wifi: Boolean,
    val ethernet: Boolean,
) {
    val eligible: Boolean get() = notVpn && (wifi || ethernet)
}

/** Only LinkProperties fields used by the persisted private LAN fingerprint. */
internal data class LanLinkKey(
    val prefixes: Set<String>,
    val defaultGateways: Set<String>,
    val dnsServers: Set<String>,
) {
    val complete: Boolean
        get() = prefixes.isNotEmpty() && (defaultGateways.isNotEmpty() || dnsServers.isNotEmpty())
}

internal enum class LanCallbackAction {
    Ignore,
    Revalidate,
    AuthorizedLoss,
}

internal enum class LanAuthorizationCommit {
    Arrival,
    Stable,
    Handover,
}

/**
 * Removes Samsung/Android callback noise before it reaches DataStore, Keystore or reconnect logic.
 * Signal strength, validation, bandwidth and callback object identity are deliberately absent.
 */
internal class NetworkCallbackRevalidationGate {
    private data class Entry(
        var caps: LanCapsKey? = null,
        var links: LanLinkKey? = null,
    )

    private val entries = mutableMapOf<Long, Entry>()
    private var committedHandle: Long? = null
    private var lossEmittedForHandle: Long? = null

    fun onAvailable(networkHandle: Long): LanCallbackAction {
        entries.getOrPut(networkHandle) { Entry() }
        return LanCallbackAction.Ignore
    }

    fun onCapabilitiesChanged(
        networkHandle: Long,
        key: LanCapsKey,
    ): LanCallbackAction {
        val entry = entries.getOrPut(networkHandle) { Entry() }
        if (entry.caps == key) return LanCallbackAction.Ignore
        entry.caps = key
        if (networkHandle == committedHandle && !key.eligible) return emitAuthorizedLoss(networkHandle)
        return if (key.eligible && entry.links?.complete == true) {
            LanCallbackAction.Revalidate
        } else {
            LanCallbackAction.Ignore
        }
    }

    fun onLinkPropertiesChanged(
        networkHandle: Long,
        key: LanLinkKey,
    ): LanCallbackAction {
        val entry = entries.getOrPut(networkHandle) { Entry() }
        if (entry.links == key) return LanCallbackAction.Ignore
        entry.links = key
        if (networkHandle == committedHandle && !key.complete) return emitAuthorizedLoss(networkHandle)
        return if (key.complete && entry.caps?.eligible == true) {
            LanCallbackAction.Revalidate
        } else {
            LanCallbackAction.Ignore
        }
    }

    fun onLost(networkHandle: Long): LanCallbackAction {
        entries.remove(networkHandle)
        return if (networkHandle == committedHandle) {
            emitAuthorizedLoss(networkHandle)
        } else {
            LanCallbackAction.Ignore
        }
    }

    fun commitAuthorization(networkHandle: Long): LanAuthorizationCommit {
        val previous = committedHandle
        committedHandle = networkHandle
        lossEmittedForHandle = null
        entries.getOrPut(networkHandle) { Entry() }
        return when {
            previous == null -> LanAuthorizationCommit.Arrival
            previous == networkHandle -> LanAuthorizationCommit.Stable
            else -> LanAuthorizationCommit.Handover
        }
    }

    fun clearAuthorization() {
        committedHandle = null
        lossEmittedForHandle = null
    }

    fun reset() {
        entries.clear()
        clearAuthorization()
    }

    private fun emitAuthorizedLoss(networkHandle: Long): LanCallbackAction {
        if (lossEmittedForHandle == networkHandle) return LanCallbackAction.Ignore
        lossEmittedForHandle = networkHandle
        return LanCallbackAction.AuthorizedLoss
    }
}
