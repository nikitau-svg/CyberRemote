package dev.companionremote.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.companionremote.app.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "cyberremote_settings")

/** Light/dark theme preference. */
enum class ThemeMode { System, Light, Dark }

/** Visual skin (accent + glass palette). Orthogonal to light/dark. */
enum class AppSkin { Midnight, Graphite, Aurora, Sunset }

/** Haptic (vibration) strength for button feedback. */
enum class HapticStrength { Light, Medium, Strong }

/** Persists app-level preferences (language, theme, skin, haptics, …). */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val languageKey = stringPreferencesKey("language")
    private val themeKey = stringPreferencesKey("theme")
    private val skinKey = stringPreferencesKey("skin")
    private val fetchIconsKey = booleanPreferencesKey("fetch_app_icons")
    private val hapticEnabledKey = booleanPreferencesKey("haptic_enabled")
    private val hapticStrengthKey = stringPreferencesKey("haptic_strength")
    private val introSeenKey = booleanPreferencesKey("intro_seen")
    private val lockScreenControlsKey = booleanPreferencesKey("lock_screen_controls")
    private val homeNetworkBindingKey = stringPreferencesKey("home_network_binding_v1")

    // Removed once a secure home binding is written. They are intentionally
    // never used for automatic connection because they predate LAN binding.
    private val lastDeviceNameKey = stringPreferencesKey("last_device_name")
    private val lastDeviceHostKey = stringPreferencesKey("last_device_host")
    private val lastDevicePortKey = intPreferencesKey("last_device_port")

    val language: Flow<AppLanguage> = appContext.settingsDataStore.data.map { prefs ->
        when (prefs[languageKey]) {
            "en" -> AppLanguage.English
            "zh" -> AppLanguage.Chinese
            else -> AppLanguage.System
        }
    }

    val themeMode: Flow<ThemeMode> = appContext.settingsDataStore.data.map { prefs ->
        when (prefs[themeKey]) {
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        }
    }

    val skin: Flow<AppSkin> = appContext.settingsDataStore.data.map { prefs ->
        when (prefs[skinKey]) {
            "graphite" -> AppSkin.Graphite
            "aurora" -> AppSkin.Aurora
            "sunset" -> AppSkin.Sunset
            else -> AppSkin.Midnight
        }
    }

    /** Whether to fetch real app icons over the network (default off). */
    val fetchAppIcons: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[fetchIconsKey] ?: false
    }

    /** Vibrate on button presses (default on). */
    val hapticEnabled: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[hapticEnabledKey] ?: true
    }

    val hapticStrength: Flow<HapticStrength> = appContext.settingsDataStore.data.map { prefs ->
        when (prefs[hapticStrengthKey]) {
            "light" -> HapticStrength.Light
            "strong" -> HapticStrength.Strong
            else -> HapticStrength.Medium
        }
    }

    /** Whether the first-run remote tutorial has been shown. */
    val introSeen: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[introSeenKey] ?: false
    }

    /** Explicit opt-in for a small, non-destructive command set on keyguard. */
    val lockScreenControls: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[lockScreenControlsKey] ?: false
    }

    suspend fun setLanguage(language: AppLanguage) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[languageKey] = when (language) {
                AppLanguage.English -> "en"
                AppLanguage.Chinese -> "zh"
                AppLanguage.System -> "system"
            }
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[themeKey] = when (mode) {
                ThemeMode.Light -> "light"
                ThemeMode.Dark -> "dark"
                ThemeMode.System -> "system"
            }
        }
    }

    suspend fun setSkin(skin: AppSkin) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[skinKey] = when (skin) {
                AppSkin.Midnight -> "midnight"
                AppSkin.Graphite -> "graphite"
                AppSkin.Aurora -> "aurora"
                AppSkin.Sunset -> "sunset"
            }
        }
    }

    suspend fun setFetchAppIcons(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs -> prefs[fetchIconsKey] = enabled }
    }

    suspend fun setHapticEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs -> prefs[hapticEnabledKey] = enabled }
    }

    suspend fun setHapticStrength(strength: HapticStrength) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[hapticStrengthKey] = when (strength) {
                HapticStrength.Light -> "light"
                HapticStrength.Medium -> "medium"
                HapticStrength.Strong -> "strong"
            }
        }
    }

    suspend fun setIntroSeen(seen: Boolean) {
        appContext.settingsDataStore.edit { prefs -> prefs[introSeenKey] = seen }
    }

    suspend fun setLockScreenControls(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs -> prefs[lockScreenControlsKey] = enabled }
    }

    /** Encrypted endpoint plus keyed LAN/device fingerprints. */
    suspend fun homeNetworkBinding(): HomeNetworkBinding? {
        val prefs = appContext.settingsDataStore.data.first()
        val wrapped = prefs[homeNetworkBindingKey]
        if (wrapped == null) {
            // A pre-binding build stored this endpoint in plaintext. It is not
            // safe input for automatic access and is no longer needed: the
            // user can explicitly refresh once to authenticate and bind it.
            if (
                prefs[lastDeviceNameKey] != null ||
                prefs[lastDeviceHostKey] != null ||
                prefs[lastDevicePortKey] != null
            ) {
                appContext.settingsDataStore.edit { mutable ->
                    mutable.remove(lastDeviceNameKey)
                    mutable.remove(lastDeviceHostKey)
                    mutable.remove(lastDevicePortKey)
                }
            }
            return null
        }
        val encoded = KeystoreCrypto.decrypt(wrapped) ?: return null
        return HomeNetworkBindingCodec.decode(encoded)
    }

    suspend fun setHomeNetworkBinding(binding: HomeNetworkBinding) {
        val wrapped = KeystoreCrypto.encrypt(HomeNetworkBindingCodec.encode(binding))
        appContext.settingsDataStore.edit { prefs ->
            prefs[homeNetworkBindingKey] = wrapped
            // Delete the legacy plaintext endpoint as soon as a secure binding
            // is established.
            prefs.remove(lastDeviceNameKey)
            prefs.remove(lastDeviceHostKey)
            prefs.remove(lastDevicePortKey)
        }
    }

    suspend fun clearHomeNetworkBindingIf(name: String) {
        appContext.settingsDataStore.edit { prefs ->
            val bindingName = prefs[homeNetworkBindingKey]
                ?.let(KeystoreCrypto::decrypt)
                ?.let(HomeNetworkBindingCodec::decode)
                ?.deviceName
            if (bindingName == name) prefs.remove(homeNetworkBindingKey)
            if (prefs[lastDeviceNameKey] == name) {
                prefs.remove(lastDeviceNameKey)
                prefs.remove(lastDeviceHostKey)
                prefs.remove(lastDevicePortKey)
            }
        }
    }
}
