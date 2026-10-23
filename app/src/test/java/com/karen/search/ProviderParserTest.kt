package com.karen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderParserTest {
    @Test
    fun duckDuckGoParsesAbstractAndRelatedTopics() {
        val json = """
            {
              "Heading": "Kotlin",
              "AbstractText": "Kotlin is a cross-platform language.",
              "AbstractURL": "https://kotlinlang.org/",
              "AbstractSource": "Kotlin",
              "RelatedTopics": [
                {"Text": "Kotlin is used for Android.", "FirstURL": "https://example.com/android"},
                {"Topics": [
                  {"Text": "Kotlin/Native compiles to native code.", "FirstURL": "https://example.com/native"}
                ]}
              ]
            }
        """.trimIndent()
        val hits = DuckDuckGoProvider.parse(json, 5)
        assertEquals(3, hits.size)
        assertEquals("Kotlin", hits[0].title)
        assertEquals("kotlinlang.org", hits[0].source)
        assertEquals("https://example.com/android", hits[1].url)
    }

    @Test
    fun wikipediaParsesSearchHits() {
        val json = """
            {"query":{"search":[
              {"title":"Kotlin (programming language)","snippet":"<span>Kotlin</span> is a language.","pageid":123}
            ]}}
        """.trimIndent()
        val hits = WikipediaProvider.parse(json, 5)
        assertEquals(1, hits.size)
        assertEquals("Kotlin (programming language)", hits[0].title)
        assertTrue(hits[0].snippet.contains("Kotlin is a language"))
        assertEquals("https://en.wikipedia.org/?curid=123", hits[0].url)
    }

    @Test
    fun searxngParsesJsonResultsAndDates() {
        val json = """
            {"results":[
              {"title":"Story","url":"https://example.com/story","content":"<b>Useful</b> text",
               "publishedDate":"2026-10-20T10:00:00Z"}
            ]}
        """.trimIndent()
        val hits = SearXNGProvider.parse(json, 5)
        assertEquals(1, hits.size)
        assertEquals("Story", hits[0].title)
        assertEquals("Useful text", hits[0].snippet)
        assertNotNull(hits[0].publishedAtMillis)
    }

    @Test
    fun exaParsesTextResults() {
        val json = """
            {"results":[
              {"title":"Story","url":"https://example.com/story","text":"A longer body",
               "publishedDate":"2026-10-19"}
            ]}
        """.trimIndent()
        val hits = ExaProvider.parse(json, 5)
        assertEquals(1, hits.size)
        assertEquals("example.com", hits[0].source)
        assertNotNull(hits[0].publishedAtMillis)
    }

    @Test
    fun malformedJsonReturnsEmptyInsteadOfThrowing() {
        assertTrue(DuckDuckGoProvider.parse("{", 5).isEmpty())
        assertTrue(WikipediaProvider.parse("not json", 5).isEmpty())
        assertTrue(SearXNGProvider.parse("[]", 5).isEmpty())
        assertTrue(ExaProvider.parse("{}", 5).isEmpty())
    }
}
