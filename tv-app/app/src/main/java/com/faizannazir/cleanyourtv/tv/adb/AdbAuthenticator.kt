package com.faizannazir.cleanyourtv.tv.adb

import java.security.KeyPair

/**
 * The two operations the ADB handshake needs from the key store.
 *
 * It is an interface so [AdbConnection] can be tested without Android's `Context`,
 * `android.util.Base64` or a key written to disk.
 */
interface AdbAuthenticator {
    /** Signs the peer's AUTH token with the ADB private key. */
    fun signToken(token: ByteArray): ByteArray

    /** The `base64 user@host\0` public key payload the TV shows in its authorisation prompt. */
    fun publicKeyPayload(): ByteArray
}

/** [AdbAuthenticator] backed by the key files [AdbKeyManager] keeps in the app's files directory. */
class KeyManagerAuthenticator(private val keyManager: AdbKeyManager) : AdbAuthenticator {

    private val keyPair: KeyPair by lazy { keyManager.getOrCreateKeyPair() }

    override fun signToken(token: ByteArray): ByteArray = keyManager.signToken(token, keyPair)

    override fun publicKeyPayload(): ByteArray = keyManager.getAdbPublicKeyPayload(keyPair)
}
