package com.example.mpvlibrary.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FolderEntity::class, VideoEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun videos(): VideoDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "UPDATE videos SET folderId = (SELECT MIN(canonical.id) FROM folders duplicate " +
                        "JOIN folders canonical ON canonical.treeUri = duplicate.treeUri " +
                        "WHERE duplicate.id = videos.folderId) " +
                        "WHERE folderId IN (SELECT duplicate.id FROM folders duplicate " +
                        "WHERE duplicate.id != (SELECT MIN(canonical.id) FROM folders canonical " +
                        "WHERE canonical.treeUri = duplicate.treeUri))",
                )
                database.execSQL(
                    "DELETE FROM folders WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM folders GROUP BY treeUri)",
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_folders_treeUri ON folders(treeUri)",
                )
            }
        }

        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDb::class.java, "library.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
