package dev.companionremote.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/**
 * The one Apple TV selected for automatic reconnects.
 *
 * The endpoint is kept so the common path can connect without an mDNS browse.
 * The whole value is encrypted before it is put in DataStore. Both identities
 * are keyed fingerprints; neither the LAN topology nor the HAP identifier is
 * persisted in plaintext.
 */
data class HomeNetworkBinding(
    val deviceName: String,
    val deviceModel: String?,
    val endpointHost: String,
    val endpointPort: Int,
    val deviceFingerprint: String,
    val networkFingerprint: String,
)

/** Pure policy kept separate from Android so the security boundary is testable. */
internal object HomeNetworkPolicy {
    fun allowsAutomaticAccess(
        binding: HomeNetworkBinding,
        currentNetworkFingerprint: String?,
        expectedDeviceFingerprint: String? = null,
    ): Boolean =
        currentNetworkFingerprint != null &&
            constantTimeEquals(binding.networkFingerprint, currentNetworkFingerprint) &&
            (
                expectedDeviceFingerprint == null ||
                    constantTimeEquals(binding.deviceFingerprint, expectedDeviceFingerprint)
                )

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        var difference = leftBytes.size xor rightBytes.size
        val size = maxOf(leftBytes.size, rightBytes.size)
        for (index in 0 until size) {
            val leftByte = if (index < leftBytes.size) leftBytes[index].toInt() else 0
            val rightByte = if (index < rightBytes.size) rightBytes[index].toInt() else 0
            difference = difference or (leftByte xor rightByte)
        }
        return difference == 0
    }
}

/** Versioned binary codec. The resulting string is encrypted by SettingsRepository. */
internal object HomeNetworkBindingCodec {
    private const val VERSION = 1
    private const val MAX_FIELD_BYTES = 64 * 1024

    fun encode(binding: HomeNetworkBinding): String {
        require(binding.endpointPort in 1..65535) { "invalid endpoint port" }
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(VERSION)
                output.writeField(binding.deviceName)
                output.writeNullableField(binding.deviceModel)
                output.writeField(binding.endpointHost)
                output.writeInt(binding.endpointPort)
                output.writeField(binding.deviceFingerprint)
                output.writeField(binding.networkFingerprint)
            }
            buffer.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(encoded: String): HomeNetworkBinding? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(encoded)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "unsupported home binding version" }
            val binding = HomeNetworkBinding(
                deviceName = input.readField(),
                deviceModel = input.readNullableField(),
                endpointHost = input.readField(),
                endpointPort = input.readInt(),
                deviceFingerprint = input.readField(),
                networkFingerprint = input.readField(),
            )
            require(binding.endpointPort in 1..65535) { "invalid endpoint port" }
            require(input.read() == -1) { "trailing home binding data" }
            binding
        }
    }.getOrNull()

    private fun DataOutputStream.writeField(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FIELD_BYTES) { "home binding field is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableField(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeField(value)
    }

    private fun DataInputStream.readField(): String {
        val length = readInt()
        require(length in 0..MAX_FIELD_BYTES) { "invalid home binding field length" }
        return String(ByteArray(length).also(::readFully), Charsets.UTF_8)
    }

    private fun DataInputStream.readNullableField(): String? =
        if (readBoolean()) readField() else null
}
