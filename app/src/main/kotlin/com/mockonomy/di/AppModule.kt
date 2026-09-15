package com.mockonomy.di

import com.mockonomy.ui.screens.setup.AndroidLoopbackProbe
import com.mockonomy.ui.screens.setup.LoopbackProbe
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindLoopbackProbe(impl: AndroidLoopbackProbe): LoopbackProbe
}
