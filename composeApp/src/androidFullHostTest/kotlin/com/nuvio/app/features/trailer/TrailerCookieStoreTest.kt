package com.nuvio.app.features.trailer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailerCookieStoreTest {

    @Test
    fun `keeps a domain cookie and sends it to that host and its subdomains`() {
        val store = TrailerCookieStore()

        store.save("youtube.com", listOf("GOOGLE_ABUSE_EXEMPTION=abc123; path=/; domain=.youtube.com; expires=soon"))

        assertEquals("GOOGLE_ABUSE_EXEMPTION=abc123", store.headerFor("youtube.com"))
        assertEquals("GOOGLE_ABUSE_EXEMPTION=abc123", store.headerFor("m.youtube.com"))
        assertNull(store.headerFor("example.com"))
    }

    @Test
    fun `host-only cookies do not leak to other hosts`() {
        val store = TrailerCookieStore()

        store.save("www.youtube.com", listOf("__Secure-YNID=token; path=/"))

        assertEquals("__Secure-YNID=token", store.headerFor("www.youtube.com"))
        assertNull(store.headerFor("m.youtube.com"))
        assertNull(store.headerFor("google.com"))
    }

    @Test
    fun `merges several cookies across hosts and replaces duplicates`() {
        val store = TrailerCookieStore()

        store.save("youtube.com", listOf("VISITOR_INFO1_LIVE=first; domain=.youtube.com", "GPS=one; domain=.youtube.com"))
        store.save("youtube.com", listOf("GPS=two; domain=.youtube.com"))

        val header = store.headerFor("www.youtube.com").orEmpty()
        assertEquals("VISITOR_INFO1_LIVE=first; GPS=two", header)
    }

    @Test
    fun `removes cookies expired with max-age zero`() {
        val store = TrailerCookieStore()

        store.save("youtube.com", listOf("CONSENT=YES; domain=.youtube.com"))
        assertEquals("CONSENT=YES", store.headerFor("youtube.com"))

        store.save("youtube.com", listOf("CONSENT=; domain=.youtube.com; max-age=0"))
        assertNull(store.headerFor("youtube.com"))
    }
}
