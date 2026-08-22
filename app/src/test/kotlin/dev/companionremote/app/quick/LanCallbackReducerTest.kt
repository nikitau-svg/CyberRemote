package dev.companionremote.app.quick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LanCallbackReducerTest {
    private val eligibleCaps = LanCapsKey(notVpn = true, wifi = true, ethernet = false)
    private val homeLinks = LanLinkKey(
        prefixes = setOf("4:c0a80100/24"),
        defaultGateways = setOf("4:c0a80101"),
        dnsServers = setOf("4:c0a80101"),
    )

    @Test
    fun `available waits for normalized capabilities and links`() {
        val gate = NetworkCallbackRevalidationGate()

        assertEquals(LanCallbackAction.Ignore, gate.onAvailable(1L))
        assertEquals(LanCallbackAction.Ignore, gate.onCapabilitiesChanged(1L, eligibleCaps))
        assertEquals(LanCallbackAction.Revalidate, gate.onLinkPropertiesChanged(1L, homeLinks))
    }

    @Test
    fun `duplicate Samsung callbacks are ignored`() {
        val gate = NetworkCallbackRevalidationGate()
        gate.onCapabilitiesChanged(1L, eligibleCaps)
        gate.onLinkPropertiesChanged(1L, homeLinks)

        repeat(100) {
            assertEquals(LanCallbackAction.Ignore, gate.onCapabilitiesChanged(1L, eligibleCaps))
            assertEquals(LanCallbackAction.Ignore, gate.onLinkPropertiesChanged(1L, homeLinks))
        }
    }

    @Test
    fun `set ordering does not create a false topology change`() {
        val gate = NetworkCallbackRevalidationGate()
        gate.onCapabilitiesChanged(1L, eligibleCaps)
        gate.onLinkPropertiesChanged(
            1L,
            homeLinks.copy(dnsServers = linkedSetOf("4:01010101", "4:08080808")),
        )

        assertEquals(
            LanCallbackAction.Ignore,
            gate.onLinkPropertiesChanged(
                1L,
                homeLinks.copy(dnsServers = linkedSetOf("4:08080808", "4:01010101")),
            ),
        )
    }

    @Test
    fun `real link change is revalidated once`() {
        val gate = NetworkCallbackRevalidationGate()
        gate.onCapabilitiesChanged(1L, eligibleCaps)
        gate.onLinkPropertiesChanged(1L, homeLinks)
        val changed = homeLinks.copy(defaultGateways = setOf("4:c0a801fe"))

        assertEquals(LanCallbackAction.Revalidate, gate.onLinkPropertiesChanged(1L, changed))
        assertEquals(LanCallbackAction.Ignore, gate.onLinkPropertiesChanged(1L, changed))
    }

    @Test
    fun `authorized loss and duplicate lost are emitted once`() {
        val gate = NetworkCallbackRevalidationGate()
        gate.commitAuthorization(7L)

        assertEquals(LanCallbackAction.AuthorizedLoss, gate.onLost(7L))
        assertEquals(LanCallbackAction.Ignore, gate.onLost(7L))
        assertEquals(LanCallbackAction.Ignore, gate.onLost(8L))
    }

    @Test
    fun `authorization commit distinguishes stable arrival and handover`() {
        val gate = NetworkCallbackRevalidationGate()

        assertEquals(LanAuthorizationCommit.Arrival, gate.commitAuthorization(1L))
        assertEquals(LanAuthorizationCommit.Stable, gate.commitAuthorization(1L))
        assertEquals(LanAuthorizationCommit.Handover, gate.commitAuthorization(2L))
    }
}
