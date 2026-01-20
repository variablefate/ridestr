# Adding Nostr-Synced Features

**Last Updated**: 2026-01-17

This guide explains how to add new data types that sync via Nostr relays using the ProfileSyncManager architecture.

---

## Overview

Ridestr uses ProfileSyncManager to sync profile data across devices via Nostr relays. Currently synced data types:

| Data Type | Event Kind | d-tag | Sync Order | App |
|-----------|------------|-------|------------|-----|
| Wallet (NIP-60) | 7375, 17375 | - | 0 | Both |
| Ride History | 30174 | `rideshare-history` | 1 | Both |
| Vehicles | 30175 | `rideshare-vehicles` | 2 | Driver |
| Saved Locations | 30176 | `rideshare-locations` | 3 | Rider |

---

## Prerequisites

Before starting, understand:
- The data model for what needs to be synced
- Whether it's for rider app, driver app, or both
- The appropriate sync order (lower = synced earlier)

---

## Step 1: Choose Event Kind

Check existing event kinds to avoid conflicts:
- 7375, 7376, 17375: Wallet (NIP-60)
- 30174: Ride History
- 30175: Vehicles (driver)
- 30176: Saved Locations (rider)

For new features, use **30177+**.

---

## Step 2: Create Backup Event Class

Create `common/src/main/java/com/ridestr/common/nostr/events/XxxBackupEvent.kt`:

```kotlin
package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

object XxxBackupEvent {
    const val D_TAG = "rideshare-xxx"  // Unique d-tag

    suspend fun create(
        signer: NostrSigner,
        data: List<YourDataClass>
    ): Event? {
        val pubKeyHex = signer.pubKey

        val dataArray = JSONArray()
        data.forEach { item -> dataArray.put(item.toJson()) }

        val contentJson = JSONObject().apply {
            put("data", dataArray)
            put("updated_at", System.currentTimeMillis() / 1000)
        }

        // Encrypt to self using NIP-44
        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), pubKeyHex)
        } catch (e: Exception) {
            return null
        }

        val tags = arrayOf(
            arrayOf("d", D_TAG),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG)
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.XXX_BACKUP,  // Add to RideshareEventKinds
            tags = tags,
            content = encryptedContent
        )
    }

    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event
    ): XxxBackupData? {
        if (event.kind != RideshareEventKinds.XXX_BACKUP) return null
        if (event.pubKey != signer.pubKey) return null

        return try {
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            val data = mutableListOf<YourDataClass>()
            val dataArray = json.getJSONArray("data")
            for (i in 0 until dataArray.length()) {
                data.add(YourDataClass.fromJson(dataArray.getJSONObject(i)))
            }

            XxxBackupData(
                eventId = event.id,
                data = data,
                updatedAt = json.getLong("updated_at"),
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class XxxBackupData(
    val eventId: String,
    val data: List<YourDataClass>,
    val updatedAt: Long,
    val createdAt: Long
)
```

---

## Step 3: Add Event Kind Constant

Edit `common/src/main/java/com/ridestr/common/nostr/events/RideshareEventKinds.kt`:

```kotlin
const val XXX_BACKUP = 30177  // Use next available
```

---

## Step 4: Add NostrService Methods

Edit `common/src/main/java/com/ridestr/common/nostr/NostrService.kt`:

```kotlin
// Add backup method
suspend fun backupXxx(data: List<YourDataClass>): String? {
    val signer = keyManager.getSigner() ?: return null
    // Wait for relay connection...
    val event = XxxBackupEvent.create(signer, data)
    if (event != null) {
        relayManager.publish(event)
        return event.id
    }
    return null
}

// Add fetch method
suspend fun fetchXxxBackup(): XxxBackupData? {
    val signer = keyManager.getSigner() ?: return null
    val myPubKey = keyManager.getPubKeyHex() ?: return null
    // Wait for relay connection...
    // Subscribe and decrypt...
}
```

---

## Step 5: Create Sync Adapter

Create `common/src/main/java/com/ridestr/common/sync/XxxSyncAdapter.kt`:

```kotlin
package com.ridestr.common.sync

class XxxSyncAdapter(
    private val repository: XxxRepository,
    private val nostrService: NostrService
) : SyncableProfileData {

    override val kind: Int = RideshareEventKinds.XXX_BACKUP
    override val dTag: String = XxxBackupEvent.D_TAG
    override val syncOrder: Int = 4  // After existing types
    override val displayName: String = "Your Feature"

    override suspend fun fetchFromNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): SyncResult {
        val backupData = nostrService.fetchXxxBackup()
        if (backupData != null && backupData.data.isNotEmpty()) {
            repository.restoreFromBackup(backupData.data)
            return SyncResult.Success(backupData.data.size)
        }
        return SyncResult.NoData("No backup found")
    }

    override suspend fun publishToNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): String? {
        return nostrService.backupXxx(repository.data.value)
    }

    override fun hasLocalData(): Boolean = repository.hasData()
    override fun clearLocalData() = repository.clearAll()
}
```

---

## Step 6: Register in MainActivity

**For rider app** (`rider-app/src/main/java/com/ridestr/rider/MainActivity.kt`):
```kotlin
profileSyncManager.registerSyncable(XxxSyncAdapter(xxxRepository, nostrService))
```

**For driver app** (`drivestr/src/main/java/com/drivestr/app/MainActivity.kt`):
```kotlin
profileSyncManager.registerSyncable(XxxSyncAdapter(xxxRepository, nostrService))
```

---

## Step 7: Test Sync Flows

### Test 1: Fresh Install → Import Existing Key

1. Clear app storage
2. Import nsec
3. Verify data restores in correct order
4. Check logs for `ProfileSyncManager` sync messages

### Test 2: Logout → Login

1. Log out (clears local data)
2. Log back in with same key
3. Verify data restores

### Test 3: Auto-Backup on Change

1. Modify data locally
2. Verify backup published to relays
3. Check Kind 30177 events on relay

---

## Sync Order Guidelines

| Order | Data Type | Rationale |
|-------|-----------|-----------|
| 0 | Wallet | Needed for payments, highest priority |
| 1 | Ride History | May reference payments |
| 2 | Vehicles | Driver profile data |
| 3 | Saved Locations | Rider convenience data |
| 4+ | New features | Lower priority |

Lower sync order = synced first during key import.

---

## Files to Create/Modify Summary

### New Files
- `common/.../nostr/events/XxxBackupEvent.kt`
- `common/.../sync/XxxSyncAdapter.kt`

### Modified Files
- `common/.../nostr/events/RideshareEventKinds.kt` (add kind constant)
- `common/.../nostr/NostrService.kt` (add backup/fetch methods)
- `rider-app/.../MainActivity.kt` or `drivestr/.../MainActivity.kt` (register adapter)

---

## Common Pitfalls

1. **Forgetting to add event kind constant** - Compilation will fail
2. **Wrong sync order** - May cause data dependency issues
3. **Not testing fresh install flow** - Most common bug is sync not working on first login
4. **Not refreshing KeyManager** - If syncing right after login, call `keyManager.refreshFromStorage()`
5. **Not clearing local data on logout** - Old data leaks to next user

---

## Related Documentation

- [OVERVIEW.md](../architecture/OVERVIEW.md) - ProfileSyncManager architecture
- [NOSTR_EVENTS.md](../protocol/NOSTR_EVENTS.md) - Event kind registry
