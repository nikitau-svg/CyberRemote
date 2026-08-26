package dev.companionremote.app.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LockScreenControlsConsentTest {

    @Test
    fun `legacy consent is disabled until the current policy is accepted`() {
        assertFalse(hasCurrentLockScreenControlsConsent(enabled = true, policyVersion = null))
        assertFalse(hasCurrentLockScreenControlsConsent(enabled = true, policyVersion = 1))
        assertFalse(
            hasCurrentLockScreenControlsConsent(
                enabled = false,
                policyVersion = LOCK_SCREEN_CONTROLS_POLICY_VERSION,
            ),
        )
        assertTrue(
            hasCurrentLockScreenControlsConsent(
                enabled = true,
                policyVersion = LOCK_SCREEN_CONTROLS_POLICY_VERSION,
            ),
        )
    }
}
