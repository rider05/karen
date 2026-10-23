package com.karen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryExpanderTest {
    @Test
    fun cleanRemovesOnlyExplicitSearchPrefixes() {
        assertEquals("latest news", QueryExpander.clean("/search latest news"))
        assertEquals("latest news", QueryExpander.clean("search: latest news"))
        assertEquals("latest news", QueryExpander.clean("search latest news"))
        assertEquals("what is kotlin", QueryExpander.clean("what is kotlin"))
    }

    @Test
    fun primaryCollapsesWhitespaceAndCapsLength() {
        val value = QueryExpander.primary("  hello \n\n world  ")
        assertEquals("hello world", value)
        assertEquals(300, QueryExpander.primary("x".repeat(400)).length)
    }

    @Test
    fun keywordVariantDropsStopwordsAndKeepsContent() {
        val variant = QueryExpander.keywordVariant("What is the latest price of Bitcoin?")
        assertEquals("latest price bitcoin", variant)
        assertTrue(QueryExpander.keywordVariant("What is it?").isBlank())
    }

    @Test
    fun expandIsBoundedAndDeduplicated() {
        val queries = QueryExpander.expand("What is the latest price of Bitcoin?", 2)
        assertEquals(2, queries.size)
        assertEquals("What is the latest price of Bitcoin?", queries[0])
        assertEquals("latest price bitcoin", queries[1])
        assertTrue(QueryExpander.expand("what is kotlin", 1).size == 1)
    }

    @Test
    fun followUpReturnsOnlyAnUnattemptedQuery() {
        val question = "latest bitcoin price"
        val first = QueryExpander.primary(question)
        val next = QueryExpander.followUp(question, listOf(first))
        assertTrue(next == null || next != first)
    }

    @Test
    fun freshQueryDetectionUsesDeterministicMarkers() {
        assertTrue(QueryExpander.isFreshQuery("latest football scores"))
        assertTrue(QueryExpander.isFreshQuery("bitcoin price today"))
        assertFalse(QueryExpander.isFreshQuery("what is a monad"))
    }
}
