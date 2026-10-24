package com.nuvio.app.features.profiles

internal expect object ProfileStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun loadKidsToken(configId: String): String?
    fun saveKidsToken(configId: String, token: String?)
}