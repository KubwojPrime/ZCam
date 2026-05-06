package com.zcam.core.di

import com.zcam.core.dispatchers.DefaultDispatcher
import com.zcam.core.dispatchers.DefaultDispatcherProvider
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.dispatchers.IoDispatcher
import com.zcam.core.logging.TimberZCamLogger
import com.zcam.core.logging.ZCamLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindingsModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    @Binds
    @Singleton
    abstract fun bindLogger(impl: TimberZCamLogger): ZCamLogger
}

@Module
@InstallIn(SingletonComponent::class)
object CoreDispatchersModule {

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
