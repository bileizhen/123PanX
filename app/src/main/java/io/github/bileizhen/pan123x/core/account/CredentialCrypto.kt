package io.github.bileizhen.pan123x.core.account

import java.security.GeneralSecurityException

/**
 * Seals credential secrets before they touch persistent storage.
 *
 * The sealed layout is `iv(12) || ciphertext || GCM-tag`, and the AAD binds every blob to exactly
 * one accountId, so a blob can never be replayed under another account's entry. Implementations
 * must draw a fresh random IV per [seal] call and never store the key next to the blobs.
 */
interface CredentialCrypto {

    fun seal(plain: ByteArray, aad: String): ByteArray

    /** Throws [GeneralSecurityException] when the AAD mismatches or the blob is corrupt. */
    fun open(sealed: ByteArray, aad: String): ByteArray
}
