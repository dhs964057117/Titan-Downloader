package com.awesome.dhs.tools.downloader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 2:05 AM
 * Description:
 **/


@Database(
    entities = [DownloadTaskEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(com.awesome.dhs.tools.downloader.db.TypeConverters::class) // 使用我们创建的转换器
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        private fun build(context: Context, dbName: String): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
        }

        @Volatile
        var instance: AppDatabase? = null

        fun instance(context: Context, dbName: String): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context, dbName).also {
                    instance = it
                }
            }
        }
    }
}