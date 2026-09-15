package com.mockonomy.server.di

import com.mockonomy.server.AndroidServerLauncher
import com.mockonomy.server.ServerLauncher
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
    abstract fun bindServerLauncher(impl: AndroidServerLauncher): ServerLauncher
}
