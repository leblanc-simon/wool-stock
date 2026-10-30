package io.leblanc.woolstock.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

/** Le fichier des versions précédentes, ouvert avec le SQLite du système. */
internal fun androidDatabaseBuilder(context: Context): RoomDatabase.Builder<WoolStockDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<WoolStockDatabase>(appContext, WoolStockDatabase.FILE_NAME)
        .setDriver(AndroidSQLiteDriver())
}
