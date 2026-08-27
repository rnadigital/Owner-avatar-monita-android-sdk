// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.RemoteConfig
import ai.monita.sdk.internal.VendorConfig
import ai.monita.sdk.internal.VendorMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Vendor matching is an EXACT port of the JS reference stripDomains and
 * match loop; web and mobile must agree on shared configs, quirks included.
 */
class VendorMatcherTest {

    private fun matcher(vararg vendors: Pair<String, List<String>>) = VendorMatcher(
        RemoteConfig(
            monitoringVersion = "1",
            vendors = vendors.map { (name, patterns) ->
                VendorConfig(vendorName = name, urlPatternMatches = patterns)
            },
        )
    )

    @Test
    fun matchesWhenUrlContainsSlashPlusPattern() {
        val m = matcher("Facebook" to listOf("facebook.com/tr"))
        assertEquals("Facebook", m.match("https://www.facebook.com/tr?id=1"))
    }

    @Test
    fun matchesWhenUrlContainsDotPlusPattern() {
        val m = matcher("GA4" to listOf("google-analytics.com/g/collect"))
        assertEquals("GA4", m.match("https://region1.google-analytics.com/g/collect?v=2"))
    }

    @Test
    fun httpsPatternsAnchorWithALeadingSlashLikeTheJsReference() {
        // stripDomains: "https://x.com/tr" becomes "/x.com/tr", so the match
        // candidates are "//x.com/tr" and "./x.com/tr".
        val m = matcher("X" to listOf("https://x.com/tr"))
        assertEquals("X", m.match("https://x.com/tr?id=1"))
        // A path-embedded occurrence has one slash, not two: no match.
        assertNull(m.match("https://evil.example.com/x.com/tr"))
    }

    @Test
    fun httpPatternsStripTheProtocolWithoutAnchoring() {
        val m = matcher("X" to listOf("http://x.com/tr"))
        assertEquals("X", m.match("http://x.com/tr?id=1"))
        assertEquals("X", m.match("https://cdn.example.com/x.com/tr"))
    }

    @Test
    fun matchingIsCaseSensitiveOnBothSides() {
        val m = matcher("X" to listOf("Example.COM/Collect"))
        assertEquals("X", m.match("https://sub.Example.COM/Collect"))
        assertNull(m.match("https://sub.example.com/collect"))
    }

    @Test
    fun wwwIsNeverStripped() {
        val m = matcher("X" to listOf("www.example.com/c"))
        assertEquals("X", m.match("https://www.example.com/c?a=1"))
        assertNull(m.match("https://example.com/c?a=1"))
    }

    @Test
    fun noSubstringMatchWithoutSlashOrDotPrefix() {
        val m = matcher("X" to listOf("facebook.com/tr"))
        assertNull(m.match("https://notfacebook.com/tr"))
    }

    @Test
    fun duplicatePatternsBelongToTheLastDeclaringVendor() {
        // JS object assignment: the pattern keeps its first position in the
        // match order but resolves to the last vendor that declared it.
        val m = matcher(
            "First" to listOf("shared.example.com"),
            "Second" to listOf("shared.example.com"),
        )
        assertEquals("Second", m.match("https://shared.example.com/x"))
    }

    @Test
    fun allSlashCandidatesAreCheckedBeforeAnyDotCandidate() {
        // The compiled match list holds every "/" + pattern first, then every
        // "." + pattern, so a URL that dot-matches an earlier vendor but
        // slash-matches a later one attributes to the later vendor, exactly
        // as on web.
        val m = matcher(
            "Early" to listOf("pixel.net"),
            "Late" to listOf("tracker.io/p"),
        )
        assertEquals("Late", m.match("https://api.pixel.net/x/tracker.io/p"))
        // Without the competing slash match, the dot candidate still wins.
        assertEquals("Early", m.match("https://api.pixel.net/x"))
    }

    @Test
    fun noMatchReturnsNull() {
        val m = matcher("X" to listOf("vendor.com"))
        assertNull(m.match("https://unrelated.org/path"))
    }
}
