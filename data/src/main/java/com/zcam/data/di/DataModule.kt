package com.zcam.data.di

import com.zcam.core.domain.settings.ClientSessionRepository
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeStateRepository
import com.zcam.core.domain.settings.RuntimeCrashRepository
import com.zcam.data.DataStoreClientSessionRepository
import com.zcam.data.DataStoreRuntimeCrashRepository
import com.zcam.data.DataStoreRuntimeSettingsRepository
import com.zcam.data.DataStoreRuntimeStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindClientSessionRepository(
        impl: DataStoreClientSessionRepository
    ): ClientSessionRepository

    @Binds
    @Singleton
    abstract fun bindRuntimeSettingsRepository(
        impl: DataStoreRuntimeSettingsRepository
    ): RuntimeSettingsRepository

    @Binds
    @Singleton
    abstract fun bindRuntimeStateRepository(
        impl: DataStoreRuntimeStateRepository
    ): RuntimeStateRepository

    @Binds
    @Singleton
    abstract fun bindRuntimeCrashRepository(
        impl: DataStoreRuntimeCrashRepository
    ): RuntimeCrashRepository
}
