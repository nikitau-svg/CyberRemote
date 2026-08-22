package dev.companionremote.app.quick

internal enum class HomeNetworkServiceAction { None, Disconnect, Reconnect }

/** Finite retry budget: TV restarts recover, an offline TV is not polled forever. */
internal class BoundedReconnectBackoff(
    delaysMs: LongArray = longArrayOf(2_000L, 10_000L, 30_000L, 120_000L, 600_000L),
) {
    private val delays = delaysMs.copyOf()
    private var index = 0

    fun nextDelayMs(): Long? = delays.getOrNull(index)?.also { index += 1 }

    fun reset() {
        index = 0
    }
}

/** Stateful, pure transition policy. One reconnect is issued per away -> home edge. */
internal class HomeNetworkServicePolicy {
    private var lastAuthorized: Boolean? = null

    fun evaluate(
        leaseActive: Boolean,
        reconnectArmed: Boolean,
        authorized: Boolean,
        connectedOrConnecting: Boolean,
        connectionAttemptActive: Boolean,
    ): HomeNetworkServiceAction {
        if (!leaseActive) {
            lastAuthorized = null
            return HomeNetworkServiceAction.None
        }
        val previous = lastAuthorized
        lastAuthorized = authorized
        if (!authorized) {
            return if (
                previous != false ||
                connectedOrConnecting ||
                connectionAttemptActive
            ) {
                HomeNetworkServiceAction.Disconnect
            } else {
                HomeNetworkServiceAction.None
            }
        }
        if (!reconnectArmed || connectionAttemptActive || connectedOrConnecting) {
            return HomeNetworkServiceAction.None
        }
        return if (previous != true) {
            HomeNetworkServiceAction.Reconnect
        } else {
            HomeNetworkServiceAction.None
        }
    }

    fun reset() {
        lastAuthorized = null
    }
}
