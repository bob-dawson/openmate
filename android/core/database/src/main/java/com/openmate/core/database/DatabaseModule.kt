package com.openmate.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabaseFactory(@ApplicationContext context: Context): DatabaseFactory {
        return DatabaseFactory(context)
    }

    @Provides
    @Singleton
    fun provideActiveDatabaseProvider(factory: DatabaseFactory): ActiveDatabaseProvider {
        return ActiveDatabaseProvider(factory)
    }

    @Provides
    @Singleton
    fun provideCacheDatabase(@ApplicationContext context: Context): CacheDatabase {
        return Room.databaseBuilder(
            context,
            CacheDatabase::class.java,
            "file_cache",
        ).fallbackToDestructiveMigration().build()
    }
}
