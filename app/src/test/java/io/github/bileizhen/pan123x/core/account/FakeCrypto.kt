package io.github.bileizhen.pan123x.core.account

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real JVM AES-GCM with an in-memory key: mirrors the AAD/IV/tag semantics of
 * [KeystoreCredentialCrypto] without touching AndroidKeyStore, so host tests exercise the
 * exact corruption paths the keystore implementation produces on device.
 */
class FakeCrypto : CredentialCrypto {
    private val key: SecretKey = SecretKeySpec(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes), "AES")

    override fun seal(plain: ByteArray, aad: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray, aad: String): ByteArray {
        val iv = sealed.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = sealed.copyOfRange(GCM_IV_LENGTH, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
