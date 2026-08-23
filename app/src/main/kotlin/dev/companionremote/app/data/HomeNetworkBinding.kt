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
internal const val MAX_EXACT_NETWORK_FINGERPRINTS = 4
internal const val MAX_CONTINUITY_FINGERPRINTS_PER_RECORD = 16

data class HomeNetworkFingerprintRecord(
    val exactFingerprint: String,
    val continuityFingerprints: Set<String>,
)

data class HomeNetworkBinding(
    val deviceName: String,
    val deviceModel: String?,
    val endpointHost: String,
    val endpointPort: Int,
    val deviceFingerprint: String,
    val networkFingerprint: String,
    /** Non-authoritative hints used only to recognize an in-process Network replacement. */
    val continuityFingerprints: Set<String> = emptySet(),
    /** Oldest to newest verified exact variants; the final record is the current MRU. */
    val networkFingerprintRecords: List<HomeNetworkFingerprintRecord> = listOf(
        HomeNetworkFingerprintRecord(networkFingerprint, continuityFingerprints),
    ),
)

internal fun HomeNetworkBinding.exactFingerprints(): Set<String> =
    networkFingerprintRecords.mapTo(linkedSetOf()) { it.exactFingerprint }

/** Pure policy kept separate from Android so the security boundary is testable. */
internal object HomeNetworkPolicy {
    fun allowsAutomaticAccess(
        binding: HomeNetworkBinding,
        currentNetworkFingerprint: String?,
        expectedDeviceFingerprint: String? = null,
    ): Boolean =
        currentNetworkFingerprint != null &&
            binding.networkFingerprintRecords.any { record ->
                constantTimeEquals(record.exactFingerprint, currentNetworkFingerprint)
            } &&
            (
                expectedDeviceFingerprint == null ||
                    matchesDeviceIdentity(binding, expectedDeviceFingerprint)
                )

    /** Compare a freshly derived HAP identifier fingerprint with the stored paired TV. */
    fun matchesDeviceIdentity(
        binding: HomeNetworkBinding,
        expectedDeviceFingerprint: String,
    ): Boolean = constantTimeEquals(binding.deviceFingerprint, expectedDeviceFingerprint)

    /**
     * Metadata such as the Companion endpoint may change while an authorization is alive. Such a
     * refresh is equivalent for continuity only while it still describes the same paired TV and
     * preserves the exact origin record (including its non-authoritative continuity anchors).
     */
    fun isContinuityOriginEquivalent(
        originBinding: HomeNetworkBinding,
        originStrictFingerprint: String,
        persistedBinding: HomeNetworkBinding,
    ): Boolean {
        if (!matchesDeviceIdentity(persistedBinding, originBinding.deviceFingerprint)) return false
        val originRecord = originBinding.recordForExact(originStrictFingerprint) ?: return false
        val persistedRecord = persistedBinding.recordForExact(originStrictFingerprint)
            ?: return false
        return constantTimeSetEquals(
            originRecord.continuityFingerprints,
            persistedRecord.continuityFingerprints,
        )
    }

    /**
     * A continuity match is only a candidate signal. It must never be substituted for the strict
     * v1 network fingerprint in [allowsAutomaticAccess].
     */
    fun isContinuityCandidate(
        binding: HomeNetworkBinding,
        originStrictFingerprint: String,
        currentContinuityFingerprints: Set<String>,
    ): Boolean {
        val originAnchors = binding.networkFingerprintRecords
            .firstOrNull { record ->
                constantTimeEquals(record.exactFingerprint, originStrictFingerprint)
            }
            ?.continuityFingerprints
            .orEmpty()
        return originAnchors.isNotEmpty() &&
            currentContinuityFingerprints.isNotEmpty() &&
            originAnchors.any { expected ->
                currentContinuityFingerprints.any { current ->
                    constantTimeEquals(expected, current)
                }
            }
    }

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

    private fun HomeNetworkBinding.recordForExact(
        exactFingerprint: String,
    ): HomeNetworkFingerprintRecord? = networkFingerprintRecords.firstOrNull { record ->
        constantTimeEquals(record.exactFingerprint, exactFingerprint)
    }

    private fun constantTimeSetEquals(left: Set<String>, right: Set<String>): Boolean {
        if (left.size != right.size) return false
        return left.all { expected ->
            right.any { current -> constantTimeEquals(expected, current) }
        }
    }
}

/** Fill a legacy record only after its strict network fingerprint has matched. */
internal fun HomeNetworkBinding.migrateContinuityAfterStrictMatch(
    currentExactFingerprint: String,
    currentContinuityFingerprints: Set<String>,
): HomeNetworkBinding {
    if (currentContinuityFingerprints.isEmpty()) return this
    var changed = false
    val updatedRecords = networkFingerprintRecords.map { record ->
        if (
            record.exactFingerprint == currentExactFingerprint &&
            record.continuityFingerprints.isEmpty()
        ) {
            changed = true
            record.copy(continuityFingerprints = currentContinuityFingerprints)
        } else {
            record
        }
    }
    if (!changed) return this
    val currentRecord = updatedRecords.last()
    return copy(
        networkFingerprint = currentRecord.exactFingerprint,
        continuityFingerprints = currentRecord.continuityFingerprints,
        networkFingerprintRecords = updatedRecords,
    )
}

/** Append/touch a HAP-verified exact variant and evict the least-recently verified one. */
internal fun HomeNetworkBinding.withVerifiedNetworkVariant(
    exactFingerprint: String,
    continuityFingerprints: Set<String>,
): HomeNetworkBinding {
    val record = HomeNetworkFingerprintRecord(exactFingerprint, continuityFingerprints)
    val updatedRecords = (
        networkFingerprintRecords.filterNot { it.exactFingerprint == exactFingerprint } + record
        ).takeLast(MAX_EXACT_NETWORK_FINGERPRINTS)
    return copy(
        networkFingerprint = record.exactFingerprint,
        continuityFingerprints = record.continuityFingerprints,
        networkFingerprintRecords = updatedRecords,
    )
}

/** Versioned binary codec. The resulting string is encrypted by SettingsRepository. */
internal object HomeNetworkBindingCodec {
    private const val VERSION_1 = 1
    private const val VERSION_2 = 2
    private const val VERSION_3 = 3
    private const val MAX_LEGACY_CONTINUITY_FINGERPRINTS = 64
    private const val MAX_FIELD_BYTES = 64 * 1024

    fun encode(binding: HomeNetworkBinding): String {
        require(binding.endpointPort in 1..65535) { "invalid endpoint port" }
        require(binding.networkFingerprintRecords.size in 1..MAX_EXACT_NETWORK_FINGERPRINTS) {
            "invalid exact fingerprint record count"
        }
        require(
            binding.networkFingerprintRecords.map { it.exactFingerprint }.distinct().size ==
                binding.networkFingerprintRecords.size,
        ) { "duplicate exact network fingerprint" }
        binding.networkFingerprintRecords.forEach { record ->
            require(record.exactFingerprint.isNotBlank()) { "empty exact network fingerprint" }
            require(
                record.continuityFingerprints.size <= MAX_CONTINUITY_FINGERPRINTS_PER_RECORD,
            ) { "too many continuity fingerprints" }
            require(record.continuityFingerprints.none(String::isBlank)) {
                "empty continuity fingerprint"
            }
        }
        val currentRecord = binding.networkFingerprintRecords.last()
        require(binding.networkFingerprint == currentRecord.exactFingerprint) {
            "current exact fingerprint is inconsistent"
        }
        require(binding.continuityFingerprints == currentRecord.continuityFingerprints) {
            "current continuity fingerprints are inconsistent"
        }
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(VERSION_3)
                output.writeField(binding.deviceName)
                output.writeNullableField(binding.deviceModel)
                output.writeField(binding.endpointHost)
                output.writeInt(binding.endpointPort)
                output.writeField(binding.deviceFingerprint)
                output.writeFingerprintRecords(binding.networkFingerprintRecords)
            }
            buffer.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(encoded: String): HomeNetworkBinding? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(encoded)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version in VERSION_1..VERSION_3) {
                "unsupported home binding version"
            }
            val deviceName = input.readField()
            val deviceModel = input.readNullableField()
            val endpointHost = input.readField()
            val endpointPort = input.readInt()
            val deviceFingerprint = input.readField()
            val records = when (version) {
                VERSION_1 -> listOf(
                    HomeNetworkFingerprintRecord(
                        exactFingerprint = input.readField(),
                        continuityFingerprints = emptySet(),
                    ),
                )
                VERSION_2 -> listOf(
                    HomeNetworkFingerprintRecord(
                        exactFingerprint = input.readField(),
                        continuityFingerprints = input
                            .readStringSet(MAX_LEGACY_CONTINUITY_FINGERPRINTS)
                            .sorted()
                            .take(MAX_CONTINUITY_FINGERPRINTS_PER_RECORD)
                            .toSet(),
                    ),
                )
                VERSION_3 -> input.readFingerprintRecords()
                else -> error("unreachable binding version")
            }
            val currentRecord = records.last()
            require(records.all { it.exactFingerprint.isNotBlank() }) {
                "empty exact network fingerprint"
            }
            require(records.all { record -> record.continuityFingerprints.none(String::isBlank) }) {
                "empty continuity fingerprint"
            }
            val binding = HomeNetworkBinding(
                deviceName = deviceName,
                deviceModel = deviceModel,
                endpointHost = endpointHost,
                endpointPort = endpointPort,
                deviceFingerprint = deviceFingerprint,
                networkFingerprint = currentRecord.exactFingerprint,
                continuityFingerprints = currentRecord.continuityFingerprints,
                networkFingerprintRecords = records,
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

    private fun DataOutputStream.writeFingerprintRecords(
        records: List<HomeNetworkFingerprintRecord>,
    ) {
        writeInt(records.size)
        records.forEach { record ->
            writeField(record.exactFingerprint)
            writeStringSet(record.continuityFingerprints)
        }
    }

    private fun DataOutputStream.writeStringSet(values: Set<String>) {
        writeInt(values.size)
        values.sorted().forEach { value -> writeField(value) }
    }

    private fun DataInputStream.readField(): String {
        val length = readInt()
        require(length in 0..MAX_FIELD_BYTES) { "invalid home binding field length" }
        return String(ByteArray(length).also(::readFully), Charsets.UTF_8)
    }

    private fun DataInputStream.readNullableField(): String? =
        if (readBoolean()) readField() else null

    private fun DataInputStream.readFingerprintRecords(): List<HomeNetworkFingerprintRecord> {
        val size = readInt()
        require(size in 1..MAX_EXACT_NETWORK_FINGERPRINTS) {
            "invalid exact fingerprint record count"
        }
        val records = buildList(size) {
            repeat(size) {
                add(
                    HomeNetworkFingerprintRecord(
                        exactFingerprint = readField(),
                        continuityFingerprints = readStringSet(
                            MAX_CONTINUITY_FINGERPRINTS_PER_RECORD,
                        ),
                    ),
                )
            }
        }
        require(records.map { it.exactFingerprint }.distinct().size == records.size) {
            "duplicate exact network fingerprint"
        }
        return records
    }

    private fun DataInputStream.readStringSet(maxSize: Int): Set<String> {
        val size = readInt()
        require(size in 0..maxSize) {
            "invalid continuity fingerprint count"
        }
        return buildSet(size) {
            repeat(size) {
                require(add(readField())) { "duplicate continuity fingerprint" }
            }
        }
    }
}
