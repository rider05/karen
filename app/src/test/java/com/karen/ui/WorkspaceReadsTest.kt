package com.karen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceReadsTest {
    @Test
    fun parseReadsDedupesCapsAndSkipsBlank() {
        val reply = listOf(
            "///READ: src/Main.kt",
            "///READ: src/Main.kt",
            "///READ: lib/util.sh",
            "///READ: a.txt",
            "///READ: b.txt",
            "///READ:   ",
            "done"
        ).joinToString("\n")
        assertEquals(
            listOf("src/Main.kt", "lib/util.sh", "a.txt", "b.txt"),
            parseReads(reply)
        )
    }

    @Test
    fun parseReadsIgnoresFileBlocksAndMidLineText() {
        assertTrue(parseReads("///FILE: x.txt\nhi\n///END").isEmpty())
        assertTrue(parseReads("see ///READ: nope here").isEmpty())
    }

    @Test
    fun stripCodeForChatDropsReadLines() {
        val out = stripCodeForChat("Check this:\n///READ: src/Main.kt\nThanks.")
        assertEquals("Check this:\n\nThanks.", out)
        assertTrue("///READ" !in out)
    }
}
