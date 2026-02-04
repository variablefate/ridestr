package com.ridestr.common.nostr

import android.util.Log
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.keys.KeyManager
import org.json.JSONObject

/**
 * Helper class for NIP-44 encryption/decryption operations.
 *
 * Provides utilities for encrypting and decrypting:
 * - Generic data for user-to-user communication
 * - Locations for rider ride state history
 * - PINs for driver ride state history
 *
 * All methods require the KeyManager to have a valid signer (user must be logged in).
 *
 * @param keyManager The KeyManager instance for accessing the user's signer
 */
class NostrCryptoHelper(private val keyManager: KeyManager) {

    companion object {
        private const val TAG = "NostrCryptoHelper"
    }

    /**
     * Encrypt arbitrary string data for a specific user (NIP-44).
     * Generic helper for encrypting any data to a recipient.
     *
     * @param data The data to encrypt
     * @param recipientPubKey The recipient's public key
     * @return Encrypted string, or null on failure
     */
    suspend fun encryptForUser(
        data: String,
        recipientPubKey: String
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Encrypt(data, recipientPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt data for user", e)
            null
        }
    }

    /**
     * Decrypt arbitrary string data from a specific user (NIP-44).
     * Generic helper for decrypting any data from a sender.
     *
     * @param encryptedData The encrypted data
     * @param senderPubKey The sender's public key
     * @return Decrypted string, or null on failure
     */
    suspend fun decryptFromUser(
        encryptedData: String,
        senderPubKey: String
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Decrypt(encryptedData, senderPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt data from user", e)
            null
        }
    }

    /**
     * Encrypt a location for inclusion in rider ride state history.
     * @param location The location to encrypt
     * @param driverPubKey The driver's public key (recipient)
     * @return Encrypted location string, or null on failure
     */
    suspend fun encryptLocationForRiderState(
        location: Location,
        driverPubKey: String
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Encrypt(location.toJson().toString(), driverPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt location", e)
            null
        }
    }

    /**
     * Decrypt a location from rider ride state history.
     * @param encryptedLocation The encrypted location string
     * @param riderPubKey The rider's public key (sender)
     * @return Decrypted location, or null on failure
     */
    suspend fun decryptLocationFromRiderState(
        encryptedLocation: String,
        riderPubKey: String
    ): Location? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            val decrypted = signer.nip44Decrypt(encryptedLocation, riderPubKey)
            Location.fromJson(JSONObject(decrypted))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt location", e)
            null
        }
    }

    /**
     * Encrypt a PIN for inclusion in driver ride state history.
     * @param pin The PIN to encrypt
     * @param riderPubKey The rider's public key (recipient)
     * @return Encrypted PIN string, or null on failure
     */
    suspend fun encryptPinForDriverState(
        pin: String,
        riderPubKey: String
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Encrypt(pin, riderPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt PIN", e)
            null
        }
    }

    /**
     * Decrypt a PIN from driver ride state history.
     * @param encryptedPin The encrypted PIN string
     * @param driverPubKey The driver's public key (sender)
     * @return Decrypted PIN, or null on failure
     */
    suspend fun decryptPinFromDriverState(
        encryptedPin: String,
        driverPubKey: String
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Decrypt(encryptedPin, driverPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt PIN", e)
            null
        }
    }
}
