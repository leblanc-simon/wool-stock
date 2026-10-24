package eu.leblancsimon.woolstock.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [YarnReference::class],
    version = 2,
    exportSchema = true,
)
abstract class WoolStockDatabase : RoomDatabase() {
    abstract fun yarnDao(): YarnDao

    companion object {
        /** Le stock passe sur le serveur : seul le cache des références reste sur le téléphone. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS stock_entry")
                db.execSQL("DROP TABLE IF EXISTS yarn_lot")
            }
        }

        fun build(context: Context): WoolStockDatabase =
            Room.databaseBuilder(context, WoolStockDatabase::class.java, "wool-stock.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
