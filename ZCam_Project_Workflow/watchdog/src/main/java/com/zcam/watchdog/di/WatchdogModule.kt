package com.zcam.watchdog.di

import com.zcam.watchdog.ProcessWatchdogManager
import com.zcam.watchdog.WatchdogManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WatchdogModule {

    @Binds
    @Singleton
    abstract fun bindWatchdogManager(impl: ProcessWatchdogManager): WatchdogManager
}
