package com.roadflare.rider.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * DI module for ride-specific classes.
 *
 * RideSessionManager, ChatCoordinator, and FareCoordinator use
 * @Singleton @Inject constructor and are auto-discovered by Hilt.
 *
 * This module exists as the logical home for future qualifiers,
 * @Provides methods, or test overrides specific to the ride flow.
 */
@Module
@InstallIn(SingletonComponent::class)
object RideModule
