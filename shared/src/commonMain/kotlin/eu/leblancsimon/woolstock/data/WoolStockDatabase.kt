package eu.leblancsimon.woolstock.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [YarnReference::class],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(WoolStockDatabaseConstructor::class)
abstract class WoolStockDatabase : RoomDatabase() {
    abstract fun yarnDao(): YarnDao

    companion object {
        /** Nom du fichier, inchangé depuis la première version Android. */
        const val FILE_NAME = "wool-stock.db"

        /** Le stock passe sur le serveur : seul le cache des références reste sur le téléphone. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE IF EXISTS stock_entry")
                connection.execSQL("DROP TABLE IF EXISTS yarn_lot")
            }
        }
    }
}

/** Implémenté par Room (KSP) pour chaque plateforme. */
@Suppress("KotlinNoActualForExpect")
expect object WoolStockDatabaseConstructor : RoomDatabaseConstructor<WoolStockDatabase> {
    override fun initialize(): WoolStockDatabase
}
