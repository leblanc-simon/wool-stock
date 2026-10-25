package eu.leblancsimon.woolstock.platform

/** Réglages gardés sur l'appareil : SharedPreferences sur Android, NSUserDefaults sur iOS. */
interface KeyValueStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun remove(key: String)
}
