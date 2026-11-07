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

    @Test
    fun splitTraceParsesSkillAndStripsLines() {
        val reply = "///TRACE {\"t\":\"Build the PDF page by page\",\"k\":\"build\",\"s\":\"started\"}\n" +
            "///TRACE {\"t\":\"pdf\",\"k\":\"skill\",\"s\":\"info\"}\n" +
            "///TRACE {\"t\":\"PDF tools and fonts\",\"k\":\"env\",\"s\":\"info\"}\n" +
            "///TRACE {\"t\":\"Created lib.py\",\"k\":\"create\",\"s\":\"info\"}\n" +
            "Body."
        val parsed = splitTrace(reply)
        assertEquals(4, parsed.rows.size)
        assertEquals(TraceKind.Skill, parsed.rows[1].kind)
        assertEquals(TraceKind.EnvCheck, parsed.rows[2].kind)
        assertEquals(TraceKind.Create, parsed.rows[3].kind)
        assertFalse(parsed.body.contains("///TRACE"))
        assertTrue(parsed.body.contains("Body."))
        assertTrue(traceSummary(parsed.rows).contains("Created 1 file"))
    }

    @Test
    fun splitClarifyParsesMaxTwo() {
        val reply = "///CLARIFY {\"q\":\"Which one?\",\"opts\":[\"A\",\"B (Recommended)\"]}\n" +
            "///CLARIFY {\"q\":\"Style?\",\"opts\":[\"X\",\"Y\"]}\n" +
            "Body."
        val parsed = splitClarify(reply)
        assertEquals(2, parsed.questions.size)
        assertEquals("Which one?", parsed.questions[0].question)
        assertFalse(parsed.body.contains("///CLARIFY"))
    }

    @Test
    fun splitArtifactAndSourcesParseAndStrip() {
        val reply = "///ARTIFACT {\"name\":\"shop_magazine.pdf\",\"pages\":15,\"kind\":\"pdf\"}\n" +
            "///SOURCES {\"items\":[{\"title\":\"Daily News\",\"url\":\"https://example.com/a\",\"source\":\"dailynews\"}]}\n" +
            "Body."
        val art = splitArtifact(reply)
        val src = splitSources(art.body)
        assertEquals(1, art.artifacts.size)
        assertEquals(15, art.artifacts[0].pages)
        assertEquals(1, src.items.size)
        assertEquals("dailynews", src.items[0].source)
        assertTrue(src.body.contains("Body."))
        assertFalse(src.body.contains("///ARTIFACT"))
        assertFalse(src.body.contains("///SOURCES"))
    }

    @Test
    fun wantsLongBuildGuardsFalsePositives() {
        assertTrue(wantsLongBuild("Make a 15 page pdf magazine for acme and do it step by step"))
        assertTrue(wantsLongBuild("Build a step by step document"))
        assertTrue(wantsLongBuild("Make an 8 slide ppt for acme"))
        assertTrue(wantsLongBuild("Create a presentation about climate"))
        assertTrue(isPptPrompt("Make an 8 slide ppt for acme"))
        assertFalse(isPptPrompt("Make a 15 page pdf magazine for acme"))
        assertFalse(wantsLongBuild("Hi there"))
        assertFalse(wantsLongBuild("What is the capital of France?"))
    }
}
