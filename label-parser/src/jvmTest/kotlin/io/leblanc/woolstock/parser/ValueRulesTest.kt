package io.leblanc.woolstock.parser

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValueRulesTest {
    @ParameterizedTest
    @ValueSource(strings = ["4512", "A23", "12-34", "7/12", "583921", "0", "K1234"])
    fun `accepte les bains plausibles`(value: String) {
        assertTrue(ValueRules.isPlausibleDyeLot(value))
    }

    @ParameterizedTest
    @ValueSource(strings = ["50g", "125m", "3,5mm", "40°", "100%", "3614090123456", "Marine", "", "12345678901", "-12"])
    fun `rejette poids, métrages, aiguilles, températures, EAN et mots`(value: String) {
        assertFalse(ValueRules.isPlausibleDyeLot(value))
    }

    @Test
    fun `rejette une valeur suivie d'une unité`() {
        assertFalse(ValueRules.isPlausibleDyeLot("50", following = " g / 125 m"))
        assertFalse(ValueRules.isPlausibleDyeLot("125", following = " m"))
        assertFalse(ValueRules.isPlausibleDyeLot("3-4", following = " mm"))
        assertFalse(ValueRules.isPlausibleDyeLot("30", following = " °C"))
        assertTrue(ValueRules.isPlausibleDyeLot("4512", following = " Marine"))
    }

    @Test
    fun `rejette un fragment de l'EAN`() {
        assertFalse(ValueRules.isPlausibleDyeLot("158432", ean = "4033493158432"))
        assertTrue(ValueRules.isPlausibleDyeLot("812", ean = "4033493158432"))
    }

    @ParameterizedTest
    @CsvSource("4O71,4071", "1I85,1185", "12S4,1254", "4B12,4812", "l234,1234", "B12,B12", "S123,S123", "A123,A123", "OOA,OOA", "Marine,Marine")
    fun `corrige prudemment les confusions OCR`(raw: String, expected: String) {
        assertEquals(expected, ValueRules.normalizeDigits(raw))
    }

    @Test
    fun `reconnaît les mots de nom de coloris`() {
        assertTrue(ValueRules.isColorNameWord("Marine"))
        assertTrue(ValueRules.isColorNameWord("Bleu-ciel"))
        assertFalse(ValueRules.isColorNameWord("Bain"))
        assertFalse(ValueRules.isColorNameWord("1001"))
        assertFalse(ValueRules.isColorNameWord("g"))
    }
}
