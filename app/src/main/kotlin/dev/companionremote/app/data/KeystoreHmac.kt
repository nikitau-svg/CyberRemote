package dev.companionremote.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Per-install HMAC key used to turn local-network and device identifiers into
 * non-reversible fingerprints. Raw topology data never leaves memory.
 */
internal object KeystoreHmac {
    private const val ALIAS = "companion-remote-local-identity"
    private const val ALGORITHM = "HmacSHA256"

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKey()
    }

    fun sign(payload: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(key())
        return mac.doFinal(payload)
    }
}
