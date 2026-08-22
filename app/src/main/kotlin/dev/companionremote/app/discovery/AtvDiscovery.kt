package dev.companionremote.app.discovery

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import dev.companionremote.app.diagnostics.Diagnostics
import dev.companionremote.app.diagnostics.Diagnostics.DiagnosticToken
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveredAtv(
    val name: String,
    val host: String,
    val port: Int,
    val model: String?,
)

internal enum class NsdDiscoveryMode {
    Legacy,
    NetworkScoped,
    UnsupportedScoped,
}

/**
 * A scoped automatic browse must never silently fall back to all networks.
 * Android only exposes the Network-specific NSD overload from API 33.
 */
internal fun nsdDiscoveryMode(networkRequested: Boolean, sdkInt: Int): NsdDiscoveryMode = when {
    !networkRequested -> NsdDiscoveryMode.Legacy
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> NsdDiscoveryMode.NetworkScoped
    else -> NsdDiscoveryMode.UnsupportedScoped
}

/**
 * mDNS discovery of `_companion-link._tcp` services via NsdManager.
 * A WifiManager.MulticastLock is held while discovering — without it,
 * discovery silently returns nothing on most devices.
 */
class AtvDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Browse for [durationMs] and return every resolved Apple TV.
     * Resolution happens sequentially (NsdManager allows one resolve at a
     * time on older Android versions).
     */
    suspend fun scan(
        durationMs: Long = 6_000,
        network: Network? = null,
        authorizationStillValid: () -> Boolean = { true },
        serviceNameFilter: (String) -> Boolean = { true },
        onDevice: (DiscoveredAtv) -> Unit,
    ) {
        val discoveryMode = nsdDiscoveryMode(
            networkRequested = network != null,
            sdkInt = Build.VERSION.SDK_INT,
        )
        Diagnostics.record(
            appContext,
            "discovery",
            "scan_entered",
            "mode" to discoveryMode,
            "sdk" to Build.VERSION.SDK_INT,
        )
        // On Android 12L and below, NSD cannot be constrained to a specific
        // Network. Automatic discovery therefore fails closed instead of
        // leaking a browse onto another attached LAN. A null network is an
        // explicit/user-initiated browse and may use the legacy API.
        if (discoveryMode == NsdDiscoveryMode.UnsupportedScoped) {
            Diagnostics.record(
                appContext,
                "discovery",
                "scan_skipped",
                "reason" to DiagnosticToken("unsupported_scoped"),
            )
            return
        }
        // Check before acquiring MulticastLock so an unauthorized automatic
        // browse has no radio or battery side effect.
        if (!authorizationStillValid()) {
            Diagnostics.record(
                appContext,
                "discovery",
                "scan_skipped",
                "reason" to DiagnosticToken("authorization"),
            )
            return
        }
        val multicastLock = wifiManager.createMulticastLock("companion-remote-discovery")
        try {
            multicastLock.setReferenceCounted(false)
            multicastLock.acquire()
            Diagnostics.record(appContext, "discovery", "multicast_lock_acquired")
        } catch (error: RuntimeException) {
            Diagnostics.exception(appContext, "discovery", "multicast_lock", error)
            return
        }
        val found = LinkedHashMap<String, NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Diagnostics.record(appContext, "discovery", "start_failed", "code" to errorCode)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Diagnostics.record(appContext, "discovery", "stop_failed", "code" to errorCode)
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Diagnostics.record(appContext, "discovery", "started")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Diagnostics.record(appContext, "discovery", "stopped")
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Diagnostics.record(appContext, "discovery", "service_lost")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val accepted =
                    authorizationStillValid() &&
                    serviceNameFilter(serviceInfo.serviceName)
                Diagnostics.record(appContext, "discovery", "service_found", "accepted" to accepted)
                if (accepted) {
                    synchronized(found) { found[serviceInfo.serviceName] = serviceInfo }
                }
            }
        }

        try {
            when (discoveryMode) {
                NsdDiscoveryMode.Legacy -> nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )

                NsdDiscoveryMode.NetworkScoped -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
                    nsdManager.discoverServices(
                        SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        checkNotNull(network),
                        appContext.mainExecutor,
                        listener,
                    )
                }

                NsdDiscoveryMode.UnsupportedScoped -> return
            }
            val deadline = System.currentTimeMillis() + durationMs
            val resolved = mutableSetOf<String>()
            while (System.currentTimeMillis() < deadline && authorizationStillValid()) {
                val pending = synchronized(found) {
                    found.entries.firstOrNull { it.key !in resolved }
                }
                if (pending == null) {
                    kotlinx.coroutines.delay(200)
                    continue
                }
                resolved.add(pending.key)
                if (!authorizationStillValid()) break
                resolve(pending.value)?.takeIf { authorizationStillValid() }?.let(onDevice)
            }
            Diagnostics.record(
                appContext,
                "discovery",
                "scan_finished",
                "found" to synchronized(found) { found.size },
                "resolved" to resolved.size,
            )
        } finally {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            runCatching { multicastLock.release() }
        }
    }

    /**
     * Re-resolve the current host/port for a service by name, returning as
     * soon as it is found. The Companion port is ephemeral (changes after
     * reboot) so this runs on every connect.
     */
    suspend fun resolveByName(
        name: String,
        timeoutMs: Long = 6_000,
        network: Network? = null,
        authorizationStillValid: () -> Boolean = { true },
    ): DiscoveredAtv? =
        withTimeoutOrNull(timeoutMs) {
            if (!authorizationStillValid()) return@withTimeoutOrNull null
            coroutineScope {
                val found = CompletableDeferred<DiscoveredAtv>()
                val scanJob = async {
                    scan(
                        durationMs = timeoutMs,
                        network = network,
                        authorizationStillValid = authorizationStillValid,
                        serviceNameFilter = { it == name },
                    ) { device ->
                        if (
                            authorizationStillValid() &&
                            device.name == name &&
                            !found.isCompleted
                        ) {
                            found.complete(device)
                        }
                    }
                }
                val device = select {
                    found.onAwait { it }
                    scanJob.onAwait { null }
                }
                scanJob.cancel()
                device
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredAtv? =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { continuation ->
                nsdManager.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Diagnostics.record(appContext, "discovery", "resolve_failed", "code" to errorCode)
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress
                            if (host == null) {
                                Diagnostics.record(appContext, "discovery", "resolved", "accepted" to false)
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }
                            val model = serviceInfo.attributes["rpMd"]?.toString(Charsets.UTF_8)
                            val flags = serviceInfo.attributes["rpFl"]?.toString(Charsets.UTF_8)
                                ?.removePrefix("0x")?.toIntOrNull(16) ?: 0
                            // The `_companion-link._tcp` service is also
                            // advertised by Macs and HomePods. Keep only Apple
                            // TVs: model starts with "AppleTV", or (model
                            // missing) the rpFl PIN-pairable bit is set — Macs
                            // and HomePods do not set it.
                            val isAppleTv = model?.startsWith("AppleTV") == true ||
                                (model.isNullOrEmpty() && (flags and PAIRABLE_MASK) != 0)
                            if (!isAppleTv) {
                                Diagnostics.record(appContext, "discovery", "resolved", "accepted" to false)
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }
                            if (continuation.isActive) {
                                Diagnostics.record(appContext, "discovery", "resolved", "accepted" to true)
                                continuation.resume(
                                    DiscoveredAtv(
                                        name = serviceInfo.serviceName,
                                        host = host,
                                        port = serviceInfo.port,
                                        model = model,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
        }

    companion object {
        const val SERVICE_TYPE = "_companion-link._tcp."

        // pyatv PAIRING_WITH_PIN_SUPPORTED_MASK — set on Apple TVs, not on
        // Macs/HomePods (pyatv/protocols/companion/__init__.py).
        private const val PAIRABLE_MASK = 0x4000
    }
}
