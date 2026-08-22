package dev.companionremote.app.diagnostics

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import dev.companionremote.app.discovery.ACCESS_LOCAL_NETWORK_PERMISSION
import dev.companionremote.app.discovery.LOCAL_NETWORK_PERMISSION_SDK
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small, privacy-safe diagnostic journal intended for reports copied by the user.
 *
 * Callers must only record state names, booleans, counters, SDK levels and other
 * bounded technical values. Never pass device/user names, addresses, credentials,
 * content, capability tokens or exception messages. [exception] deliberately
 * records only the throwable class.
 */
object Diagnostics {
    private const val DIRECTORY_NAME = "diagnostics"
    private const val CURRENT_FILE_NAME = "events.log"
    private const val PREVIOUS_FILE_NAME = "events.previous.log"
    private const val MAX_FILE_BYTES = 48L * 1024L
    private const val MAX_REPORT_CHARS = 110_000
    private const val MEDIA_CHANNEL_ID = "apple_tv_native_media_controls"
    private const val MEDIA_NOTIFICATION_ID = 4207
    private const val MAX_PENDING_WRITES = 128
    private const val FLUSH_TIMEOUT_MS = 1_500L

    private val stateLock = Any()
    private val fileLock = Any()
    private val droppedEvents = AtomicInteger()
    private val writeFailures = AtomicInteger()
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(MAX_PENDING_WRITES),
        ThreadFactory { runnable ->
            Thread(runnable, "CyberRemoteDiagnostics").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        },
        RejectedExecutionHandler { task, executor ->
            // Keep diagnostics bounded and non-blocking even during an event storm.
            droppedEvents.incrementAndGet()
            executor.queue.poll()
            if (!executor.isShutdown && !executor.queue.offer(task)) {
                droppedEvents.incrementAndGet()
            }
        },
    )
    private var runtimeState = RuntimeState()

    /** Records a single bounded event. Values should be Boolean, Number, Enum, or null. */
    internal fun record(
        context: Context,
        component: String,
        event: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val appContext = context.applicationContext
        val line = buildString {
            append(timestamp(System.currentTimeMillis()))
            append(" component=")
            append(token(component))
            append(" event=")
            append(token(event))
            for ((key, value) in fields) {
                append(' ')
                append(token(key))
                append('=')
                append(safeValue(value))
            }
            append('\n')
        }

        writer.execute {
            synchronized(fileLock) {
                runCatching {
                    val directory = diagnosticsDirectory(appContext)
                    if (!directory.exists() && !directory.mkdirs()) error("diagnostics_directory")
                    val current = File(directory, CURRENT_FILE_NAME)
                    val lineBytes = line.toByteArray(StandardCharsets.UTF_8)
                    if (current.length() + lineBytes.size > MAX_FILE_BYTES) rotate(directory, current)
                    FileOutputStream(current, true).use { it.write(lineBytes) }
                }.onFailure {
                    writeFailures.incrementAndGet()
                }
            }
        }
    }

    /** Records only a throwable's class, never its potentially sensitive message. */
    internal fun exception(
        context: Context,
        component: String,
        operation: String,
        throwable: Throwable,
    ) {
        record(
            context,
            component,
            "exception",
            "operation" to DiagnosticToken(operation),
            "type" to DiagnosticToken(throwable.javaClass.simpleName.ifBlank { "Throwable" }),
        )
    }

    internal fun updateRuntimeState(
        serviceRunning: Boolean? = null,
        foregroundStarted: Boolean? = null,
        mediaSessionActive: Boolean? = null,
        playback: RuntimePlayback? = null,
        lockScreenControls: Boolean? = null,
        keyguardLocked: Boolean? = null,
        connection: DiagnosticToken? = null,
        lastStopReason: DiagnosticToken? = null,
    ) {
        synchronized(stateLock) {
            runtimeState = runtimeState.copy(
                observed = true,
                serviceRunning = serviceRunning ?: runtimeState.serviceRunning,
                foregroundStarted = foregroundStarted ?: runtimeState.foregroundStarted,
                mediaSessionActive = mediaSessionActive ?: runtimeState.mediaSessionActive,
                playback = playback ?: runtimeState.playback,
                lockScreenControls = lockScreenControls ?: runtimeState.lockScreenControls,
                keyguardLocked = keyguardLocked ?: runtimeState.keyguardLocked,
                connection = connection?.let { token(it.value) } ?: runtimeState.connection,
                lastStopReason = lastStopReason?.let { token(it.value) } ?: runtimeState.lastStopReason,
            )
        }
    }

    /** Returns a self-contained report suitable for Copy/Share in the diagnostics UI. */
    fun snapshotReport(context: Context): String {
        val appContext = context.applicationContext
        val flushed = flushPendingWrites()
        val state = synchronized(stateLock) { runtimeState }
        val (previous, current) = synchronized(fileLock) {
            val directory = diagnosticsDirectory(appContext)
            readBounded(File(directory, PREVIOUS_FILE_NAME)) to
                readBounded(File(directory, CURRENT_FILE_NAME))
        }
        return buildString {
            appendLine("CyberRemote diagnostics")
            appendLine("Generated: ${timestamp(System.currentTimeMillis())}")
            appendLine("App: ${appVersion(appContext)}")
            appendLine("Device: ${token(Build.MANUFACTURER)} ${token(Build.MODEL)}")
            appendLine("Android: ${token(Build.VERSION.RELEASE)} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Local network permission: ${localNetworkPermission(appContext)}")
            appendLine("Notification permission: ${notificationPermission(appContext)}")
            appendLine("Microphone permission: ${microphonePermission(appContext)}")
            appendLine("App notifications: ${appNotificationState(appContext)}")
            appendLine("Media channel: ${mediaChannelState(appContext)}")
            appendLine("Media notification active: ${mediaNotificationState(appContext)}")
            appendLine("Keyguard: ${keyguardState(appContext)}")
            appendLine("Battery optimization: ${batteryOptimizationState(appContext)}")
            appendLine("Runtime: ${state.summary()}")
            appendLine(
                "Diagnostic writer: flushed=$flushed; dropped=${droppedEvents.get()}; " +
                    "write_failures=${writeFailures.get()}",
            )
            appendLine("Privacy: no TV names, network addresses, credentials, user content, tokens, or exception messages are collected.")
            appendLine("Review note: the report includes the phone model, permission states, UTC action times, lock state, and remote button types.")
            appendLine()
            appendLine("Events (oldest first):")

            if (previous.isEmpty() && current.isEmpty()) {
                appendLine("No diagnostic events recorded.")
            } else {
                append(previous)
                if (previous.isNotEmpty() && !previous.endsWith('\n')) appendLine()
                append(current)
                if (current.isNotEmpty() && !current.endsWith('\n')) appendLine()
            }
        }.takeLastPreservingHeader(MAX_REPORT_CHARS)
    }

    /** Clears both generations of the local diagnostic journal. */
    fun clear(context: Context): Boolean {
        val flushed = flushPendingWrites()
        val filesCleared = synchronized(fileLock) {
            val directory = diagnosticsDirectory(context)
            val currentCleared = erase(File(directory, CURRENT_FILE_NAME))
            val previousCleared = erase(File(directory, PREVIOUS_FILE_NAME))
            runCatching { directory.delete() }
            currentCleared && previousCleared
        }
        synchronized(stateLock) {
            runtimeState = RuntimeState()
        }
        droppedEvents.set(0)
        writeFailures.set(0)
        return flushed && filesCleared
    }

    /** Explicit wrapper for developer-owned, non-user string constants. */
    internal data class DiagnosticToken(val value: String)

    internal enum class RuntimePlayback { Unknown, Paused, Playing, Released }

    private data class RuntimeState(
        val observed: Boolean = false,
        val serviceRunning: Boolean? = null,
        val foregroundStarted: Boolean? = null,
        val mediaSessionActive: Boolean? = null,
        val playback: RuntimePlayback = RuntimePlayback.Unknown,
        val lockScreenControls: Boolean? = null,
        val keyguardLocked: Boolean? = null,
        val connection: String = "unknown",
        val lastStopReason: String = "none",
    ) {
        fun summary(): String = if (!observed) {
            "not_observed_in_this_process"
        } else {
            "service_running=${serviceRunning ?: "unknown"}; " +
                "foreground_started=${foregroundStarted ?: "unknown"}; " +
                "media_session_active=${mediaSessionActive ?: "unknown"}; " +
                "playback=${playback.name.lowercase(Locale.US)}; " +
                "lock_screen_controls=${lockScreenControls ?: "unknown"}; " +
                "keyguard_locked=${keyguardLocked ?: "unknown"}; connection=$connection; " +
                "last_stop_reason=$lastStopReason"
        }
    }

    private fun diagnosticsDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    private fun rotate(directory: File, current: File) {
        val previous = File(directory, PREVIOUS_FILE_NAME)
        if (previous.exists()) previous.delete()
        if (current.exists() && !current.renameTo(previous)) current.delete()
    }

    private fun erase(file: File): Boolean {
        if (!file.exists()) return true
        return runCatching {
            FileOutputStream(file, false).use { }
            file.delete() || file.length() == 0L
        }.getOrDefault(false)
    }

    private fun flushPendingWrites(): Boolean = runCatching {
        writer.submit(Runnable { }).get(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        true
    }.getOrDefault(false)

    private fun readBounded(file: File): String = runCatching {
        if (!file.isFile) return@runCatching ""
        val bytes = file.readBytes()
        val start = (bytes.size - MAX_FILE_BYTES.toInt()).coerceAtLeast(0)
        val decoded = String(bytes, start, bytes.size - start, StandardCharsets.UTF_8)
        if (start > 0) decoded.substringAfter('\n', missingDelimiterValue = "") else decoded
    }.getOrDefault("")

    private fun safeValue(value: Any?): String = when (value) {
        null -> "none"
        is Boolean, is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> if (value.isFinite()) value.toString() else "invalid_number"
        is Double -> if (value.isFinite()) value.toString() else "invalid_number"
        is Enum<*> -> token(value.name)
        is DiagnosticToken -> token(value.value)
        else -> "redacted_${token(value.javaClass.simpleName)}"
    }

    private fun token(value: String): String = buildString {
        for (character in value.take(64)) {
            append(
                when {
                    character.isLetterOrDigit() -> character
                    character == '_' || character == '-' || character == '.' -> character
                    else -> '_'
                },
            )
        }
        if (isEmpty()) append("unknown")
    }

    private fun timestamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))

    private fun appVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${token(info.versionName ?: "unknown")} ($code)"
    }.getOrDefault("unknown")

    private fun notificationPermission(context: Context): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            "not_required"
        } else if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "denied"
        }

    private fun localNetworkPermission(context: Context): String =
        if (Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_SDK) {
            "not_required"
        } else if (
            context.checkSelfPermission(ACCESS_LOCAL_NETWORK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            "granted"
        } else {
            "denied"
        }

    private fun microphonePermission(context: Context): String =
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            "granted"
        } else {
            "denied"
        }

    private fun appNotificationState(context: Context): String = runCatching {
        if (context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            "enabled"
        } else {
            "blocked"
        }
    }.getOrDefault("unavailable")

    private fun mediaChannelState(context: Context): String = runCatching {
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(MEDIA_CHANNEL_ID)
            ?: return@runCatching "missing"
        val enabled = channel.importance != NotificationManager.IMPORTANCE_NONE
        "${if (enabled) "enabled" else "blocked"}; importance=${channel.importance}; lockscreen_visibility=${channel.lockscreenVisibility}"
    }.getOrDefault("unavailable")

    private fun mediaNotificationState(context: Context): String = runCatching {
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
        val status = active.firstOrNull { it.id == MEDIA_NOTIFICATION_ID }
            ?: return@runCatching "missing; own_active_count=${active.size}"
        val notification = status.notification
        val ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        val foreground = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val mediaToken = notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        "present; channel=${token(notification.channelId ?: "none")}; " +
            "category=${token(notification.category ?: "none")}; visibility=${notification.visibility}; " +
            "ongoing=$ongoing; foreground_service=$foreground; media_token=$mediaToken; " +
            "actions=${notification.actions?.size ?: 0}; own_active_count=${active.size}"
    }.getOrDefault("unavailable (id=$MEDIA_NOTIFICATION_ID)")

    private fun keyguardState(context: Context): String = runCatching {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        "locked=${keyguard.isKeyguardLocked}; device_locked=${keyguard.isDeviceLocked}"
    }.getOrDefault("unavailable")

    private fun batteryOptimizationState(context: Context): String = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(context.packageName)) "exempt" else "optimized"
    }.getOrDefault("unavailable")

    private fun String.takeLastPreservingHeader(maxChars: Int): String {
        if (length <= maxChars) return this
        val eventsMarker = "Events (oldest first):\n"
        val markerIndex = indexOf(eventsMarker)
        if (markerIndex < 0) return takeLast(maxChars)
        val headerEnd = markerIndex + eventsMarker.length
        val header = substring(0, headerEnd)
        val remaining = (maxChars - header.length - 28).coerceAtLeast(0)
        return header + "[older events truncated]\n" + takeLast(remaining).substringAfter('\n')
    }
}
