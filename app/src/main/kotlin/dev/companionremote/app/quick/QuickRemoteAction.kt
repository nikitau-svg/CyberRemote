package dev.companionremote.app.quick

import dev.companionremote.app.RemoteSessionManager
import dev.companionremote.protocol.client.HidCommand

/** Strict allow-list for commands accepted from notification PendingIntents. */
enum class QuickRemoteAction(val wireValue: String) {
    Up("up"),
    Down("down"),
    Left("left"),
    Right("right"),
    Select("select"),
    Back("back"),
    Home("home"),
    HomeHold("home_hold"),
    Play("play"),
    Pause("pause"),
    PlayPause("play_pause"),
    VolumeUp("volume_up"),
    VolumeDown("volume_down"),
    Wake("wake"),
    Sleep("sleep"),
    ;

    suspend fun execute(
        session: RemoteSessionManager,
        requireUnlocked: Boolean = true,
        allowReconnect: Boolean = true,
        maxAgeMs: Long? = null,
        authorizationStillValid: (() -> Boolean)? = null,
    ): Boolean = session.execute(
        requireUnlocked = requireUnlocked,
        allowReconnect = allowReconnect,
        maxAgeMs = maxAgeMs,
        lockScreenAction = if (requireUnlocked) null else this,
        authorizationStillValid = authorizationStillValid,
    ) { client ->
        when (this) {
            Up -> client.pressButton(HidCommand.Up)
            Down -> client.pressButton(HidCommand.Down)
            Left -> client.pressButton(HidCommand.Left)
            Right -> client.pressButton(HidCommand.Right)
            Select -> client.tap()
            Back -> client.pressButton(HidCommand.Menu)
            Home -> client.pressButton(HidCommand.Home)
            HomeHold -> client.holdButton(HidCommand.Home)
            Play -> client.play()
            Pause -> client.pause()
            PlayPause -> client.pressButton(HidCommand.PlayPause)
            VolumeUp -> client.pressButton(HidCommand.VolumeUp)
            VolumeDown -> client.pressButton(HidCommand.VolumeDown)
            Wake -> client.wake()
            Sleep -> client.sleep()
        }
    }

    companion object {
        fun fromWireValue(value: String?): QuickRemoteAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}
