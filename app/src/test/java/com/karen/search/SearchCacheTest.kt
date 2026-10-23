package com.karen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCacheTest {
    private class FakeStore : SearchCacheStore {
        val searches = mutableMapOf<String, CachedSearch>()
        val pages = mutableMapOf<String, CachedPage>()
        var trims = 0

        override fun search(key: String): CachedSearch? = searches[key]
        override fun putSearch(key: String, value: CachedSearch) {
            searches[key] = value
        }

        override fun page(key: String): CachedPage? = pages[key]
        override fun putPage(key: String, value: CachedPage) {
            pages[key] = value
        }

        override fun trim(now: Long) {
            trims++
        }
    }

    @Test
    fun searchAndPageRoundTripWithTtl() {
        val store = FakeStore()
        var now = 1_000L
        val cache = SearchCache(store, now = { now })
        val hit = SearchHit("Title", "https://example.com/a", "snippet", "example.com")

        cache.putSearch("query", "keyless", listOf(hit))
        val cached = cache.search("query", "keyless")
        assertNotNull(cached)
        assertTrue(cache.isFresh(cached!!))
        assertEquals("Title", cached.hits[0].title)

        val page = FetchedPage(
            requestedUrl = "https://example.com/a",
            finalUrl = "https://example.com/a",
            title = "Page",
            text = "body",
            fetchedAt = now
        )
        cache.putPage(page)
        assertNotNull(cache.page("https://example.com/a"))

        now += 16 * 60_000L
        assertFalse(cache.isFresh(cache.search("query", "keyless")!!))
        assertTrue(cache.canServeStale(cache.search("query", "keyless")!!))
    }

    @Test
    fun cacheKeysAreHashedAndVariantAware() {
        val plain = SearchCacheKeys.searchKey("my private query")
        val variant = SearchCacheKeys.searchKey("my private query", "searx")
        assertFalse(plain.contains("private"))
        assertFalse(plain == variant)
        assertEquals(64, plain.length)
    }

    @Test
    fun staleWindowExpiresAfterRetention() {
        val store = FakeStore()
        var now = 0L
        val cache = SearchCache(store, now = { now })
        cache.putSearch("q", "", listOf(SearchHit("T", "https://example.com", "s", "example.com")))
        now = 8 * 24 * 60 * 60_000L
        val cached = cache.search("q")
        assertFalse(cache.isFresh(cached!!))
        assertFalse(cache.canServeStale(cached))
    }

    @Test
    fun trimRunsAtMostOncePerHour() {
        val store = FakeStore()
        var now = 0L
        val cache = SearchCache(store, now = { now })
        cache.trimIfDue()
        cache.trimIfDue()
        assertEquals(1, store.trims)
        now += 61 * 60_000L
        cache.trimIfDue()
        assertEquals(2, store.trims)
    }

    @Test
    fun missingEntriesReturnNull() {
        val cache = SearchCache(FakeStore())
        assertNull(cache.search("missing"))
        assertNull(cache.page("https://example.com/missing"))
    }
}
