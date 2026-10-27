package eu.leblancsimon.woolstock.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Secrets (le jeton de session) dans le trousseau d'iOS plutôt que dans les NSUserDefaults :
 * chiffrés, réservés à cet iPhone, absents des sauvegardes iCloud ou du Mac et jamais
 * transférés vers un autre appareil (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly).
 * Chaque clé est un mot de passe générique du service [service].
 */
@OptIn(ExperimentalForeignApi::class)
internal class KeychainStore(private val service: String) : KeyValueStore {
    override fun getString(key: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(key, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) {
            SecItemCopyMatching(it, result.ptr)
        }
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        decode(data)
    }

    override fun putString(key: String, value: String) {
        remove(key)
        val data = CFBridgingRetain(encode(value))
        try {
            withQuery(key, kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) {
                SecItemAdd(it, null)
            }
        } finally {
            CFRelease(data)
        }
    }

    override fun remove(key: String) {
        withQuery(key) { SecItemDelete(it) }
    }

    /** Appelle [block] avec la requête de [key] (service, compte) complétée de [extra], puis la libère. */
    private fun <T> withQuery(key: String, vararg extra: Pair<CFStringRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> T): T {
        val query: CFMutableDictionaryRef? = CFDictionaryCreateMutable(
            null,
            (3 + extra.size).convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(key)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, serviceRef)
            CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
            extra.forEach { (name, value) -> CFDictionaryAddValue(query, name, value) }
            return block(query)
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
            CFRelease(query)
        }
    }

    @OptIn(BetaInteropApi::class)
    private fun encode(value: String): NSData {
        val bytes = value.encodeToByteArray()
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
    }

    @OptIn(BetaInteropApi::class)
    private fun decode(data: NSData): String? = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
}
