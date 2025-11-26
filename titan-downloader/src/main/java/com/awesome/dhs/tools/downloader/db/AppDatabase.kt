package com.awesome.dhs.tools.downloader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 2:05 AM
 * Description:
 **/


@Database(
    entities = [DownloadTaskEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(com.awesome.dhs.tools.downloader.db.TypeConverters::class) // 使用我们创建的转换器
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        private fun build(context: Context, dbName: String): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()
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

/**
 * 数据库迁移：从版本 1 到 2
 * 添加了 speedBps 列
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_tasks ADD COLUMN speedBps INTEGER NOT NULL DEFAULT 0")
    }
}