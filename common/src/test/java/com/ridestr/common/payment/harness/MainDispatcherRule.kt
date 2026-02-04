package com.ridestr.common.payment.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit Rule to override Dispatchers.Main for testing.
 *
 * This rule:
 * 1. Sets Dispatchers.Main to a TestDispatcher before each test
 * 2. Resets it after the test completes
 *
 * CRITICAL: Uses StandardTestDispatcher (NOT UnconfinedTestDispatcher) so that:
 * - Virtual time works correctly with advanceTimeBy() and advanceUntilIdle()
 * - Delay-based retry logic can be tested without real waiting
 *
 * Usage:
 * ```kotlin
 * @RunWith(RobolectricTestRunner::class)
 * class MyTest {
 *     @get:Rule
 *     val mainDispatcherRule = MainDispatcherRule()
 *
 *     @Test
 *     fun `test with virtual time`() = runTest(mainDispatcherRule.testDispatcher) {
 *         // Virtual time advances instantly through delays
 *         someOperationWithRetry()
 *         advanceUntilIdle()
 *
 *         // Assertions...
 *     }
 * }
 * ```
 *
 * Why this is needed:
 * - WalletService uses StateFlows which collect on Dispatchers.Main
 * - Without this rule, tests that assert on StateFlow values may hang or fail
 * - The TestDispatcher allows virtual time control for testing retry delays (1s, 2s, 4s)
 *
 * @param dispatcher The test dispatcher to use. Defaults to StandardTestDispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description?) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description?) {
        Dispatchers.resetMain()
    }
}
