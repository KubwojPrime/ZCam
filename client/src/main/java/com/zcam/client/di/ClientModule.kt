package com.zcam.client.di

import com.zcam.client.LocalAudioTransport
import com.zcam.client.LocalClient
import com.zcam.client.OkHttpLocalAudioTransport
import com.zcam.client.OkHttpLocalClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ClientModule {

    @Binds
    @Singleton
    abstract fun bindLocalClient(impl: OkHttpLocalClient): LocalClient

    @Binds
    @Singleton
    abstract fun bindLocalAudioTransport(impl: OkHttpLocalAudioTransport): LocalAudioTransport
}
