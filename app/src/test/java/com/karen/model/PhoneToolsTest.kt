package com.karen.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolsTest {

    @Test
    fun evalExprCalculatesCorrectPrecedence() {
        assertEquals(14.0, evalExpr("2 + 3 * 4"), 1e-6)
        assertEquals(20.0, evalExpr("(2 + 3) * 4"), 1e-6)
        assertEquals(7.0, evalExpr("10 - 6 / 2"), 1e-6)
        assertEquals(8.0, evalExpr("2 ^ 3"), 1e-6)
        assertEquals(1.0, evalExpr("10 % 3"), 1e-6)
        assertEquals(3.5, evalExpr("7 / 2"), 1e-6)
        assertEquals(-5.0, evalExpr("-2 - 3"), 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun evalExprRejectsInvalidCharacters() {
        evalExpr("2 + a")
    }

    @Test(expected = IllegalArgumentException::class)
    fun evalExprRejectsUnbalancedParentheses() {
        evalExpr("(2 + 3")
    }

    @Test
    fun parseTimerArgParsesVariousFormats() {
        val t1 = parseTimerArg("5 minutes")
        assertNotNull(t1)
        assertEquals(300L, t1!!.getLong("seconds"))

        val t2 = parseTimerArg("1 hour")
        assertNotNull(t2)
        assertEquals(3600L, t2!!.getLong("seconds"))

        val t3 = parseTimerArg("45s eggs")
        assertNotNull(t3)
        assertEquals(45L, t3!!.getLong("seconds"))
        assertEquals("eggs", t3.optString("label"))

        assertNull(parseTimerArg("invalid text"))
        assertNull(parseTimerArg("0 minutes"))
    }

    @Test
    fun parseAlarmArgParsesAmPmAnd24Hour() {
        val a1 = parseAlarmArg("7:30 am")
        assertNotNull(a1)
        assertEquals(7, a1!!.getInt("hour"))
        assertEquals(30, a1.getInt("minute"))

        val a2 = parseAlarmArg("8:15 pm")
        assertNotNull(a2)
        assertEquals(20, a2!!.getInt("hour"))
        assertEquals(15, a2.getInt("minute"))

        val a3 = parseAlarmArg("12:00 am")
        assertNotNull(a3)
        assertEquals(0, a3!!.getInt("hour"))

        val a4 = parseAlarmArg("12:00 pm")
        assertNotNull(a4)
        assertEquals(12, a4!!.getInt("hour"))

        val a5 = parseAlarmArg("23:45")
        assertNotNull(a5)
        assertEquals(23, a5!!.getInt("hour"))
        assertEquals(45, a5.getInt("minute"))

        assertNull(parseAlarmArg("25:00"))
        assertNull(parseAlarmArg("8:70 am"))
    }

    @Test
    fun extractToolCallsFindsValidJson() {
        val text = "Here is the result.\n///TOOL {\"name\":\"calculator\",\"arguments\":{\"expression\":\"5+5\"}}\nFinished."
        val calls = extractToolCalls(text)
        assertEquals(1, calls.size)
        assertEquals("calculator", calls[0].name)
        assertEquals("5+5", calls[0].args.optString("expression"))

        val stripped = stripToolCalls(text, calls)
        assertEquals("Here is the result.\n\nFinished.", stripped)
        assertFalse(stripped.contains("///TOOL"))
    }

    @Test
    fun validateToolCallValidatesTypesAndRanges() {
        val spec = ToolSpec(
            name = "set_alarm",
            desc = "Set alarm",
            tier = "T2",
            args = listOf(
                ToolArgSpec(name = "hour", type = "integer", required = true, desc = "0-23", min = 0, max = 23),
                ToolArgSpec(name = "minute", type = "integer", required = true, desc = "0-59", min = 0, max = 59),
                ToolArgSpec(name = "label", type = "string", required = false, desc = "Alarm label")
            )
        )

        // Valid
        val validArgs = JSONObject().put("hour", 7).put("minute", 30).put("label", "Wake up")
        assertNull(validateToolCall(spec, validArgs))

        // Missing required
        val missingArgs = JSONObject().put("hour", 7)
        val missingErr = validateToolCall(spec, missingArgs)
        assertNotNull(missingErr)
        assertTrue(missingErr!!.contains("missing required argument \"minute\""))

        // Out of range
        val outOfRange = JSONObject().put("hour", 25).put("minute", 30)
        val rangeErr = validateToolCall(spec, outOfRange)
        assertNotNull(rangeErr)
        assertTrue(rangeErr!!.contains("must be <= 23"))

        // Wrong type
        val wrongType = JSONObject().put("hour", "not_a_num").put("minute", 30)
        val typeErr = validateToolCall(spec, wrongType)
        assertNotNull(typeErr)
        assertTrue(typeErr!!.contains("must be a whole number"))
    }

    @Test
    fun vaultChunksSplitsLongParagraphsWithOverlap() {
        val longPara = "A".repeat(500)
        val chunks = vaultChunks(longPara, target = 200, overlap = 50)
        assertTrue(chunks.size >= 3)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 200)
        }
    }

    @Test
    fun vaultQueryTermsFiltersStopwordsAndShortWords() {
        val terms = vaultQueryTerms("What is the current status of the project report?")
        assertFalse(terms.contains("what"))
        assertFalse(terms.contains("the"))
        assertFalse(terms.contains("is"))
        assertFalse(terms.contains("of"))
        assertTrue(terms.contains("current"))
        assertTrue(terms.contains("status"))
        assertTrue(terms.contains("project"))
        assertTrue(terms.contains("report"))
    }
}
