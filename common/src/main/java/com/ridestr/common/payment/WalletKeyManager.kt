package com.ridestr.common.payment

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import fr.acinq.secp256k1.Secp256k1
import org.cashudevkit.generateMnemonic
import java.security.SecureRandom

/**
 * Manages wallet-specific keypair for NIP-60 P2PK ecash.
 *
 * CRITICAL: This is NOT the user's Nostr identity key!
 * The wallet key is used for:
 * - P2PK spending conditions on Cashu proofs
 * - Signing HTLC witness for NUT-14
 *
 * The key is stored encrypted locally and can be backed up via NIP-60 Kind 17375.
 */
class WalletKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "WalletKeyManager"
        private const val PREFS_FILE = "ridestr_wallet_keys"
        private const val KEY_WALLET_PRIVKEY = "wallet_privkey"
        private const val KEY_WALLET_MNEMONIC = "wallet_mnemonic"
    }

    private val prefs: SharedPreferences
    private var cachedKeypair: WalletKeypair? = null

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to regular prefs", e)
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * Get or generate the wallet-specific keypair.
     * This key is separate from Nostr identity and used for P2PK ecash.
     */
    fun getOrCreateWalletKeypair(): WalletKeypair {
        // Return cached keypair if available
        cachedKeypair?.let { return it }

        // Try to load from storage
        val existingPrivKey = prefs.getString(KEY_WALLET_PRIVKEY, null)
        if (existingPrivKey != null) {
            return try {
                WalletKeypair.fromPrivateKey(existingPrivKey).also {
                    cachedKeypair = it
                    Log.d(TAG, "Loaded wallet keypair: ${it.publicKeyHex.take(16)}...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load wallet key, generating new", e)
                generateAndSaveNewKeypair()
            }
        }

        // Generate new keypair
        return generateAndSaveNewKeypair()
    }

    /**
     * Check if a wallet keypair exists.
     */
    fun hasWalletKey(): Boolean {
        return prefs.getString(KEY_WALLET_PRIVKEY, null) != null
    }

    /**
     * Get wallet public key hex for P2PK conditions.
     * Returns compressed pubkey (33 bytes = 66 hex chars) for Cashu NUT-11 compatibility.
     * Returns null if no wallet key exists.
     */
    fun getWalletPubKeyHex(): String? {
        return try {
            val keypair = getOrCreateWalletKeypair()
            // Cashu NUT-11 P2PK requires compressed pubkey (33 bytes = 66 hex chars)
            // Nostr/Quartz uses x-only pubkeys (32 bytes = 64 hex chars)
            // For BIP-340 x-only keys, we can use "02" prefix (even y-coordinate assumed)
            if (keypair.publicKeyHex.length == 64) {
                "02${keypair.publicKeyHex}"
            } else {
                keypair.publicKeyHex
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wallet pubkey", e)
            null
        }
    }

    /**
     * Get or create BIP-39 mnemonic for cdk-kotlin wallet seed.
     * This mnemonic is used to deterministically derive keys for Cashu proofs.
     *
     * Uses cdk-kotlin's generateMnemonic() which wraps the Rust CDK library's
     * BIP-39 implementation (bip39 crate v2.0) - the same standard used by
     * Bitcoin wallets like BlueWallet.
     *
     * @throws IllegalStateException if mnemonic generation fails
     */
    fun getOrCreateMnemonic(): String {
        val existing = prefs.getString(KEY_WALLET_MNEMONIC, null)
        if (existing != null) {
            Log.d(TAG, "Loaded existing wallet mnemonic")
            return existing
        }

        // Generate new BIP-39 mnemonic using cdk-kotlin (Rust bip39 crate)
        val mnemonic = try {
            generateMnemonic()
        } catch (e: Exception) {
            Log.e(TAG, "BIP-39 mnemonic generation failed", e)
            throw IllegalStateException("Failed to generate BIP-39 mnemonic: ${e.message}", e)
        }

        prefs.edit().putString(KEY_WALLET_MNEMONIC, mnemonic).apply()
        Log.d(TAG, "Generated new BIP-39 wallet mnemonic")
        return mnemonic
    }

    /**
     * Check if a wallet mnemonic exists.
     */
    fun hasMnemonic(): Boolean = prefs.getString(KEY_WALLET_MNEMONIC, null) != null

    /**
     * Export wallet private key for NIP-60 backup.
     * This should be encrypted with NIP-44 before storing in Nostr events.
     *
     * @return The private key hex string
     */
    fun exportPrivateKeyForBackup(): String? {
        return prefs.getString(KEY_WALLET_PRIVKEY, null)
    }

    /**
     * Export wallet key encrypted for NIP-60 backup.
     * Encrypted with NIP-44 to user's own Nostr pubkey.
     *
     * @param nostrSigner The user's Nostr signer for NIP-44 encryption
     * @param userPubKey The user's Nostr public key (encrypt to self)
     * @return Encrypted wallet private key, or null on failure
     */
    suspend fun exportEncryptedForBackup(nostrSigner: NostrSigner, userPubKey: String): String? {
        val privateKey = prefs.getString(KEY_WALLET_PRIVKEY, null) ?: return null
        return try {
            nostrSigner.nip44Encrypt(privateKey, userPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt wallet key for backup", e)
            null
        }
    }

    /**
     * Import wallet key from NIP-60 backup.
     *
     * @param encryptedKey The NIP-44 encrypted wallet private key
     * @param nostrSigner The user's Nostr signer for decryption
     * @param userPubKey The user's Nostr public key
     * @return true if import succeeded
     */
    suspend fun importFromBackup(
        encryptedKey: String,
        nostrSigner: NostrSigner,
        userPubKey: String
    ): Boolean {
        return try {
            val privateKey = nostrSigner.nip44Decrypt(encryptedKey, userPubKey)

            // Validate by creating keypair
            val keypair = WalletKeypair.fromPrivateKey(privateKey)

            // Save and cache
            prefs.edit().putString(KEY_WALLET_PRIVKEY, privateKey).apply()
            cachedKeypair = keypair

            Log.d(TAG, "Imported wallet key from backup: ${keypair.publicKeyHex.take(16)}...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import wallet key from backup", e)
            false
        }
    }

    /**
     * Import wallet key directly (for restoring from plaintext backup).
     *
     * @param privateKeyHex The wallet private key in hex format
     * @return true if import succeeded
     */
    fun importPrivateKey(privateKeyHex: String): Boolean {
        return try {
            // Validate by creating keypair
            val keypair = WalletKeypair.fromPrivateKey(privateKeyHex)

            // Save and cache
            prefs.edit().putString(KEY_WALLET_PRIVKEY, privateKeyHex).apply()
            cachedKeypair = keypair

            Log.d(TAG, "Imported wallet key: ${keypair.publicKeyHex.take(16)}...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import wallet key", e)
            false
        }
    }

    /**
     * Import mnemonic from NIP-60 backup.
     * Used for cross-device cdk-kotlin wallet recovery.
     *
     * @param mnemonic The BIP-39 mnemonic words
     * @return true if import succeeded
     */
    fun importMnemonic(mnemonic: String): Boolean {
        return try {
            // Basic validation - should be 12 or 24 words
            val words = mnemonic.trim().split(" ").filter { it.isNotBlank() }
            if (words.size != 12 && words.size != 24) {
                Log.w(TAG, "Invalid mnemonic word count: ${words.size}")
                return false
            }

            prefs.edit().putString(KEY_WALLET_MNEMONIC, mnemonic.trim()).apply()
            Log.d(TAG, "Imported wallet mnemonic (${words.size} words)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import mnemonic", e)
            false
        }
    }

    /**
     * Clear the wallet key and mnemonic (for logout/reset).
     */
    fun clearWalletKey() {
        prefs.edit()
            .remove(KEY_WALLET_PRIVKEY)
            .remove(KEY_WALLET_MNEMONIC)
            .apply()
        cachedKeypair = null
        Log.d(TAG, "Wallet key and mnemonic cleared")
    }

    private fun generateAndSaveNewKeypair(): WalletKeypair {
        val keypair = WalletKeypair.generate()
        prefs.edit().putString(KEY_WALLET_PRIVKEY, keypair.privateKeyHex).apply()
        cachedKeypair = keypair
        Log.d(TAG, "Generated new wallet keypair: ${keypair.publicKeyHex.take(16)}...")
        return keypair
    }
}

/**
 * Wallet keypair for P2PK ecash operations.
 * Uses secp256k1 curve (same as Nostr/Bitcoin).
 */
data class WalletKeypair(
    val privateKeyHex: String,
    val publicKeyHex: String
) {
    companion object {
        /**
         * Generate a new random keypair.
         */
        fun generate(): WalletKeypair {
            // Generate random 32-byte private key
            val privateKey = ByteArray(32)
            SecureRandom().nextBytes(privateKey)

            // Use Quartz's KeyPair to derive public key (secp256k1)
            val keyPair = KeyPair(privKey = privateKey)
            val pubKeyHex = keyPair.pubKey.toHexKey()
            val privKeyHex = privateKey.toHexKey()

            return WalletKeypair(
                privateKeyHex = privKeyHex,
                publicKeyHex = pubKeyHex
            )
        }

        /**
         * Create keypair from existing private key.
         */
        fun fromPrivateKey(hex: String): WalletKeypair {
            val privateKey = hex.hexToByteArray()
            require(privateKey.size == 32) { "Private key must be 32 bytes" }

            val keyPair = KeyPair(privKey = privateKey)
            val pubKeyHex = keyPair.pubKey.toHexKey()

            return WalletKeypair(
                privateKeyHex = hex,
                publicKeyHex = pubKeyHex
            )
        }
    }

    /**
     * Get the private key as byte array for signing operations.
     */
    fun getPrivateKeyBytes(): ByteArray = privateKeyHex.hexToByteArray()

    /**
     * Get the public key as byte array.
     */
    fun getPublicKeyBytes(): ByteArray = publicKeyHex.hexToByteArray()

    /**
     * Create a Schnorr signature (BIP-340) for the given message hash.
     * Used for P2PK witness in NUT-11/14 HTLC claims.
     *
     * @param messageHash 32-byte SHA256 hash to sign
     * @return 64-byte Schnorr signature as hex, or null on failure
     */
    fun signSchnorr(messageHash: ByteArray): String? {
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }

        return try {
            val signature = Secp256k1.signSchnorr(
                messageHash,
                getPrivateKeyBytes(),
                null  // No aux random (deterministic)
            )
            signature.toHexKey()
        } catch (e: Exception) {
            null
        }
    }
}
