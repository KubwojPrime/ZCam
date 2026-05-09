package com.zcam.audio.di

import com.zcam.audio.AndroidPushToTalkManager
import com.zcam.audio.AndroidSystemVolumeController
import com.zcam.audio.PushToTalkManager
import com.zcam.audio.SystemVolumeController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    @Singleton
    abstract fun bindPushToTalkManager(impl: AndroidPushToTalkManager): PushToTalkManager

    @Binds
    @Singleton
    abstract fun bindSystemVolumeController(impl: AndroidSystemVolumeController): SystemVolumeController
}
