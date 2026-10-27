package eu.leblancsimon.woolstock.platform

import platform.Foundation.NSUserDefaults

/**
 * Réglages dans les NSUserDefaults de l'app (raison CA92.1 du manifeste de confidentialité).
 * Le jeton de session n'y est plus : il vit dans le trousseau (KeychainStore).
 */
internal class UserDefaultsStore(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : KeyValueStore {
    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) = defaults.setObject(value, forKey = key)

    override fun remove(key: String) = defaults.removeObjectForKey(key)
}
