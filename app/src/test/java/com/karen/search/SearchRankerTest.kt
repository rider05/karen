package com.karen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankerTest {
    @Test
    fun deduplicatesTrackingVariantsAndMergesSnippets() {
        val first = SearchHit(
            title = "Bitcoin price today",
            url = "https://example.com/btc?utm_source=newsletter",
            snippet = "Short",
            source = "example.com",
            provider = "duckduckgo"
        )
        val second = SearchHit(
            title = "Bitcoin price today",
            url = "https://example.com/btc",
            snippet = "A much longer snippet about the bitcoin price today.",
            source = "example.com",
            provider = "wikipedia"
        )
        val ranked = SearchRanker.rank(
            "bitcoin price today",
            listOf("bitcoin price today" to listOf(first), "btc price" to listOf(second)),
            5
        )
        assertEquals(1, ranked.size)
        assertEquals("A much longer snippet about the bitcoin price today.", ranked[0].hit.snippet)
        assertEquals(2, ranked[0].matchedQueries.size)
    }

    @Test
    fun titleMatchesRankAboveSnippetOnlyMatches() {
        val titleMatch = SearchHit(
            title = "Kotlin coroutines guide",
            url = "https://example.com/a",
            snippet = "A guide.",
            source = "example.com"
        )
        val bodyMatch = SearchHit(
            title = "Android notes",
            url = "https://example.com/b",
            snippet = "This note mentions kotlin coroutines once.",
            source = "example.com"
        )
        val ranked = SearchRanker.rank("kotlin coroutines", listOf("q" to listOf(bodyMatch, titleMatch)), 5)
        assertEquals("https://example.com/a", ranked[0].hit.url)
    }

    @Test
    fun blankUrlsWithDuplicateTitlesCollapse() {
        val a = SearchHit("Same title", "", "snippet", "DuckDuckGo", provider = "duckduckgo")
        val b = SearchHit("Same title", "", "longer snippet", "DuckDuckGo", provider = "duckduckgo")
        val ranked = SearchRanker.rank("same", listOf("q" to listOf(a, b)), 5)
        assertEquals(1, ranked.size)
        assertEquals("longer snippet", ranked[0].hit.snippet)
    }

    @Test
    fun limitIsRespected() {
        val hits = (1..10).map {
            SearchHit("Result $it", "https://example.com/$it", "snippet $it", "example.com")
        }
        assertEquals(3, SearchRanker.rank("result", listOf("q" to hits), 3).size)
        assertTrue(SearchRanker.rank("result", emptyList(), 3).isEmpty())
    }
}
