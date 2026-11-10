package com.nuvio.app.features.trailer

import android.net.Uri
import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.IPv4FirstDns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val MAX_REDIRECT_HOPS = 10
private const val HTTP_TEMPORARY_REDIRECT = 307
private const val HTTP_PERMANENT_REDIRECT = 308
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/**
 * Minimal cookie store for the extractor.
 *
 * Google answers traffic it flags with a bounce through `google.com/sorry` and sets a
 * `GOOGLE_ABUSE_EXEMPTION` cookie for `youtube.com` that lets the next hop through.
 * OkHttp only retains cookies when a jar is configured; without one the bounce loops
 * until OkHttp gives up with "Too many follow-up requests: 21", which is what made every
 * trailer fail. iOS gets this for free from NSURLSession, so Android needs it explicitly.
 *
 * Parsing is deliberately lenient (no public-suffix resolution) because the extractor
 * only talks to google.com/youtube.com hosts.
 */
internal class TrailerCookieStore {
    private val cookiesByDomain = mutableMapOf<String, MutableMap<String, String>>()

    fun headerFor(host: String): String? = synchronized(cookiesByDomain) {
        val cookies = cookiesByDomain
            .filterKeys { domain -> domainMatches(domain, host) }
            .values
            .flatMap { it.entries }
        if (cookies.isEmpty()) {
            null
        } else {
            cookies.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    fun save(host: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        synchronized(cookiesByDomain) {
            for (header in setCookieHeaders) {
                val parts = header.split(";")
                val nameValue = parts.firstOrNull()?.trim().orEmpty()
                if (!nameValue.contains("=")) continue
                val name = nameValue.substringBefore("=").trim()
                val value = nameValue.substringAfter("=").trim()
                if (name.isEmpty()) continue
                val domain = parts
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("domain=", ignoreCase = true) }
                    ?.substringAfter("=")
                    ?.trim()
                    ?.trimStart('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: host
                val expired = parts.any { part ->
                    val lower = part.trim().lowercase()
                    lower == "max-age=0" || lower.startsWith("max-age=-")
                }
                val cookies = cookiesByDomain.getOrPut(domain) { mutableMapOf() }
                if (expired) cookies.remove(name) else cookies[name] = value
            }
        }
    }

    fun clear() {
        synchronized(cookiesByDomain) { cookiesByDomain.clear() }
    }

    private fun domainMatches(cookieDomain: String, host: String): Boolean =
        host.equals(cookieDomain, ignoreCase = true) ||
            host.endsWith(".$cookieDomain", ignoreCase = true)
}

internal object TrailerExtractionPlatform {
    private val log = Logger.withTag(TRAILER_EXTRACTOR_TAG)

    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    private val cookieStore = TrailerCookieStore()

    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // Redirects are followed by hand in performRequest so the hop that loops is
        // visible in logcat instead of surfacing as "Too many follow-up requests".
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.IO) {
        val client = httpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()

        var currentUrl = url
        var currentMethod = method.uppercase()
        var currentBody = body
        var hops = 0

        while (true) {
            val request = buildRequest(currentUrl, currentMethod, currentBody, headers)
            val response = client.newCall(request).execute()
            val code = response.code
            val location = response.header("Location")
            cookieStore.save(response.request.url.host, response.headers("set-cookie"))

            if (code !in REDIRECT_CODES || location.isNullOrBlank()) {
                return@withContext response.use {
                    TrailerRequestResponse(
                        ok = it.isSuccessful,
                        status = it.code,
                        statusText = it.message,
                        url = it.request.url.toString(),
                        body = it.body?.string().orEmpty(),
                    )
                }
            }

            if (hops >= MAX_REDIRECT_HOPS) {
                response.close()
                log.w { "Trailer request gave up after $MAX_REDIRECT_HOPS redirects: $currentUrl -> $location" }
                return@withContext TrailerRequestResponse(
                    ok = false,
                    status = code,
                    statusText = "Redirect limit reached",
                    url = currentUrl,
                    body = "",
                )
            }

            val nextUrl = response.request.url.resolve(location)
            log.d { "Redirect $code $currentUrl -> ${nextUrl ?: location}" }
            response.close()

            if (nextUrl == null) {
                return@withContext TrailerRequestResponse(
                    ok = false,
                    status = code,
                    statusText = "Unresolvable redirect",
                    url = currentUrl,
                    body = "",
                )
            }
            if (code != HTTP_TEMPORARY_REDIRECT && code != HTTP_PERMANENT_REDIRECT) {
                currentMethod = "GET"
                currentBody = null
            }
            currentUrl = nextUrl.toString()
            hops += 1
        }

        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Redirect handler returned for $url")
    }

    private fun buildRequest(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): Request {
        val requestUrl: HttpUrl = url.toHttpUrl()
        val builder = Request.Builder()
            .url(requestUrl)
            .headers(buildHeaders(headers))

        if (headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            cookieStore.headerFor(requestUrl.host)?.let { builder.header("Cookie", it) }
        }

        when (method) {
            "POST" -> builder.post((body ?: "").toRequestBody())
            "PUT" -> builder.put((body ?: "").toRequestBody())
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        return builder.build()
    }

    suspend fun buildPlaybackSource(
        bestManifest: ManifestCandidate?,
        bestProgressive: StreamCandidate?,
        bestVideo: StreamCandidate?,
        bestAudio: StreamCandidate?,
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val bestCombinedIsManifest = bestManifest != null &&
            (bestProgressive == null || bestManifest.height > bestProgressive.height)

        val combinedUrl = if (bestCombinedIsManifest) {
            bestManifest.selectedVariantUrl
        } else {
            bestProgressive?.url
        }

        val separatedVideoUrl = bestVideo?.url?.let { resolveReachableUrlOrNull(it) }
        val combinedCandidateUrl = combinedUrl?.let { resolveReachableUrlOrNull(it) }
        val videoUrl = separatedVideoUrl ?: combinedCandidateUrl ?: return@withContext null
        val audioUrl = if (!separatedVideoUrl.isNullOrBlank()) {
            bestAudio?.url?.let { resolveReachableUrlOrNull(it) }
        } else {
            null
        }

        TrailerPlaybackSource(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
        )
    }

    private suspend fun resolveReachableUrlOrNull(url: String): String? {
        if (!url.contains("googlevideo.com")) return url
        val uri = Uri.parse(url)
        val mnParam = uri.getQueryParameter("mn") ?: return url
        val servers = mnParam.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (servers.size < 2) {
            return if (isUrlReachable(url)) url else null
        }

        val host = uri.host ?: return if (isUrlReachable(url)) url else null
        val candidates = mutableListOf(url)
        servers.forEachIndexed { index, server ->
            val altHost = host
                .replaceFirst(Regex("^rr\\d+---"), "rr${index + 1}---")
                .replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), server)
            if (altHost != host) {
                candidates += url.replace(host, altHost)
            }
        }

        if (candidates.size == 1) {
            return if (isUrlReachable(candidates[0])) candidates[0] else null
        }

        val result = CompletableDeferred<String>()
        val probeScope = CoroutineScope(Dispatchers.IO)
        candidates.forEach { candidate ->
            probeScope.launch {
                if (isUrlReachable(candidate)) {
                    result.complete(candidate)
                }
            }
        }

        return try {
            withTimeoutOrNull(2_000L) { result.await() }
        } finally {
            probeScope.cancel()
        }
    }

    private fun isUrlReachable(url: String): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .headers(buildHeaders(defaultHeaders))
                .build()

            probeClient.newCall(request).execute().use { response ->
                response.code in 200..299
            }
        }.getOrDefault(false)
    }

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", defaultHeaders.getValue("user-agent"))
        }
        return headers.build()
    }
}
