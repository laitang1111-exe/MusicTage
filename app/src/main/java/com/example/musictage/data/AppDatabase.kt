package com.example.musictage.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SongEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 3 → 4：新增「歌词偏移」列。
         *
         * 只加一列、默认 0（不偏移），老数据一行都不会动。这里刻意写迁移而不是靠
         * `fallbackToDestructiveMigration` 清库重建——库里存着用户重命名过的歌名
         * 和已经关联好的歌词路径，清一次就全没了 喵~
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN lyricOffsetMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 4 → 5：新增「封面文件路径」列 喵~
         *
         * 背景（用户需求 ✓）：封面不再写进音频文件标签里 ✗ 改成和歌词完全一样的做法 ✓
         *   —— 在歌曲同目录放一个旁挂图片文件 ✓ 数据库只记它的路径 ✓
         *
         * 为什么这么改 ✓：
         *   1. 不再改写用户的音频文件本身 ✓ 原始文件永远保持原样 ✓ 零损坏风险 ✓
         *   2. 换封面只要覆盖/删除那个图片文件 ✓ 不用重写整个音频 ✓
         *   3. 与歌词（lrcPath）的做法完全一致 ✓ 心智模型统一 ✓
         *
         * 列可空 ✓ 所以 ALTER 不需要 DEFAULT ✓（老数据自动是 NULL ✓ 一行都不动 ✓）
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN coverPath TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "music_tage_db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
