package com.roadflare.common.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * DI module for Nostr infrastructure classes.
 * NostrService, RelayManager, SubscriptionManager, NostrCryptoHelper,
 * and RoadFlareDomainService all use @Singleton @Inject constructor
 * and are auto-discovered by Hilt.
 * This module exists as the logical home for future qualifiers or test overrides.
 */
@Module
@InstallIn(SingletonComponent::class)
object NostrModule
