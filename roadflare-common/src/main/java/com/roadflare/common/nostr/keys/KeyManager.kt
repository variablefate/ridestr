package com.roadflare.common.nostr.keys

import android.content.Context
import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip19Bech32.decodePrivateKeyAsHexOrNull
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Nostr keypairs for the app.
 * Handles generation, import, storage, and signer creation.
 */
@Singleton
class KeyManager @Inject constructor(@ApplicationContext context: Context) {

    companion object {
        private const val TAG = "KeyManager"
    }

    private val storage = SecureKeyStorage(context)
    private var currentKeyPair: KeyPair? = null
    private var currentSigner: NostrSignerInternal? = null

    init {
        loadStoredKey()
    }

    fun hasKey(): Boolean = currentKeyPair != null

    fun generateNewKey(): Boolean {
        return try {
            val keyPair = KeyPair()
            val privKeyHex = keyPair.privKey?.toHexKey()
                ?: throw IllegalStateException("Generated keypair has no private key")

            storage.savePrivateKey(privKeyHex)
            currentKeyPair = keyPair
            currentSigner = NostrSignerInternal(keyPair)

            Log.d(TAG, "Generated new keypair: ${getNpub()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate keypair", e)
            false
        }
    }

    fun importKey(keyInput: String): Boolean {
        return try {
            val trimmed = keyInput.trim()
            val privKeyHex = decodePrivateKeyAsHexOrNull(trimmed)
                ?: throw IllegalArgumentException("Invalid key format: not a valid nsec or hex key")

            val keyPair = KeyPair(privKey = privKeyHex.hexToByteArray())

            storage.savePrivateKey(privKeyHex)
            currentKeyPair = keyPair
            currentSigner = NostrSignerInternal(keyPair)

            Log.d(TAG, "Imported keypair: ${getNpub()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import key", e)
            false
        }
    }

    fun getNpub(): String? = currentKeyPair?.pubKey?.toNpub()

    fun getPubKeyHex(): String? = currentKeyPair?.pubKey?.toHexKey()

    fun getNsec(): String? = currentKeyPair?.privKey?.toNsec()

    fun getSigner(): NostrSigner? = currentSigner

    fun logout() {
        storage.clearKey()
        storage.clearProfileStatus()
        currentKeyPair = null
        currentSigner = null
        Log.d(TAG, "Logged out, key and profile status cleared")
    }

    fun isProfileCompleted(): Boolean = storage.isProfileCompleted()

    fun markProfileCompleted() = storage.saveProfileCompleted()

    fun getUserMode(): SecureKeyStorage.UserMode = storage.getUserMode()

    fun setUserMode(mode: SecureKeyStorage.UserMode) = storage.saveUserMode(mode)

    fun refreshFromStorage() = loadStoredKey()

    fun isUsingUnencryptedStorage(): Boolean = storage.isUsingFallback()

    private fun loadStoredKey() {
        val privKeyHex = storage.getPrivateKey() ?: return

        try {
            val keyPair = KeyPair(privKey = privKeyHex.hexToByteArray())
            currentKeyPair = keyPair
            currentSigner = NostrSignerInternal(keyPair)
            Log.d(TAG, "Loaded stored keypair: ${getNpub()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load stored key, clearing", e)
            storage.clearKey()
        }
    }
}
