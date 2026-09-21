package app.maskan.chat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, FolderEntity::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun folderDao(): FolderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN providerId TEXT NOT NULL DEFAULT 'deepseek'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN modelId TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageBase64 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN imageMimeType TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT DEFAULT NULL")
            }
        }

        // The one migration v2.5 allows itself: a video job id must survive process death, and
        // the message row is the only place that is both durable and already tied to the bubble.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN videoJobId TEXT DEFAULT NULL")
            }
        }

        /**
         * 2.6: a folder becomes a project. Two nullable TEXT columns, no data rewrite, so an
         * upgrade over a database full of real chats is two ALTERs and nothing else.
         *
         * Hand-written rather than a destructive fallback on purpose - the fallback would drop
         * every conversation on the phone, and this database is the only copy there is.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN instructions TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN memory TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val factory = SupportOpenHelperFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "privacyai_database"
                )
                    .openHelperFactory(factory)
                    .addMigrations(
                        MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
