package com.mockonomy.data.log

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's only database. It currently holds just the request log — environments
 * live in files and will stay there (that file is what gets exported).
 */
@Database(
    entities = [RequestLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MockonomyDatabase : RoomDatabase() {
    abstract fun requestLogDao(): RequestLogDao
}
