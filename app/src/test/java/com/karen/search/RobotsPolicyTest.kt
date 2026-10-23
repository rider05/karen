package com.karen.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotsPolicyTest {
    private val robots = """
        User-agent: *
        Disallow: /private
        Allow: /private/public

        User-agent: Karen
        Disallow: /karen-only
    """.trimIndent()

    @Test
    fun specificAgentGroupOverridesWildcard() {
        val rules = RobotsPolicy.parse(robots, "Karen")
        assertTrue(RobotsPolicy.allows(rules, "/anything"))
        assertFalse(RobotsPolicy.allows(rules, "/karen-only"))
        assertTrue(RobotsPolicy.allows(rules, "/private"))
    }

    @Test
    fun wildcardGroupAppliesToOtherAgentsWithLongestMatch() {
        val rules = RobotsPolicy.parse(robots, "OtherBot")
        assertFalse(RobotsPolicy.allows(rules, "/private/data"))
        assertTrue(RobotsPolicy.allows(rules, "/private/public/page"))
        assertTrue(RobotsPolicy.allows(rules, "/public"))
    }

    @Test
    fun allowWinsWhenPatternsHaveEqualLength() {
        val rules = RobotsPolicy.parse(
            "User-agent: *\nDisallow: /same\nAllow: /same",
            "AnyBot"
        )
        assertTrue(RobotsPolicy.allows(rules, "/same"))
    }

    @Test
    fun emptyOrMissingRulesAllowEverything() {
        assertTrue(RobotsPolicy.allows(emptyList(), "/anything"))
        assertTrue(RobotsPolicy.allows(RobotsPolicy.parse("", "Karen"), "/anything"))
    }

    @Test
    fun wildcardAndEndAnchorsAreSupported() {
        val rules = RobotsPolicy.parse(
            "User-agent: *\nDisallow: /*.pdf$\n",
            "AnyBot"
        )
        assertFalse(RobotsPolicy.allows(rules, "/docs/file.pdf"))
        assertTrue(RobotsPolicy.allows(rules, "/docs/file.pdf?download=1"))
        assertTrue(RobotsPolicy.allows(rules, "/docs/file.html"))
    }
}
