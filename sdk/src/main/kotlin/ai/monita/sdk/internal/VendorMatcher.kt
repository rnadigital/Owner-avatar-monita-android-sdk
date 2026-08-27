// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

/**
 * Vendor URL matching, ported EXACTLY from the JS reference (stripDomains,
 * global-inject-script.ts:155-167, the pattern map :598, the compiled match
 * strings :1265-1268, and the match loop :937-939) so shared configs match
 * identically on web, iOS, and Android:
 *
 *  - a pattern starting with "http" has "http://" removed and "https://"
 *    replaced with "/" (so "https://x.com/tr" anchors as "/x.com/tr"),
 *  - patterns compile into ONE ordered list of match strings: every
 *    "/" + pattern in map insertion order, then every "." + pattern,
 *  - a duplicate pattern belongs to the LAST vendor declaring it (object
 *    assignment keeps the first key position but updates the value),
 *  - the first containing match string wins, case-sensitive on the raw URL,
 *    no lowercasing of either side and no "www." stripping, resolving the
 *    vendor through the pattern map.
 */
internal class VendorMatcher(config: RemoteConfig) {

    // LinkedHashMap.put mirrors JS object assignment: a repeated pattern
    // keeps its first insertion position but takes the last vendor's name.
    private val patternToVendor = LinkedHashMap<String, String>()

    private val matchStrings: List<String>

    init {
        for (vendor in config.vendors) {
            for (raw in vendor.urlPatternMatches) {
                val pattern = normalizePattern(raw)
                // An empty pattern would compile to "/" and match every URL;
                // the builder never emits one, so it is skipped defensively.
                if (pattern.isEmpty()) continue
                patternToVendor[pattern] = vendor.vendorName
            }
        }
        matchStrings = patternToVendor.keys.map { "/$it" } + patternToVendor.keys.map { ".$it" }
    }

    fun match(url: String): String? {
        for (candidate in matchStrings) {
            if (url.contains(candidate)) {
                return patternToVendor[candidate.substring(1)]
            }
        }
        return null
    }

    companion object {
        fun normalizePattern(pattern: String): String =
            if (pattern.startsWith("http")) {
                pattern.replaceFirst("http://", "").replaceFirst("https://", "/")
            } else {
                pattern
            }
    }
}
