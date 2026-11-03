package com.karen.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRouterTest {

    @Test
    fun detectIntentIdentifiesCodeTasks() {
        assertEquals(TaskIntent.CODE, detectIntent("Can you write a python script to parse CSV files?"))
        assertEquals(TaskIntent.CODE, detectIntent("Fix this NullPointerException in Kotlin"))
        assertEquals(TaskIntent.CODE, detectIntent("Refactor this database query function"))
    }

    @Test
    fun detectIntentIdentifiesReasoningTasks() {
        assertEquals(TaskIntent.REASONING, detectIntent("Why is the sky blue and explain the physics"))
        assertEquals(TaskIntent.REASONING, detectIntent("Solve this math logic puzzle"))
        assertEquals(TaskIntent.REASONING, detectIntent("Compare the strategic advantages of option A vs option B"))
    }

    @Test
    fun detectIntentIdentifiesLongReadingTasks() {
        assertEquals(TaskIntent.LONG, detectIntent("Summarize this long document for me"))
        assertEquals(TaskIntent.LONG, detectIntent("Extract the main points of this thesis paper"))
    }

    @Test
    fun detectIntentDefaultsToSimple() {
        assertEquals(TaskIntent.SIMPLE, detectIntent("Hello, how are you today?"))
        assertEquals(TaskIntent.SIMPLE, detectIntent("Tell me a funny joke"))
    }

    @Test
    fun scoreCloudRanksAppropriateProvidersHighest() {
        assertTrue(scoreCloud("anthropic", TaskIntent.REASONING) > scoreCloud("groq", TaskIntent.REASONING))
        assertTrue(scoreCloud("deepseek", TaskIntent.CODE) > scoreCloud("grok", TaskIntent.CODE))
    }
}
