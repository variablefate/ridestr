package com.ridestr.common.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProfileSyncManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        ProfileSyncManager.clearInstance()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
        ProfileSyncManager.clearInstance()
    }

    @Test
    fun `checkAndSyncRidestrData with no syncables emits retryable Error`() = runTest {
        val psm = ProfileSyncManager.getInstance(context, listOf("wss://example.com"))

        // Call without registering any syncables
        psm.checkAndSyncRidestrData()

        val state = psm.syncState.value
        assertTrue("Expected Error state but got $state", state is ProfileSyncState.Error)
        val error = state as ProfileSyncState.Error
        assertTrue("Expected retryable=true", error.retryable)
    }

    @Test
    fun `checkAndSyncRidestrData with no syncables does not reach NoDataFound`() = runTest {
        val psm = ProfileSyncManager.getInstance(context, listOf("wss://example.com"))

        psm.checkAndSyncRidestrData()

        val state = psm.syncState.value
        assertFalse(
            "Should not fall through to NoDataFound",
            state is ProfileSyncState.NoDataFound
        )
    }
}
