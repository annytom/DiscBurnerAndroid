package com.enterprise.discburner.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用数据库
 */
@Database(
    entities = [
        AuditLogEntity::class,
        BurnSessionEntity::class,
        BurnFileEntity::class,
        DeviceInfoEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class DiscBurnerDatabase : RoomDatabase() {

    abstract fun auditLogDao(): AuditLogDao
    abstract fun burnSessionDao(): BurnSessionDao
    abstract fun burnFileDao(): BurnFileDao
    abstract fun deviceInfoDao(): DeviceInfoDao

    companion object {
        @Volatile
        private var INSTANCE: DiscBurnerDatabase? = null

        fun getDatabase(context: Context): DiscBurnerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DiscBurnerDatabase::class.java,
                    "disc_burner_database"
                )
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // 预填充数据和初始化
        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // 数据库创建时的初始化
            }
        }
    }
}
