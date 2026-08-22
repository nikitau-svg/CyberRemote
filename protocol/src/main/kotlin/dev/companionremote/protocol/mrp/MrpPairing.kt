package dev.companionremote.protocol.mrp

import dev.companionremote.protocol.crypto.Crypto
import dev.companionremote.protocol.crypto.HapSrpClient
import dev.companionremote.protocol.mrp.proto.CryptoPairingMessageOuterClass
import dev.companionremote.protocol.mrp.proto.ProtocolMessage
import dev.companionremote.protocol.tlv8.Tlv8
import dev.companionremote.protocol.tlv8.TlvValue
import java.util.UUID

/**
 * Credentials for the MRP service. They deliberately have a distinct type so
 * callers cannot accidentally store or load Companion Link credentials under
 * the MRP key even though both use the same four-field HAP representation.
 */
data class MrpCredentials(
    val deviceLongTermPublicKey: ByteArray,
    val controllerLongTermSecretKey: ByteArray,
    val deviceIdentifier: ByteArray,
    val controllerIdentifier: ByteArray,
) {
    override fun toString(): String = listOf(
        deviceLongTermPublicKey,
        controllerLongTermSecretKey,
        deviceIdentifier,
        controllerIdentifier,
    ).joinToString(":") { it.toHexString() }

    override fun equals(other: Any?): Boolean = other is MrpCredentials &&
        deviceLongTermPublicKey.contentEquals(other.deviceLongTermPublicKey) &&
        controllerLongTermSecretKey.contentEquals(other.controllerLongTermSecretKey) &&
        deviceIdentifier.contentEquals(other.deviceIdentifier) &&
        controllerIdentifier.contentEquals(other.controllerIdentifier)

    override fun hashCode(): Int = toString().hashCode()

    companion object {
        fun parse(encoded: String): MrpCredentials {
            val fields = encoded.trim().split(":")
            require(fields.size == 4) { "invalid MRP credentials (expected four hex fields)" }
            return MrpCredentials(
                fields[0].hexToByteArray(),
                fields[1].hexToByteArray(),
                fields[2].hexToByteArray(),
                fields[3].hexToByteArray(),
            )
        }
    }
}

class MrpPairingException(message: String, cause: Throwable? = null) : MrpException(message, cause)

/** Direct-MRP pair-setup. Modern tvOS uses AirPlay HAP pairing instead. */
class MrpPairSetup(private val connection: MrpConnection) {
    private var deviceSalt: ByteArray? = null
    private var devicePublicKey: ByteArray? = null

    suspend fun startPairing() {
        val response = connection.sendAndReceive(
            MrpMessages.cryptoPairing(
                Tlv8.write(
                    linkedMapOf(
                        TlvValue.METHOD to byteArrayOf(0x00),
                        TlvValue.SEQ_NO to byteArrayOf(0x01),
                    ),
                ),
                isPairing = true,
            ),
            generateIdentifier = false,
        )
        val data = pairingData(response)
        deviceSalt = data[TlvValue.SALT] ?: throw MrpPairingException("MRP pair M2 has no salt")
        devicePublicKey = data[TlvValue.PUBLIC_KEY]
            ?: throw MrpPairingException("MRP pair M2 has no public key")
    }

    suspend fun finishPairing(pin: String): MrpCredentials {
        val salt = deviceSalt ?: error("call startPairing first")
        val serverPublicKey = devicePublicKey ?: error("call startPairing first")
        val srp = HapSrpClient("Pair-Setup", pin)
        srp.processServerParams(serverPublicKey, salt)

        val m4 = connection.sendAndReceive(
            MrpMessages.cryptoPairing(
                Tlv8.write(
                    linkedMapOf(
                        TlvValue.SEQ_NO to byteArrayOf(0x03),
                        TlvValue.PUBLIC_KEY to srp.publicKey,
                        TlvValue.PROOF to srp.clientProof,
                    ),
                ),
            ),
            generateIdentifier = false,
        )
        val serverProof = pairingData(m4)[TlvValue.PROOF]
            ?: throw MrpPairingException("MRP pair M4 has no proof")
        if (!srp.verifyServerProof(serverProof)) {
            throw MrpPairingException("MRP server proof mismatch (wrong PIN?)")
        }

        val controllerSecret = Crypto.randomBytes(32)
        val controllerPublic = Crypto.ed25519PublicKey(controllerSecret)
        val controllerId = UUID.randomUUID().toString().toByteArray()
        val controllerX = Crypto.hkdfSha512(
            "Pair-Setup-Controller-Sign-Salt",
            "Pair-Setup-Controller-Sign-Info",
            srp.sessionKey,
        )
        val setupKey = Crypto.hkdfSha512(
            "Pair-Setup-Encrypt-Salt",
            "Pair-Setup-Encrypt-Info",
            srp.sessionKey,
        )
        val signature = Crypto.ed25519Sign(controllerSecret, controllerX + controllerId + controllerPublic)
        val encryptedM5 = Crypto.chaChaEncrypt(
            setupKey,
            Crypto.padNonce("PS-Msg05"),
            Tlv8.write(
                linkedMapOf(
                    TlvValue.IDENTIFIER to controllerId,
                    TlvValue.PUBLIC_KEY to controllerPublic,
                    TlvValue.SIGNATURE to signature,
                ),
            ),
        )

        val m6 = connection.sendAndReceive(
            MrpMessages.cryptoPairing(
                Tlv8.write(
                    linkedMapOf(
                        TlvValue.SEQ_NO to byteArrayOf(0x05),
                        TlvValue.ENCRYPTED_DATA to encryptedM5,
                    ),
                ),
            ),
            generateIdentifier = false,
        )
        val encryptedM6 = pairingData(m6)[TlvValue.ENCRYPTED_DATA]
            ?: throw MrpPairingException("MRP pair M6 has no encrypted data")
        val decryptedM6 = try {
            Crypto.chaChaDecrypt(setupKey, Crypto.padNonce("PS-Msg06"), encryptedM6)
        } catch (error: Exception) {
            throw MrpPairingException("MRP pair M6 decrypt failed", error)
        }
        val device = Tlv8.read(decryptedM6)
        val deviceId = device[TlvValue.IDENTIFIER]
            ?: throw MrpPairingException("MRP pair M6 has no device identifier")
        val devicePublic = device[TlvValue.PUBLIC_KEY]
            ?: throw MrpPairingException("MRP pair M6 has no device public key")

        return MrpCredentials(devicePublic, controllerSecret, deviceId, controllerId)
    }
}

/** Direct-MRP pair-verify and MediaRemote session-key derivation. */
class MrpPairVerify(
    private val connection: MrpConnection,
    private val credentials: MrpCredentials,
) {
    suspend fun verify(): MrpSessionKeys {
        val ephemeralPrivate = Crypto.randomBytes(32)
        val ephemeralPublic = Crypto.x25519PublicKey(ephemeralPrivate)

        val m2 = connection.sendAndReceive(
            MrpMessages.cryptoPairing(
                Tlv8.write(
                    linkedMapOf(
                        TlvValue.SEQ_NO to byteArrayOf(0x01),
                        TlvValue.PUBLIC_KEY to ephemeralPublic,
                    ),
                ),
            ),
            generateIdentifier = false,
        )
        val m2Data = pairingData(m2)
        val serverPublic = m2Data[TlvValue.PUBLIC_KEY]
            ?: throw MrpPairingException("MRP verify M2 has no public key")
        val encrypted = m2Data[TlvValue.ENCRYPTED_DATA]
            ?: throw MrpPairingException("MRP verify M2 has no encrypted data")
        val sharedSecret = Crypto.x25519SharedSecret(ephemeralPrivate, serverPublic)
        val verifyKey = Crypto.hkdfSha512(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            sharedSecret,
        )
        val device = try {
            Tlv8.read(Crypto.chaChaDecrypt(verifyKey, Crypto.padNonce("PV-Msg02"), encrypted))
        } catch (error: Exception) {
            throw MrpPairingException("MRP verify M2 decrypt failed (stale credentials?)", error)
        }
        val identifier = device[TlvValue.IDENTIFIER]
            ?: throw MrpPairingException("MRP verify M2 has no identifier")
        val signature = device[TlvValue.SIGNATURE]
            ?: throw MrpPairingException("MRP verify M2 has no signature")
        if (!identifier.contentEquals(credentials.deviceIdentifier)) {
            throw MrpPairingException("MRP verify device identifier mismatch")
        }
        if (!Crypto.ed25519Verify(
                credentials.deviceLongTermPublicKey,
                serverPublic + identifier + ephemeralPublic,
                signature,
            )
        ) {
            throw MrpPairingException("MRP verify device signature mismatch")
        }

        val controllerSignature = Crypto.ed25519Sign(
            credentials.controllerLongTermSecretKey,
            ephemeralPublic + credentials.controllerIdentifier + serverPublic,
        )
        val encryptedM3 = Crypto.chaChaEncrypt(
            verifyKey,
            Crypto.padNonce("PV-Msg03"),
            Tlv8.write(
                linkedMapOf(
                    TlvValue.IDENTIFIER to credentials.controllerIdentifier,
                    TlvValue.SIGNATURE to controllerSignature,
                ),
            ),
        )
        val m4 = connection.sendAndReceive(
            MrpMessages.cryptoPairing(
                Tlv8.write(
                    linkedMapOf(
                        TlvValue.SEQ_NO to byteArrayOf(0x03),
                        TlvValue.ENCRYPTED_DATA to encryptedM3,
                    ),
                ),
            ),
            generateIdentifier = false,
        )
        pairingData(m4)

        return MrpSessionKeys(
            outputKey = Crypto.hkdfSha512(
                "MediaRemote-Salt",
                "MediaRemote-Write-Encryption-Key",
                sharedSecret,
            ),
            inputKey = Crypto.hkdfSha512(
                "MediaRemote-Salt",
                "MediaRemote-Read-Encryption-Key",
                sharedSecret,
            ),
        )
    }
}

internal fun pairingData(message: ProtocolMessage): Map<Int, ByteArray> {
    if (!message.hasExtension(CryptoPairingMessageOuterClass.cryptoPairingMessage)) {
        throw MrpPairingException("MRP response has no crypto pairing payload")
    }
    val raw = message.getExtension(CryptoPairingMessageOuterClass.cryptoPairingMessage).pairingData.toByteArray()
    val data = Tlv8.read(raw)
    data[TlvValue.ERROR]?.let { error ->
        val code = error.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        throw MrpPairingException("Apple TV returned MRP pairing error $code")
    }
    return data
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "odd-length hex field" }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
