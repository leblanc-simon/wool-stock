package eu.leblancsimon.woolstock.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** Dans Application Support, hors de la vue de l'utilisateur ; SQLite embarqué dans l'app. */
@OptIn(ExperimentalForeignApi::class)
internal fun iosDatabaseBuilder(): RoomDatabase.Builder<WoolStockDatabase> {
    val directory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val path = requireNotNull(directory?.path) { "Application Support introuvable" } + "/" + WoolStockDatabase.FILE_NAME
    return Room.databaseBuilder<WoolStockDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
}
