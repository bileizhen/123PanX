package io.github.bileizhen.pan123x.core.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real AndroidKeyStore master key on device. */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialCryptoTest {
    private val crypto = KeystoreCredentialCrypto()

    @Test
    fun sealThenOpenReturnsTheOriginalPlaintext() {
        val plain = "p@ssw0rd-登录密码".toByteArray(Charsets.UTF_8)
        val sealed = crypto.seal(plain, "account-1")
        assertArrayEquals(plain, crypto.open(sealed, "account-1"))
    }

    @Test
    fun openingWithADifferentAadFails() {
        val sealed = crypto.seal("Bearer token-value".toByteArray(Charsets.UTF_8), "account-1")
        assertThrows(GeneralSecurityException::class.java) { crypto.open(sealed, "account-2") }
    }

    @Test
    fun randomizedEncryptionNeverRepeatsCiphertextForTheSamePlaintext() {
        val plain = "same plaintext".toByteArray(Charsets.UTF_8)
        val first = crypto.seal(plain, "account-1")
        val second = crypto.seal(plain, "account-1")
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, crypto.open(first, "account-1"))
        assertArrayEquals(plain, crypto.open(second, "account-1"))
    }
}
