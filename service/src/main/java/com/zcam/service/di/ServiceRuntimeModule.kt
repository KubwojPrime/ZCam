package com.zcam.service.di

import com.zcam.service.runtime.DefaultRetryBackoffScheduler
import com.zcam.service.runtime.InMemoryRuntimeHealthRepository
import com.zcam.service.runtime.RecoveryPolicy
import com.zcam.service.runtime.RetryBackoffScheduler
import com.zcam.service.runtime.RuntimeHealthRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceBindingsModule {

    @Binds
    @Singleton
    abstract fun bindRuntimeHealthRepository(impl: InMemoryRuntimeHealthRepository): RuntimeHealthRepository

    @Binds
    @Singleton
    abstract fun bindRetryBackoffScheduler(impl: DefaultRetryBackoffScheduler): RetryBackoffScheduler
}

@Module
@InstallIn(SingletonComponent::class)
object ServicePolicyModule {

    @Provides
    @Singleton
    fun provideRecoveryPolicy(): RecoveryPolicy = RecoveryPolicy()
}
