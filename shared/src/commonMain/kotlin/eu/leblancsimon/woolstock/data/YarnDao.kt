package eu.leblancsimon.woolstock.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface YarnDao {
    @Query("SELECT * FROM yarn_reference WHERE ean = :ean")
    suspend fun reference(ean: String): YarnReference?

    @Query("SELECT * FROM yarn_reference")
    suspend fun references(): List<YarnReference>

    /** Marques déjà saisies, les plus employées d'abord : elles l'emportent sur leurs variantes. */
    @Query("SELECT brand FROM yarn_reference WHERE brand IS NOT NULL GROUP BY brand ORDER BY COUNT(*) DESC, brand COLLATE NOCASE")
    suspend fun brands(): List<String>

    @Query(
        """
        SELECT brand, colorName, COUNT(*) AS uses FROM yarn_reference
        WHERE colorName IS NOT NULL GROUP BY brand, colorName ORDER BY uses DESC, colorName COLLATE NOCASE
        """,
    )
    suspend fun colorNames(): List<ColorNameUse>

    @Upsert
    suspend fun upsertReference(reference: YarnReference)

    @Upsert
    suspend fun upsertReferences(references: List<YarnReference>)
}
