package com.example.catalog.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        CatalogSyncEntity::class,
        CatalogCacheEntity::class
    ],
    version = LocalCatalogDatabase.SCHEMA_VERSION,
    exportSchema = false
)
abstract class LocalCatalogDatabase : RoomDatabase() {
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun catalogSyncDao(): CatalogSyncDao
    abstract fun catalogCacheDao(): CatalogCacheDao

    companion object {
        const val DATABASE_NAME = "flacdl_local_catalog.db"
        const val SCHEMA_VERSION = 1

        /**
         * Explicit migration registry. Future schema bumps MUST register an explicit
         * [Migration] here rather than using destructive fallback migrations.
         */
        val ALL_MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context): LocalCatalogDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                LocalCatalogDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        }

        fun createInMemory(context: Context): LocalCatalogDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                LocalCatalogDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
