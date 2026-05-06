package com.zcam.security.di

import com.zcam.security.LocalSecurityManager
import com.zcam.security.SecurityManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindSecurityManager(impl: LocalSecurityManager): SecurityManager
}
