package com.ridestr.common.nostr.keys

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

/**
 * Manages Nostr keypairs for the app.
 * Handles generation, import, storage, and signer creation.
 */
class KeyManager(context: Context) {

    companion object {
        private const val TAG = "KeyManager"
    }

    private val storage = SecureKeyStorage(context)
    private var currentKeyPair: KeyPair? = null
    private var currentSigner: NostrSignerInternal? = null

    init {
        // Load existing key if available
        loadStoredKey()
    }

    /**
     * Check if a key is currently loaded.
     */
    fun hasKey(): Boolean = currentKeyPair != null

    /**
     * Generate a new random keypair and store it.
     * @return true if successful
     */
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

    /**
     * Import a private key from nsec (bech32) or hex format.
     * @param keyInput The private key in nsec1... or hex format
     * @return true if import succeeded, false if key is invalid
     */
    fun importKey(keyInput: String): Boolean {
        return try {
            val trimmed = keyInput.trim()

            // Try to decode as nsec or hex
            val privKeyHex = decodePrivateKeyAsHexOrNull(trimmed)
                ?: throw IllegalArgumentException("Invalid key format: not a valid nsec or hex key")

            // Validate by creating keypair
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

    /**
     * Get the public key in npub (bech32) format.
     * @return The npub string, or null if no key is loaded
     */
    fun getNpub(): String? {
        return currentKeyPair?.pubKey?.toNpub()
    }

    /**
     * Get the public key in hex format.
     * @return The hex public key, or null if no key is loaded
     */
    fun getPubKeyHex(): String? {
        return currentKeyPair?.pubKey?.toHexKey()
    }

    /**
     * Get the private key in nsec (bech32) format for backup.
     * WARNING: This exposes the private key. Only use for backup UI.
     * @return The nsec string, or null if no key is loaded
     */
    fun getNsec(): String? {
        return currentKeyPair?.privKey?.toNsec()
    }

    /**
     * Get the NostrSigner for signing events.
     * @return The signer, or null if no key is loaded
     */
    fun getSigner(): NostrSigner? {
        return currentSigner
    }

    /**
     * Clear the stored key and reset state.
     */
    fun logout() {
        storage.clearKey()
        storage.clearProfileStatus()
        currentKeyPair = null
        currentSigner = null
        Log.d(TAG, "Logged out, key and profile status cleared")
    }

    /**
     * Check if profile setup has been completed.
     */
    fun isProfileCompleted(): Boolean {
        return storage.isProfileCompleted()
    }

    /**
     * Mark profile setup as completed.
     */
    fun markProfileCompleted() {
        storage.saveProfileCompleted()
    }

    /**
     * Get the current user mode (RIDER or DRIVER).
     */
    fun getUserMode(): SecureKeyStorage.UserMode {
        return storage.getUserMode()
    }

    /**
     * Set the user mode (RIDER or DRIVER).
     */
    fun setUserMode(mode: SecureKeyStorage.UserMode) {
        storage.saveUserMode(mode)
    }

    /**
     * Reload keys from storage.
     * Call this after another KeyManager instance imports a key,
     * to ensure this instance has the latest keys.
     */
    fun refreshFromStorage() {
        loadStoredKey()
    }

    /**
     * Load the key from secure storage.
     */
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
