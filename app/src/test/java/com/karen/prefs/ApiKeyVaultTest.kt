package com.karen.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyVaultTest {

    @Test
    fun toJsonAndParseKeysRoundtripsCorrectly() {
        val entries = mapOf(
            "openai" to Pair("sk-proj-1234567890abcdef", "gpt-4o"),
            "anthropic" to Pair("sk-ant-test-key", "claude-sonnet-5-5"),
            "gemini" to Pair("AIzaSyTestKey_with_special=chars&more", "gemini-2.0-flash")
        )

        val json = ApiKeyVault.toJson(entries)
        assertTrue(json.contains("\"v\":1"))
        assertTrue(json.contains("\"openai\""))
        assertTrue(json.contains("\"anthropic\""))

        val parsed = ApiKeyVault.parseKeys(json)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertEquals("sk-proj-1234567890abcdef", parsed["openai"]?.first)
        assertEquals("gpt-4o", parsed["openai"]?.second)
        assertEquals("sk-ant-test-key", parsed["anthropic"]?.first)
        assertEquals("claude-sonnet-5-5", parsed["anthropic"]?.second)
        assertEquals("AIzaSyTestKey_with_special=chars&more", parsed["gemini"]?.first)
        assertEquals("gemini-2.0-flash", parsed["gemini"]?.second)
    }

    @Test
    fun parseKeysHandlesSpecialCharactersAndQuotes() {
        val entries = mapOf(
            "custom" to Pair("key\"with\"quotes and \\backslashes\\", "model/with:colons")
        )

        val json = ApiKeyVault.toJson(entries)
        val parsed = ApiKeyVault.parseKeys(json)
        assertNotNull(parsed)
        assertEquals("key\"with\"quotes and \\backslashes\\", parsed!!["custom"]?.first)
        assertEquals("model/with:colons", parsed["custom"]?.second)
    }

    @Test
    fun parseKeysRejectsInvalidJsonGracefully() {
        assertNull(ApiKeyVault.parseKeys("not json"))
        assertNull(ApiKeyVault.parseKeys("{\"v\":1}")) // missing keys object
        assertNull(ApiKeyVault.parseKeys(""))
    }
}
