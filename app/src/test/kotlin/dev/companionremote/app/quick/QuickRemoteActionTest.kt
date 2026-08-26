package dev.companionremote.app.quick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuickRemoteActionTest {

    @Test
    fun `wire values are unique and seek actions round trip`() {
        assertEquals(
            QuickRemoteAction.entries.size,
            QuickRemoteAction.entries.map { it.wireValue }.toSet().size,
        )
        assertEquals(
            QuickRemoteAction.SkipBack15,
            QuickRemoteAction.fromWireValue("skip_back_15"),
        )
        assertEquals(
            QuickRemoteAction.SkipForward15,
            QuickRemoteAction.fromWireValue("skip_forward_15"),
        )
    }
}
