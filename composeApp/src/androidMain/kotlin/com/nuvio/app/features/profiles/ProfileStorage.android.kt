package com.nuvio.app.features.profiles

import android.content.Context
import android.content.SharedPreferences

actual object ProfileStorage {
    private const val preferencesName = "nuvio_profile_cache"
    private const val payloadKey = "profile_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(payloadKey, null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(payloadKey, payload)
            ?.apply()
    }
    actual fun loadKidsToken(configId: String): String? = preferences?.getString("kids_token_$configId", null)
    actual fun saveKidsToken(configId: String, token: String?) {
        preferences?.edit()?.putString("kids_token_$configId", token)?.apply()
    }
}