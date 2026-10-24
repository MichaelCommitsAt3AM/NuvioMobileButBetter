package com.nuvio.app.features.profiles

import platform.Foundation.NSUserDefaults

actual object ProfileStorage {
    private const val payloadKey = "profile_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
    actual fun loadKidsToken(configId: String): String? = NSUserDefaults.standardUserDefaults.stringForKey("kids_token_$configId")
    actual fun saveKidsToken(configId: String, token: String?) {
        NSUserDefaults.standardUserDefaults.setObject(token, forKey = "kids_token_$configId")
    }
}