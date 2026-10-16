package com.nuvio.app.features.debrid

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PremiumizeDeviceAuthTest {
    @Test
    fun `documented device code response creates a usable session`() {
        val response = DebridApiJson.json.decodeFromString<PremiumizeDeviceAuthorizationDto>(
            """{"verification_uri":"https://www.premiumize.me/device","user_code":"abcd-1234","device_code":"device-secret","expires_in":600,"interval":5}""",
        )
        val session = assertNotNull(
            premiumizeDeviceAuthorizationFromResponse(
                DebridApiResponse(status = 200, body = response, rawBody = ""),
                DebridProviders.PREMIUMIZE_ID,
            ),
        )
        assertEquals("device-secret", session.deviceCode)
        assertEquals("abcd-1234", session.userCode)
        assertEquals("https://www.premiumize.me/device", session.verificationUrl)
        assertEquals(session.verificationUrl, session.friendlyVerificationUrl)
        assertEquals(5, session.intervalSeconds)
        assertNull(
            premiumizeDeviceAuthorizationFromResponse(
                DebridApiResponse(status = 400, body = response, rawBody = ""),
                DebridProviders.PREMIUMIZE_ID,
            ),
        )
    }

    @Test
    fun `unknown and malformed token errors stop polling without exposing raw responses`() {
        assertTrue(
            premiumizeDeviceAuthorizationTokenResult(tokenError("invalid_client")) is
                DebridDeviceAuthorizationTokenResult.Failed,
        )
        assertEquals(
            DebridDeviceAuthorizationTokenResult.Failed(null),
            premiumizeDeviceAuthorizationTokenResult(
                DebridApiResponse(status = 400, body = null, rawBody = "malformed response"),
            ),
        )
    }

    @Test
    fun `distinguishes pending from requests to slow down polling`() {
        assertEquals(
            DebridDeviceAuthorizationTokenResult.Pending,
            premiumizeDeviceAuthorizationTokenResult(tokenError("authorization_pending")),
        )
        assertEquals(
            DebridDeviceAuthorizationTokenResult.SlowDown,
            premiumizeDeviceAuthorizationTokenResult(tokenError("slow_down")),
        )
    }

    @Test
    fun `maps success expired denied and invalid oauth states`() {
        assertTrue(
            premiumizeDeviceAuthorizationTokenResult(
                DebridApiResponse(
                    status = 200,
                    body = PremiumizeDeviceTokenDto(accessToken = "pm-token", tokenType = "Bearer"),
                    rawBody = "",
                ),
            ) is DebridDeviceAuthorizationTokenResult.Authorized,
        )
        assertEquals(
            DebridDeviceAuthorizationTokenResult.Expired,
            premiumizeDeviceAuthorizationTokenResult(tokenError("invalid_grant")),
        )
        assertTrue(
            premiumizeDeviceAuthorizationTokenResult(tokenError("access_denied")) is
                DebridDeviceAuthorizationTokenResult.Failed,
        )
    }

    @Test
    fun `missing Premiumize client id fails before device flow starts`() = runBlocking {
        val api = PremiumizeDebridProviderApi(clientIdProvider = { "" })

        val failed = try {
            api.startDeviceAuthorization("Nuvio")
            false
        } catch (_: IllegalStateException) {
            true
        }

        assertTrue(failed)
    }

    private fun tokenError(error: String): DebridApiResponse<PremiumizeDeviceTokenDto> =
        DebridApiResponse(
            status = 400,
            body = PremiumizeDeviceTokenDto(error = error, errorDescription = error),
            rawBody = """{"error":"$error"}""",
        )
}
