package com.roadflare.common.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * DI module for crypto-related classes.
 * KeyManager, SecureKeyStorage, and RoadFlareKeyManager use @Inject constructor
 * and are auto-discovered by Hilt — no explicit bindings needed here.
 * This module exists as the logical home for future qualifiers or test overrides.
 */
@Module
@InstallIn(SingletonComponent::class)
object CryptoModule
