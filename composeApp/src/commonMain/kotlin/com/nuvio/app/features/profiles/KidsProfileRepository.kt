package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.isAnonymous
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonRow
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Clock

internal class KidsProfileException(val code: String, message: String) : IllegalStateException(message)

/** Shared mobile KidsScreen configuration and fail-closed title policy. */
object KidsProfileRepository {
    private val client by lazy { createApiHttpClient().config { followRedirects = false } }
    private val json = Json { ignoreUnknownKeys = true }
    private val configMutex = Mutex()
    val active: NuvioProfile? get() = ProfileRepository.state.value.activeProfile?.takeIf { it.kidsEnabled }

    internal fun normalizeService(raw: String): String {
        val url = Url(raw.trim().trimEnd('/'))
        val local = url.host in setOf("localhost", "127.0.0.1", "::1")
        require(url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty()) { "Enter a valid KidsScreen server address" }
        require(url.protocol == URLProtocol.HTTPS || (url.protocol == URLProtocol.HTTP && local)) { "KidsScreen needs HTTPS" }
        require(url.encodedPath in setOf("", "/")) { "Use the server address without a path" }
        return url.toString().trimEnd('/')
    }

    private suspend fun request(base: String, path: String, data: JsonObject? = null, token: String? = null, delete: Boolean = false): JsonObject {
        val response = client.request(normalizeService(base) + path) {
            method = when { delete -> HttpMethod.Delete; data != null -> HttpMethod.Post; else -> HttpMethod.Get }
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (data != null) { contentType(ContentType.Application.Json); setBody(data.toString()) }
        }
        check(response.status.value in 200..299) { "KidsScreen rejected the request. Check your connection and TMDB API key." }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun configure(profileId: Int, enabled: Boolean, maxRating: String, apiKey: String) = configMutex.withLock {
        configure(buildJsonObject {
            put("id", profileId); put("enabled", enabled); put("maxRating", maxRating); put("apiKey", apiKey)
            put("serviceUrl", ProfileRepository.state.value.profiles.first { it.profileIndex == profileId }.kidsServiceUrl ?: "https://kids-screen.vercel.app")
        })
    }

    private suspend fun configure(params: JsonObject) {
        if (AuthRepository.state.value.isAnonymous) throw KidsProfileException("SIGN_IN_REQUIRED", "Sign in to set up a kids profile")
        val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("Profile is required")
        val profile = ProfileRepository.state.value.profiles.firstOrNull { it.profileIndex == id } ?: error("Profile not found")
        if (id == 1) throw KidsProfileException("KIDS_SETUP_INVALID", "Keep the main profile as the parent profile")
        val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: error("Enabled is required")
        val rating = params["maxRating"]?.jsonPrimitive?.content ?: "G"
        if (rating !in setOf("G", "PG", "PG-13")) throw KidsProfileException("KIDS_SETUP_INVALID", "Choose G, PG or PG-13")
        val base = normalizeService(params["serviceUrl"]?.jsonPrimitive?.content ?: "https://kids-screen.vercel.app")
        val apiKey = params["apiKey"]?.jsonPrimitive?.content.orEmpty().trim()
        var configId = profile.kidsConfigId
        var created: JsonObject? = null
        if (enabled) {
            if (ProfileRepository.state.value.profiles.any { it.addonSyncProfileId == id }) throw KidsProfileException("KIDS_ADDON_DEPENDENTS", "Unlink profiles sharing this profile's add-ons first")
            created = prepareConfiguration(profile, rating, base, apiKey,
                managementToken = { profile.kidsConfigId?.let(ProfileStorage::loadKidsToken) },
                provision = { path, data, token -> request(base, path, data, token) },
            )
            if (created != null) {
                configId = created["id"]?.jsonPrimitive?.content
                if (configId?.matches(Regex("[A-Za-z0-9_-]{43}")) != true ||
                    created["managementToken"]?.jsonPrimitive?.content?.matches(Regex("[A-Za-z0-9_-]{43}")) != true) throw KidsProfileException("KIDSSCREEN_REQUEST_FAILED", "KidsScreen returned an invalid configuration")
            }
        }
        var policySaved = false
        var originalRows: List<AddonRow>? = null
        try {
            if (enabled) {
                originalRows = AddonRepository.readProfileAddonSnapshot(id)
                // Detach first so the managed catalog cannot alter the source profile.
                ProfileRepository.setAddonProfileLink(id, null)
                val rows = AddonRepository.readProfileAddonSnapshot(id).filterNot { row ->
                    com.nuvio.app.features.addons.isKidsConflictingAddon(row) ||
                    profile.kidsConfigId != null && row.url == "${profile.kidsServiceUrl}/${profile.kidsConfigId}/manifest.json"
                }
                AddonRepository.saveIndependentProfileAddons(id, rows + AddonRow("$base/$configId/manifest.json", "KidsScreen · $rating"))
            } else if (profile.kidsEnabled) {
                originalRows = AddonRepository.readProfileAddonSnapshot(id)
                AddonRepository.saveIndependentProfileAddons(id, originalRows.filterNot {
                    it.url == "${profile.kidsServiceUrl}/${profile.kidsConfigId}/manifest.json"
                })
            }
            val verdict = try { SupabaseProvider.client.postgrest.rpc("sync_set_kids_profile", buildJsonObject {
                put("p_profile_id", id); put("p_enabled", enabled); put("p_max_rating", rating)
                put("p_service_url", if (enabled) JsonPrimitive(base) else JsonNull)
                put("p_config_id", if (enabled) JsonPrimitive(configId!!) else JsonNull)
                put("p_guardian_pin", JsonNull); putSyncOriginClientId()
            }).decodeAs<JsonObject>() } catch (error: CancellationException) { throw error } catch (error: Exception) { throw KidsProfileException("KIDS_POLICY_SAVE_FAILED", "Kids profile settings could not be saved") }
            if (verdict["unlocked"]?.jsonPrimitive?.booleanOrNull != true) throw KidsProfileException("KIDS_POLICY_SAVE_FAILED", "Kids profile settings were not saved")
            policySaved = true
            created?.get("managementToken")?.jsonPrimitive?.content?.let { ProfileStorage.saveKidsToken(configId!!, it) }
            ProfileRepository.pullProfiles()
            if (ProfileRepository.state.value.profiles.first { it.profileIndex == id }.kidsEnabled != enabled) throw KidsProfileException("KIDS_POLICY_SAVE_FAILED", "Kids profile settings were not saved")
            if (enabled && apiKey.isNotBlank()) {
                com.nuvio.app.features.tmdb.TmdbSettingsRepository.configureKidsProfile(id, apiKey, enableByDefault = !profile.kidsEnabled)
                try {
                    com.nuvio.app.core.sync.ProfileSettingsSync.syncKidsTmdbSettings(id)
                } catch (error: CancellationException) { throw error } catch (_: Exception) {
                    error("Kids profile saved, but TMDB enrichment setup could not be synced. Re-enter the key and save again to finish setup.")
                }
            }
            if (profile.kidsConfigId != null && profile.kidsConfigId != configId) revoke(profile)
            if (!enabled) revoke(profile)
            clearVerdicts()
            if (ProfileRepository.activeProfileId == id) {
                com.nuvio.app.features.home.HomeRepository.clear()
                com.nuvio.app.features.search.SearchRepository.clear()
                com.nuvio.app.features.details.MetaDetailsRepository.clear()
                com.nuvio.app.features.catalog.CatalogRepository.clear()
                com.nuvio.app.features.streams.StreamsRepository.clear()
                com.nuvio.app.features.home.HomeRepository.refresh(AddonRepository.uiState.value.addons, force = true)
            }
            return
        } catch (error: Exception) {
            if (!policySaved) {
                originalRows?.let { rows -> runCatching { AddonRepository.saveIndependentProfileAddons(id, rows) } }
                if (profile.addonSyncProfileId != null) runCatching { ProfileRepository.setAddonProfileLink(id, profile.addonSyncProfileId) }
                if (created != null) runCatching { request(base, "/v1/configurations/$configId", token = created["managementToken"]?.jsonPrimitive?.content, delete = true) }
            }
            throw error
        }
    }

    /** Reuse the server-held key without downloading it or changing the live policy before commit. */
    internal suspend fun prepareConfiguration(
        profile: NuvioProfile,
        rating: String,
        base: String,
        apiKey: String,
        managementToken: () -> String?,
        provision: suspend (String, JsonObject, String?) -> JsonObject,
    ): JsonObject? {
        val unchanged = profile.kidsEnabled && profile.kidsConfigId != null &&
            profile.kidsMaxRating == rating && profile.kidsServiceUrl == base && apiKey.isBlank()
        if (unchanged) return null
        if (apiKey.isNotBlank()) {
            return provision("/v1/configurations", buildJsonObject { put("apiKey", apiKey); put("maxRating", rating) }, null)
        }
        if (profile.kidsEnabled && profile.kidsConfigId != null && profile.kidsServiceUrl == base) {
            val token = managementToken()?.takeIf { it.isNotBlank() }
                ?: throw KidsProfileException("KIDS_CONFIGURATION_ACCESS_REQUIRED", "This device is missing access to manage the saved KidsScreen configuration. Reconnect it with your TMDB key once")
            return provision("/v1/configurations/${profile.kidsConfigId}/clone", buildJsonObject { put("maxRating", rating) }, token)
        }
        throw KidsProfileException("TMDB_KEY_REQUIRED", "Enter your TMDB API key to set up this kids profile")
    }

    private suspend fun revoke(profile: NuvioProfile) {
        val id = profile.kidsConfigId ?: return
        val token = ProfileStorage.loadKidsToken(id) ?: return
        runCatching { request(profile.kidsServiceUrl!!, "/v1/configurations/$id", token = token, delete = true); ProfileStorage.saveKidsToken(id, null) }
    }

    internal data class Ref(val type: String, val id: String)
    private fun mediaRef(type: String?, id: String?): Ref? = if (type in setOf("movie", "series") && !id.isNullOrBlank()) Ref(type!!, id) else null
    internal suspend fun check(
        profile: NuvioProfile, refs: Set<Ref>,
        lookup: suspend (NuvioProfile, List<Ref>) -> Set<Ref> = ::lookupRatings,
    ): Set<Ref> = verdictMutex.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        fun key(ref: Ref) = "${profile.userId}|${profile.kidsServiceUrl}|${profile.kidsConfigId}|${profile.kidsMaxRating}|${ref.type}|${ref.id}"
        val missing = refs.filter { it.type in setOf("movie", "series") }.filter { verdicts[key(it)]?.expires?.let { expiry -> expiry > now } != true }
        for (batch in missing.chunked(200)) {
            val allowed = try {
                lookup(profile, batch)
            } catch (error: CancellationException) { throw error } catch (_: Exception) { emptySet() }
            if (verdicts.size > 4000) verdicts.clear()
            batch.forEach { ref -> verdicts[key(ref)] = Verdict(ref in allowed, now + if (ref in allowed) 300_000 else 15_000) }
        }
        refs.filter { verdicts[key(it)]?.let { verdict -> verdict.allowed && verdict.expires > Clock.System.now().toEpochMilliseconds() } == true }.toSet()
    }


    private suspend fun lookupRatings(profile: NuvioProfile, batch: List<Ref>): Set<Ref> {
        val result = request(profile.kidsServiceUrl!!, "/${profile.kidsConfigId}/check", buildJsonObject {
            put("items", JsonArray(batch.map { buildJsonObject { put("type", it.type); put("id", it.id) } }))
        })
        return (result["items"] as? JsonArray).orEmpty().mapNotNull { item ->
            val row = item.jsonObject
            if (row["allowed"]?.jsonPrimitive?.booleanOrNull == true) mediaRef(row["type"]?.jsonPrimitive?.content, row["id"]?.jsonPrimitive?.content) else null
        }.toSet()
    }

    private data class Verdict(val allowed: Boolean, val expires: Long)
    private val verdicts = mutableMapOf<String, Verdict>()
    private val verdictMutex = Mutex()
    private suspend fun clearVerdicts() = verdictMutex.withLock { verdicts.clear() }

    suspend fun requireMedia(type: String?, id: String?) {
        val profile = active ?: return
        val ref = mediaRef(type, id) ?: error("This title is unavailable in this kids profile")
        check(ref in check(profile, setOf(ref))) { "This title could not be verified for this kids profile" }
        check(active == profile) { "Profile changed. Open the title again." }
    }

    suspend fun <T> filter(items: List<T>, type: (T) -> String, id: (T) -> String): List<T> {
        val profile = active ?: return items
        val allowed = check(profile, items.mapNotNull { mediaRef(type(it), id(it)) }.toSet())
        if (active != profile) return emptyList()
        return items.filter { mediaRef(type(it), id(it)) in allowed }
    }
}
