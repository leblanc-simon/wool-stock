package io.leblanc.woolstock.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Copie locale du cache serveur EAN → marque/coloris : préremplit l'écran de confirmation
 * sans attendre le réseau. L'EAN identifie une référence, jamais un bain.
 */
@Entity(tableName = "yarn_reference")
data class YarnReference(
    @PrimaryKey val ean: String,
    val brand: String?,
    val colorCode: String?,
    val colorName: String?,
    val createdAt: Long,
)

/** Nom de coloris déjà saisi pour une marque, et combien de références l'emploient. */
data class ColorNameUse(
    val brand: String?,
    val colorName: String,
    val uses: Int,
)
