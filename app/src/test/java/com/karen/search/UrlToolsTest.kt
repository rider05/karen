package com.karen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlToolsTest {
    @Test
    fun normalizeDropsTrackingSortsQueryAndIgnoresFragment() {
        val normalized = UrlTools.normalize(
            "HTTPS://Example.COM:443/a/?utm_source=x&b=2&a=1#frag"
        )
        assertEquals("https://example.com/a?a=1&b=2", normalized)
    }

    @Test
    fun normalizeRejectsNonHttpAndCredentials() {
        assertNull(UrlTools.normalize("file:///etc/passwd"))
        assertNull(UrlTools.normalize("https://user:pass@example.com/a"))
        assertNull(UrlTools.normalize("not a url"))
    }

    @Test
    fun unwrapsDuckDuckGoRedirect() {
        val wrapped = "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fstory%3Fa%3D1"
        assertEquals("https://example.com/story?a=1", UrlTools.unwrapRedirect(wrapped))
        assertEquals("https://example.com/story?a=1", UrlTools.normalize(wrapped))
    }

    @Test
    fun blocksPrivateAndSingleLabelHosts() {
        assertFalse(UrlTools.isPublicHttpUrl("http://127.0.0.1/admin"))
        assertFalse(UrlTools.isPublicHttpUrl("http://192.168.1.2/"))
        assertFalse(UrlTools.isPublicHttpUrl("http://localhost:8080/"))
        assertFalse(UrlTools.isPublicHttpUrl("http://router/"))
        assertTrue(UrlTools.isPublicHttpUrl("https://example.com/page"))
    }
}
