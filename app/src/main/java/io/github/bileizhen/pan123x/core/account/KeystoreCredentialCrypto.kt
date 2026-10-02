package io.github.bileizhen.pan123x.core.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [CredentialCrypto] backed by a non-exportable AES-256-GCM key inside AndroidKeyStore
 * (alias "pan123x_master"). This is the Android platform rewrite of the reference project's
 * PBKDF2 file encryption allowed by : the key material never leaves the keystore,
 * so persisting only ciphertext needs no passphrase. StrongBox is not required. Randomized
 * encryption stays enabled, so every [seal] draws a fresh keystore-generated IV.
 */
class KeystoreCredentialCrypto : CredentialCrypto {

    override fun seal(plain: ByteArray, aad: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv ?: throw GeneralSecurityException("AndroidKeyStore returned no GCM IV")
        return iv + encrypted
    }

    override fun open(sealed: ByteArray, aad: String): ByteArray {
        if (sealed.size <= GCM_IV_LENGTH) throw GeneralSecurityException("Sealed blob is shorter than its IV")
        val iv = sealed.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = sealed.copyOfRange(GCM_IV_LENGTH, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    /** Loads the master key, generating it on first use; guarded so the alias is created once. */
    private fun masterKey(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        // getKey returns the key handle itself; getEntry would wrap it in a SecretKeyEntry.
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "pan123x_master"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        val keyLock = Any()
    }
}
