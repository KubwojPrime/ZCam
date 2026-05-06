package com.zcam.storage.di

import com.zcam.storage.LocalLoopRecordingManager
import com.zcam.storage.LoopRecordingManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindLoopRecordingManager(impl: LocalLoopRecordingManager): LoopRecordingManager
}
