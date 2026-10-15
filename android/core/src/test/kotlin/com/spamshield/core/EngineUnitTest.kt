package com.spamshield.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineUnitTest {
    private val engine = Engine.loadBundled()

    @Test
    fun bundledResourcesLoad() {
        assertTrue(engine.rules.sms.keywordRules.isNotEmpty())
        assertTrue(engine.classify("") != null)
    }

    @Test
    fun normalizationEdgeCases() {
        assertNull(Numbers.normalize("   "))
        assertEquals("AMAZON", Numbers.normalize("Ama zon"))
        assertEquals("+442071234567", Numbers.normalize("02071234567", "44"))
        assertEquals("+12125550123", Numbers.normalize("(212) 555-0123"))
        assertEquals("72345", Numbers.normalize("72345"))
    }

    @Test
    fun globBacktracking() {
        assertTrue(Patterns.globMatch("*1*2*3", "+1112233"))
        assertFalse(Patterns.globMatch("+1800*9", "+18005551234"))
        assertTrue(Patterns.globMatch("+1212555????", "+12125550123"))
        assertNull(Patterns.prepare("()-. "))
    }

    @Test
    fun dotDoesNotStopAtCarriageReturn() {
        // Python's `.` only excludes '\n'; Java's default also excludes '\r' unless UNIX_LINES.
        val verdict = engine.evaluate(Event(Channel.SMS, "12345", body = "urgent\rpackage on hold"))
        assertTrue("keyword:package_delivery" in verdict.reasons, verdict.reasons.toString())
    }

    @Test
    fun communityScoreOnlyCountsForNormalizedKey() {
        val ctx = EngineContext(community = mapOf("+12025550123" to 0.9), settings = Settings(useClassifier = false))
        val hit = engine.evaluate(Event(Channel.CALL, "202-555-0123"), ctx)
        assertEquals(listOf("community"), hit.reasons)
        assertEquals(Action.BLOCK, hit.action)
        val off = engine.evaluate(Event(Channel.CALL, "202-555-0123"), ctx.copy(settings = Settings(useCommunity = false)))
        assertEquals(Action.ALLOW, off.action)
    }

    @Test
    fun urlHostsAreExtracted() {
        assertEquals(listOf("bit.ly", "10.0.0.1"), Urls.hosts("go to https://bit.ly/x or 10.0.0.1/login"))
    }
}
