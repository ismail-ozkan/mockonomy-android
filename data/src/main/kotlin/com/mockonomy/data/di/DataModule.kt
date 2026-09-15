package com.mockonomy.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.mockonomy.data.AdminApiSettingsStore
import com.mockonomy.data.AppearanceSettingsStore
import com.mockonomy.data.BodyStore
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.FileBodyStore
import com.mockonomy.data.FileEnvironmentStore
import com.mockonomy.data.OnboardingStore
import com.mockonomy.data.ProxyPrivacyStore
import com.mockonomy.data.log.LogSettingsStore
import com.mockonomy.data.log.MockonomyDatabase
import com.mockonomy.data.log.RequestLogDao
import com.mockonomy.data.log.RequestLogSink
import com.mockonomy.data.log.RequestLogStore
import com.mockonomy.data.log.RoomRequestLogStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import javax.inject.Singleton

/**
 * `@Singleton` so `:server` (the foreground service) and `:app` (ViewModels) see the
 * same [EnvironmentStore] instance — both modules read and write these files.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideEnvironmentsRoot(@ApplicationContext context: Context): Path =
        context.filesDir.absolutePath.toPath().resolve("environments")

    @Provides
    @Singleton
    fun provideEnvironmentStore(root: Path): EnvironmentStore =
        FileEnvironmentStore(FileSystem.SYSTEM, root)

    @Provides
    @Singleton
    fun provideBodyStore(@ApplicationContext context: Context): BodyStore =
        FileBodyStore(
            FileSystem.SYSTEM,
            context.filesDir.absolutePath.toPath().resolve("bodies"),
        )

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("mockonomy_settings") },
    )

    @Provides
    @Singleton
    fun provideOnboardingStore(dataStore: DataStore<Preferences>): OnboardingStore =
        OnboardingStore(dataStore)

    @Provides
    @Singleton
    fun provideProxyPrivacyStore(dataStore: DataStore<Preferences>): ProxyPrivacyStore =
        ProxyPrivacyStore(dataStore)

    @Provides
    @Singleton
    fun provideAdminApiSettingsStore(dataStore: DataStore<Preferences>): AdminApiSettingsStore =
        AdminApiSettingsStore(dataStore)

    @Provides
    @Singleton
    fun provideAppearanceSettingsStore(dataStore: DataStore<Preferences>): AppearanceSettingsStore =
        AppearanceSettingsStore(dataStore)

    // --- Request log ---

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MockonomyDatabase =
        Room.databaseBuilder(context, MockonomyDatabase::class.java, "mockonomy.db")
            // Logs are disposable: if the schema changes, recreating the table is the
            // right trade-off instead of writing a migration. The user only loses old
            // request records, never their configuration.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun provideRequestLogDao(database: MockonomyDatabase): RequestLogDao = database.requestLogDao()

    @Provides
    @Singleton
    fun provideLogSettingsStore(dataStore: DataStore<Preferences>): LogSettingsStore =
        LogSettingsStore(dataStore)

    /**
     * The store has its own scope: its write loop lives as long as the app process and
     * is not tied to the caller's (the server's) lifecycle. The IO dispatcher is chosen
     * because the work is disk writes.
     */
    @Provides
    @Singleton
    fun provideRequestLogStore(
        dao: RequestLogDao,
        settings: LogSettingsStore,
    ): RequestLogStore = RoomRequestLogStore(
        dao = dao,
        retention = settings.retention,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    @Provides
    @Singleton
    fun provideRequestLogSink(store: RequestLogStore): RequestLogSink = store
}
