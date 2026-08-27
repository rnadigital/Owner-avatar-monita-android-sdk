// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.fillParamsFromData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractionTest {

    private val tw = mapOf(
        "events" to "[[\"pageview\",{\"currency\":\"GBP\"}]]",
        "txn_id" to "o2g3p",
    )

    @Test
    fun plainParameterInterpolates() {
        assertEquals("o2g3p", fillParamsFromData("{{txn_id}}", tw))
    }

    @Test
    fun staticTextMixesWithParameters() {
        val data = mapOf("id" to "8117", "ev" to "Purchase")
        assertEquals("8117-Purchase", fillParamsFromData("{{id}}-{{ev}}", data))
    }

    @Test
    fun noBracesMeansPlainDotPathLookup() {
        val data = mapOf("a" to mapOf("b" to "deep"))
        assertEquals("deep", fillParamsFromData("a.b", data))
        assertNull(fillParamsFromData("a.missing", data))
    }

    @Test
    fun quotedSegmentsAllowDotsInsideKeys() {
        val data = mapOf("a" to mapOf("b" to mapOf("c.d" to "quoted")))
        assertEquals("quoted", fillParamsFromData("a.b.'c.d'", data))
    }

    @Test
    fun arrayValuedPathFansOutToOneEventPerElement() {
        val data = mapOf("items" to listOf(mapOf("name" to "a"), mapOf("name" to "b")))
        assertEquals(listOf("a", "b"), fillParamsFromData("{{items.name}}", data))
    }

    @Test
    fun fanOutCombinesWithStaticText() {
        val data = mapOf("ev" to "view", "items" to listOf(mapOf("id" to "1"), mapOf("id" to "2")))
        assertEquals(listOf("view-1", "view-2"), fillParamsFromData("{{ev}}-{{items.id}}", data))
    }

    @Test
    fun missingValueYieldsEmptyOutputNeverTheStringNull() {
        assertEquals("", fillParamsFromData("{{missing}}", tw))
        assertEquals("x-", fillParamsFromData("x-{{missing}}", tw))
    }

    @Test
    fun regexExtractsTheFirstMatch() {
        assertEquals("pageview", fillParamsFromData("{{regex::\\w+::events}}", tw))
    }

    @Test
    fun regexPrefersCaptureGroupOne() {
        assertEquals("pageview", fillParamsFromData("{{regex::\"([a-z]+)\"::events}}", tw))
    }

    @Test
    fun regexMixesWithStaticTextAndPlainParams() {
        assertEquals("o2g3p-pageview", fillParamsFromData("{{txn_id}}-{{regex::\\w+::events}}", tw))
    }

    @Test
    fun regexDropsSilentlyOnNoMatch() {
        assertEquals("", fillParamsFromData("{{regex::zzz9::events}}", tw))
        assertEquals("o2g3p-", fillParamsFromData("{{txn_id}}-{{regex::zzz9::events}}", tw))
    }

    @Test
    fun regexSurvivesAnInvalidPattern() {
        assertEquals("", fillParamsFromData("{{regex::([::events}}", tw))
    }

    @Test
    fun regexInputIsCappedAtFourKilobytes() {
        val long = mapOf("blob" to "x".repeat(10_000) + "needle")
        assertEquals("", fillParamsFromData("{{regex::needle::blob}}", long))
    }

    @Test
    fun numbersRenderLikeJavaScript() {
        val data = mapOf("n" to 5L, "d" to 2.5)
        assertEquals("5", fillParamsFromData("{{n}}", data))
        assertEquals("2.5", fillParamsFromData("{{d}}", data))
    }

    @Test
    fun emptyTemplateReturnsAsIs() {
        assertNull(fillParamsFromData(null, tw))
        assertEquals("", fillParamsFromData("", tw))
    }
}
