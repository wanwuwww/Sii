package com.sii.browser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sii 结构化数据存储（Room）。书签/历史（v1）+ 下载任务/分块（v2）+ 主页收藏（v3）。
 */
@Database(
    entities = [
        BookmarkItem::class, BookmarkFolder::class, HistoryItem::class,
        DownloadTask::class, DownloadChunk::class, FavoriteItem::class, RuleEntity::class,
        ScriptEntity::class, SiteConfigEntity::class, TabEntity::class, PasswordItem::class,
        ReadLaterItem::class
    ],
    version = 11,
    exportSchema = false
)
abstract class SiiDatabase : RoomDatabase() {

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun historyDao(): HistoryDao

    abstract fun downloadDao(): DownloadDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun ruleDao(): RuleDao

    abstract fun scriptDao(): ScriptDao

    abstract fun siteConfigDao(): SiteConfigDao

    abstract fun tabDao(): TabDao

    abstract fun passwordDao(): PasswordDao

    abstract fun readLaterDao(): ReadLaterDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_tasks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`header` TEXT, `mimetype` TEXT, `path` TEXT, " +
                        "`file_uri` TEXT, `downloaded_size` INTEGER NOT NULL, " +
                        "`total_size` INTEGER NOT NULL, `chunks` INTEGER NOT NULL, " +
                        "`priority` INTEGER NOT NULL, `state` INTEGER NOT NULL, " +
                        "`error_message` TEXT, `flags` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_chunks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`task_id` INTEGER NOT NULL, `start` INTEGER NOT NULL, " +
                        "`length` INTEGER NOT NULL, `downloaded` INTEGER NOT NULL, " +
                        "`flags` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (" +
                        "`f_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`f_url` TEXT NOT NULL, `f_title` TEXT NOT NULL, " +
                        "`f_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scripts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `author` TEXT, `url` TEXT, " +
                        "`match` TEXT NOT NULL, `exclude` TEXT, `runAt` TEXT NOT NULL, " +
                        "`code` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `site_configs` (" +
                        "`host` TEXT PRIMARY KEY NOT NULL, " +
                        "`config` TEXT NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tabs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `lastVisitedAt` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `passwords` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`host` TEXT NOT NULL, `username` TEXT NOT NULL, " +
                        "`cipher` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `rules` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `read_later` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`file_path` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`read_flag` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `bookmark_items` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `bookmark_items` ADD COLUMN `last_opened_at` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun create(context: Context): SiiDatabase =
            Room.databaseBuilder(context, SiiDatabase::class.java, "sii.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                )
                .build()
    }
}
