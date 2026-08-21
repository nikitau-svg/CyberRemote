package dev.companionremote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.companionremote.app.data.AppSkin
import dev.companionremote.app.data.CredentialsRepository
import dev.companionremote.app.data.HapticStrength
import dev.companionremote.app.data.SettingsRepository
import dev.companionremote.app.data.ThemeMode
import dev.companionremote.app.discovery.AtvDiscovery
import dev.companionremote.app.discovery.DiscoveredAtv
import dev.companionremote.app.i18n.AppLanguage
import dev.companionremote.app.i18n.AppStrings
import dev.companionremote.app.i18n.EnglishStrings
import dev.companionremote.app.i18n.currentSystemLanguage
import dev.companionremote.app.i18n.resolveStrings
import dev.companionremote.protocol.client.HidCommand
import dev.companionremote.protocol.client.KeyboardFocusState
import dev.companionremote.protocol.client.TouchPhase
import dev.companionremote.protocol.companion.CompanionConnection
import dev.companionremote.protocol.hap.HapCredentials
import dev.companionremote.protocol.hap.PairSetup
import dev.companionremote.protocol.hap.PairVerify
import dev.companionremote.protocol.transport.SocketTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Which screen is showing. */
sealed interface Screen {
    data object DeviceList : Screen
    data object Settings : Screen
    data class Pairing(val device: DiscoveredAtv) : Screen
    data class Remote(val device: DiscoveredAtv) : Screen
}

/** Per-device pairing check triggered by the refresh button in Settings. */
enum class DeviceVerify { Idle, Checking, Ok, Failed }

data class PairingUi(
    val awaitingPin: Boolean = false,
    val working: Boolean = true,
    val error: String? = null,
)

data class DeviceListUi(
    val devices: List<DiscoveredAtv> = emptyList(),
    val pairedNames: Set<String> = emptySet(),
    val scanning: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val discovery = AtvDiscovery(application)
    private val credentialsRepository = CredentialsRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val remoteSession = RemoteSessionManager.get(application)

    val screen = MutableStateFlow<Screen>(Screen.DeviceList)
    val deviceList = MutableStateFlow(DeviceListUi())
    val pairing = MutableStateFlow(PairingUi())
    val connectionState = remoteSession.connectionState
    val connectionError = remoteSession.connectionError

    /** Language choice (persisted); drives the UI strings. */
    val language = MutableStateFlow(AppLanguage.System)

    /** Theme mode (persisted). */
    val themeMode = MutableStateFlow(ThemeMode.System)

    /** Visual skin (persisted). */
    val skin = MutableStateFlow(AppSkin.Midnight)

    /** Whether to fetch real app icons over the network (opt-in). */
    val fetchAppIcons = MutableStateFlow(false)

    /** Button-press vibration feedback (persisted). */
    val hapticEnabled = MutableStateFlow(true)
    val hapticStrength = MutableStateFlow(HapticStrength.Medium)

    /** Whether the first-run remote tutorial has already been shown. */
    val introSeen = MutableStateFlow(false)

    // Where to return when leaving Settings (device list or the remote).
    private var settingsReturnTo: Screen = Screen.DeviceList

    /** Paired device names, shown in Settings for management. */
    val pairedDevices = MutableStateFlow<List<String>>(emptyList())

    /** Name of the Apple TV currently being controlled (for "in use"). */
    val activeDeviceName = MutableStateFlow<String?>(null)

    /** Per-device pairing-check state, keyed by device name. */
    val deviceVerify = MutableStateFlow<Map<String, DeviceVerify>>(emptyMap())

    // Current strings, used for error messages produced in the ViewModel.
    private var strings: AppStrings = EnglishStrings

    /** Keyboard focus state on the TV (drives auto-open of the soft keyboard). */
    val keyboardFocus = remoteSession.keyboardFocus

    /** The phone-side edit buffer mirrored to the TV text field. */
    val keyboardText = MutableStateFlow("")

    private var pairSetup: PairSetup? = null
    private var pairingConnection: CompanionConnection? = null
    private var textSyncJob: Job? = null
    private var isForeground = false

    /** Launchable apps (bundle id → name); null until loaded. */
    val apps = MutableStateFlow<List<Pair<String, String>>?>(null)
    val appsError = MutableStateFlow<String?>(null)

    private var lastHoldSentAt = 0L

    init {
        viewModelScope.launch {
            settingsRepository.language.collect { lang ->
                language.value = lang
                strings = resolveStrings(lang, currentSystemLanguage())
            }
        }
        viewModelScope.launch {
            settingsRepository.themeMode.collect { themeMode.value = it }
        }
        viewModelScope.launch {
            settingsRepository.skin.collect { skin.value = it }
        }
        viewModelScope.launch {
            settingsRepository.fetchAppIcons.collect { fetchAppIcons.value = it }
        }
        viewModelScope.launch {
            settingsRepository.hapticEnabled.collect { hapticEnabled.value = it }
        }
        viewModelScope.launch {
            settingsRepository.hapticStrength.collect { hapticStrength.value = it }
        }
        viewModelScope.launch {
            settingsRepository.introSeen.collect { introSeen.value = it }
        }
        viewModelScope.launch {
            remoteSession.keyboardFocus.collect { state ->
                if (state == KeyboardFocusState.Focused) {
                    remoteSession.execute { client ->
                        client.textGet()?.let { keyboardText.value = it }
                    }
                }
            }
        }
        startScan()
    }

    // Settings

    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguage(lang) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setSkin(skin: AppSkin) {
        viewModelScope.launch { settingsRepository.setSkin(skin) }
    }

    fun setFetchAppIcons(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFetchAppIcons(enabled) }
    }

    fun setHapticEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHapticEnabled(enabled) }
    }

    fun setHapticStrength(strength: HapticStrength) {
        viewModelScope.launch { settingsRepository.setHapticStrength(strength) }
    }

    fun markIntroSeen() {
        viewModelScope.launch { settingsRepository.setIntroSeen(true) }
    }

    fun openSettings() {
        settingsReturnTo = screen.value
        viewModelScope.launch {
            pairedDevices.value = credentialsRepository.pairedDeviceNames().sorted()
            deviceVerify.value = emptyMap()
            screen.value = Screen.Settings
        }
    }

    /** Re-check that a paired device is reachable and its pairing still valid. */
    fun verifyDevice(name: String) {
        // The device we're actively controlling is trivially verified.
        if (name == activeDeviceName.value && connectionState.value == ConnectionState.Connected) {
            setVerify(name, DeviceVerify.Ok)
            return
        }
        viewModelScope.launch {
            setVerify(name, DeviceVerify.Checking)
            val stored = credentialsRepository.load(name)
            if (stored == null) {
                setVerify(name, DeviceVerify.Failed)
                return@launch
            }
            val creds = HapCredentials.parse(stored)
            val target = discovery.resolveByName(name)
            if (target == null) {
                setVerify(name, DeviceVerify.Failed)
                return@launch
            }
            val ok = runCatching {
                val transport = SocketTransport.connect(target.host, target.port)
                val conn = CompanionConnection(transport)
                conn.start()
                PairVerify(conn, creds).verify()
                conn.close()
                true
            }.getOrDefault(false)
            setVerify(name, if (ok) DeviceVerify.Ok else DeviceVerify.Failed)
        }
    }

    private fun setVerify(name: String, state: DeviceVerify) {
        deviceVerify.value = deviceVerify.value + (name to state)
    }

    fun closeSettings() {
        // Return to wherever Settings was opened from (device list or remote).
        val destination = settingsReturnTo
        screen.value = destination
        if (destination is Screen.Remote && isForeground) {
            remoteSession.setUiOwner(true)
            if (connectionState.value == ConnectionState.Disconnected) reconnect()
        }
    }

    fun forgetDeviceByName(name: String) {
        viewModelScope.launch {
            credentialsRepository.delete(name)
            settingsRepository.clearLastDeviceIf(name)
            pairedDevices.value = credentialsRepository.pairedDeviceNames().sorted()
            deviceList.value = deviceList.value.copy(pairedNames = deviceList.value.pairedNames - name)
        }
    }

    fun startScan() {
        if (deviceList.value.scanning) return
        viewModelScope.launch {
            val pairedNames = credentialsRepository.pairedDeviceNames().toSet()
            deviceList.value = deviceList.value.copy(scanning = true, pairedNames = pairedNames)
            runCatching {
                discovery.scan(durationMs = 6_000) { device ->
                    val current = deviceList.value
                    if (current.devices.none { it.name == device.name }) {
                        deviceList.value = current.copy(devices = current.devices + device)
                    }
                }
            }
            deviceList.value = deviceList.value.copy(scanning = false)
        }
    }

    fun addManualDevice(host: String, port: Int) {
        val device = DiscoveredAtv(name = "$host:$port", host = host, port = port, model = null)
        selectDevice(device)
    }

    fun selectDevice(device: DiscoveredAtv) {
        viewModelScope.launch {
            val stored = credentialsRepository.load(device.name)
            if (stored != null) {
                openRemote(device, HapCredentials.parse(stored))
            } else {
                beginPairing(device)
            }
        }
    }

    /** Open the most recently paired TV when launched from the shade panel. */
    fun openLastRemote() {
        viewModelScope.launch {
            val device = settingsRepository.lastDevice() ?: return@launch
            val stored = credentialsRepository.load(device.name) ?: return@launch
            openRemote(device, HapCredentials.parse(stored))
        }
    }

    fun forgetDevice(device: DiscoveredAtv) {
        viewModelScope.launch {
            credentialsRepository.delete(device.name)
            settingsRepository.clearLastDeviceIf(device.name)
            deviceList.value = deviceList.value.copy(
                pairedNames = deviceList.value.pairedNames - device.name,
            )
        }
    }

    // Pairing

    private suspend fun beginPairing(device: DiscoveredAtv) {
        screen.value = Screen.Pairing(device)
        pairing.value = PairingUi(working = true)
        try {
            val transport = SocketTransport.connect(device.host, device.port)
            val connection = CompanionConnection(transport)
            connection.start()
            pairingConnection = connection
            val setup = PairSetup(connection, name = "CyberRemote")
            setup.startPairing()
            pairSetup = setup
            pairing.value = PairingUi(awaitingPin = true, working = false)
        } catch (e: Exception) {
            pairing.value = PairingUi(working = false, error = friendlyError(e))
        }
    }

    fun submitPin(pin: String) {
        val device = (screen.value as? Screen.Pairing)?.device ?: return
        val setup = pairSetup ?: return
        viewModelScope.launch {
            pairing.value = pairing.value.copy(working = true, error = null)
            try {
                val credentials = setup.finishPairing(pin)
                credentialsRepository.save(device.name, credentials.toString())
                pairingConnection?.close()
                pairingConnection = null
                pairSetup = null
                openRemote(device, credentials)
            } catch (e: Exception) {
                pairing.value = PairingUi(working = false, error = friendlyError(e))
                pairingConnection?.close()
                pairingConnection = null
                pairSetup = null
            }
        }
    }

    fun cancelPairing() {
        pairingConnection?.close()
        pairingConnection = null
        pairSetup = null
        screen.value = Screen.DeviceList
    }

    // Remote / connection lifecycle

    private fun openRemote(device: DiscoveredAtv, credentials: HapCredentials) {
        activeDeviceName.value = device.name
        screen.value = Screen.Remote(device)
        if (isForeground) remoteSession.setUiOwner(true)
        apps.value = null
        appsError.value = null
        viewModelScope.launch { remoteSession.connect(device, credentials) }
    }

    fun reconnect() {
        viewModelScope.launch { remoteSession.reconnect() }
    }

    /** Called when the remote screen returns to the foreground. */
    fun onForeground() {
        isForeground = true
        if (screen.value is Screen.Remote) {
            remoteSession.setUiOwner(true)
            if (connectionState.value == ConnectionState.Disconnected) reconnect()
        }
    }

    /** Release a UI-only socket when the app is no longer visible. */
    fun onBackground() {
        isForeground = false
        remoteSession.setUiOwner(false)
    }

    fun closeRemote() {
        activeDeviceName.value = null
        remoteSession.setUiOwner(false)
        screen.value = Screen.DeviceList
    }

    /** Run a remote-control action, flipping to Disconnected on I/O errors. */
    fun withClient(block: suspend (dev.companionremote.protocol.client.CompanionClient) -> Unit) =
        remoteSession.launchCommand(block = block)

    fun pressButton(command: HidCommand) = withClient { it.pressButton(command) }

    fun holdButton(command: HidCommand) = withClient { it.holdButton(command) }

    // Keyboard (M6): mirror the phone's edit buffer to the TV field

    /**
     * Called on every phone-side keystroke. The whole current string is sent
     * (replace semantics) after a short debounce — the simplest reliable way
     * to keep both sides in sync.
     */
    fun onKeyboardTextChanged(text: String) {
        keyboardText.value = text
        textSyncJob?.cancel()
        textSyncJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            remoteSession.execute { it.textSet(text) }
        }
    }

    fun clearKeyboardText() {
        keyboardText.value = ""
        textSyncJob?.cancel()
        withClient { it.textClear() }
    }

    /**
     * Voice dictation result: replace the focused TV field with [text]
     * immediately (no debounce). A no-op on the TV side when nothing is
     * focused — the UI nudges the user to focus a field first.
     */
    fun dictateText(text: String) {
        if (text.isBlank()) return
        keyboardText.value = text
        textSyncJob?.cancel()
        withClient { it.textSet(text) }
    }

    // Touchpad (M7)

    /** Queue a touch event; Hold events are throttled to ~16 ms like pyatv. */
    fun sendTouch(x: Long, y: Long, phase: TouchPhase) {
        if (phase == TouchPhase.Hold) {
            val now = System.currentTimeMillis()
            if (now - lastHoldSentAt < 16) return
            lastHoldSentAt = now
        }
        remoteSession.queueTouch(x, y, phase)
    }

    fun touchTap() = withClient { it.tap() }

    // Apps (M7)

    fun loadApps(force: Boolean = false) {
        if (apps.value != null && !force) return
        appsError.value = null
        withClient { current ->
            runCatching { current.appList() }
                .onSuccess { list ->
                    apps.value = list.toList().sortedBy { it.second.lowercase() }
                }
                .onFailure { appsError.value = it.message }
        }
    }

    fun launchApp(bundleId: String) = withClient { it.launchApp(bundleId) }

    fun wake() = withClient { it.wake() }

    fun sleep() = withClient { it.sleep() }

    private fun friendlyError(e: Exception): String = when {
        e.message?.contains("proof mismatch") == true -> strings.wrongPin
        e.message?.contains("ECONNREFUSED") == true || e is java.net.ConnectException -> strings.atvUnreachable
        e is java.net.SocketTimeoutException -> strings.connectionTimedOut
        else -> e.message ?: e.javaClass.simpleName
    }

    override fun onCleared() {
        pairingConnection?.close()
        isForeground = false
        remoteSession.setUiOwner(false)
    }
}
