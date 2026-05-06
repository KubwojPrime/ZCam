package com.zcam.server.di

import com.zcam.server.LocalHttpServer
import com.zcam.server.ZCamHttpServer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerModule {

    @Binds
    @Singleton
    abstract fun bindLocalHttpServer(impl: ZCamHttpServer): LocalHttpServer
}
