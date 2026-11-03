package com.karen.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KarenPlanTest {

    @Test
    fun wantsPlanDetectsMultiStepRequests() {
        assertTrue(wantsPlan("Please give me a step by step plan to deploy an app"))
        assertTrue(wantsPlan("Build a complete REST API with authentication"))
        assertTrue(wantsPlan("Walk me through the migration workflow"))
        assertFalse(wantsPlan("Hi there"))
        assertFalse(wantsPlan("What is the capital of France?"))
    }

    @Test
    fun splitPlanParsesStepsAndStripsPlanLines() {
        val reply = """
            ///PLAN {"steps":[{"t":"Fetch latest updates","s":"done"},{"t":"Run migrations","s":"doing"},{"t":"Deploy to production","s":"todo"}]}
            I have completed fetching updates and am now running migrations.
        """.trimIndent()

        val parsed = splitPlan(reply)
        assertEquals(3, parsed.steps.size)
        assertEquals("Fetch latest updates", parsed.steps[0].text)
        assertEquals(PlanStatus.Done, parsed.steps[0].status)
        assertEquals("Run migrations", parsed.steps[1].text)
        assertEquals(PlanStatus.Doing, parsed.steps[1].status)
        assertEquals("Deploy to production", parsed.steps[2].text)
        assertEquals(PlanStatus.Todo, parsed.steps[2].status)

        assertFalse(parsed.body.contains("///PLAN"))
        assertTrue(parsed.body.contains("I have completed fetching updates"))
    }

    @Test
    fun splitPlanIgnoresPlaceholders() {
        val reply = """
            ///PLAN {"steps":[{"t":"<real step 1>","s":"todo"},{"t":"Real step to execute","s":"doing"}]}
            Body text.
        """.trimIndent()

        val parsed = splitPlan(reply)
        assertEquals(1, parsed.steps.size)
        assertEquals("Real step to execute", parsed.steps[0].text)
    }
}
