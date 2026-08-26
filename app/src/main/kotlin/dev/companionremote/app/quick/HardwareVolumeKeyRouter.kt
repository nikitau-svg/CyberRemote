package dev.companionremote.app.quick

/** Physical volume rocker direction after Android key-code mapping. */
internal enum class HardwareVolumeKey {
    Up,
    Down,
}

/** Whether an Activity should consume the key event and emit a remote step. */
internal data class HardwareVolumeKeyDecision(
    val consumed: Boolean,
    val dispatch: Boolean,
)

/**
 * Captures a physical volume-key gesture while the full remote is eligible.
 *
 * A captured key remains consumed until its matching key-up if command
 * eligibility changes mid-gesture. Activity focus/lease loss resets capture.
 * Repeated key-down events are throttled so holding the rocker cannot flood
 * the remote command queue.
 */
internal class HardwareVolumeKeyRouter(
    private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
) {
    private val capturedKeys = mutableSetOf<HardwareVolumeKey>()
    private val lastDispatchAtMs = mutableMapOf<HardwareVolumeKey, Long>()

    init {
        require(repeatIntervalMs >= 0L)
    }

    fun onDown(
        key: HardwareVolumeKey,
        repeatCount: Int,
        eventTimeMs: Long,
        eligible: Boolean,
    ): HardwareVolumeKeyDecision {
        val alreadyCaptured = key in capturedKeys
        if (!alreadyCaptured) {
            // Never take over a gesture whose first down happened while this
            // Activity was ineligible or did not have focus.
            if (!eligible || repeatCount != 0) return NOT_CONSUMED
            capturedKeys += key
            lastDispatchAtMs[key] = eventTimeMs
            return CONSUMED_AND_DISPATCH
        }

        if (!eligible) return CONSUMED_WITHOUT_DISPATCH
        val lastDispatch = lastDispatchAtMs[key] ?: eventTimeMs
        val elapsed = eventTimeMs - lastDispatch
        if (elapsed < repeatIntervalMs) return CONSUMED_WITHOUT_DISPATCH

        lastDispatchAtMs[key] = eventTimeMs
        return CONSUMED_AND_DISPATCH
    }

    fun onUp(key: HardwareVolumeKey): HardwareVolumeKeyDecision {
        val consumed = capturedKeys.remove(key)
        lastDispatchAtMs.remove(key)
        return if (consumed) CONSUMED_WITHOUT_DISPATCH else NOT_CONSUMED
    }

    fun reset() {
        capturedKeys.clear()
        lastDispatchAtMs.clear()
    }

    private companion object {
        const val DEFAULT_REPEAT_INTERVAL_MS = 150L
        val NOT_CONSUMED = HardwareVolumeKeyDecision(consumed = false, dispatch = false)
        val CONSUMED_WITHOUT_DISPATCH = HardwareVolumeKeyDecision(consumed = true, dispatch = false)
        val CONSUMED_AND_DISPATCH = HardwareVolumeKeyDecision(consumed = true, dispatch = true)
    }
}
