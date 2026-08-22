package dev.companionremote.protocol.mrp

import dev.companionremote.protocol.mrp.proto.Command
import dev.companionremote.protocol.mrp.proto.SendCommandMessageOuterClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrpMessagesTest {
    @Test
    fun `registered proto2 extension survives command round trip`() {
        val encoded = MrpMessages.command(Command.Play).toByteArray()
        val decoded = MrpMessages.parse(encoded)

        assertTrue(decoded.hasExtension(SendCommandMessageOuterClass.sendCommandMessage))
        assertEquals(
            Command.Play,
            decoded.getExtension(SendCommandMessageOuterClass.sendCommandMessage).command,
        )
    }

    @Test
    fun `AirPlay feature words are joined upper then lower`() {
        assertEquals(
            0x89ABCDEF12345678uL,
            MrpCapabilityProbe.parseAirPlayFeatures("0x12345678,0x89abcdef"),
        )
    }
}
