// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.WireFilter
import ai.monita.sdk.internal.WireFilterGroup
import ai.monita.sdk.internal.checkPassOnFilterGroups
import ai.monita.sdk.internal.checkPassOnFilters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {

    private val data = mapOf(
        "ev" to "Purchase",
        "currency" to "AUD",
        "id" to "881",
        "count" to 5L,
        "empty" to "",
        "nested" to mapOf("deep" to "value"),
    )

    private fun f(key: String, op: String?, vararg values: String) =
        WireFilter(key = key, op = op, values = if (values.isEmpty()) null else values.toList())

    @Test
    fun eqMatchesExactAndValArrayIsAnOr() {
        assertTrue(checkPassOnFilters(data, listOf(f("ev", "eq", "Purchase"))))
        assertTrue(checkPassOnFilters(data, listOf(f("ev", "eq", "Lead", "Purchase"))))
        assertFalse(checkPassOnFilters(data, listOf(f("ev", "eq", "Lead"))))
    }

    @Test
    fun eqIsLooseAcrossNumbersAndStrings() {
        assertTrue(checkPassOnFilters(data, listOf(f("count", "eq", "5"))))
        assertFalse(checkPassOnFilters(data, listOf(f("count", "eq", "6"))))
    }

    @Test
    fun containsIsSubstringNotEquality() {
        assertTrue(checkPassOnFilters(data, listOf(f("ev", "contains", "Purch"))))
        assertFalse(checkPassOnFilters(data, listOf(f("count", "contains", "5"))))
    }

    @Test
    fun neFailsWhenAnyValueMatches() {
        assertTrue(checkPassOnFilters(data, listOf(f("ev", "ne", "Lead"))))
        assertFalse(checkPassOnFilters(data, listOf(f("ev", "ne", "Lead", "Purchase"))))
    }

    @Test
    fun blankPassesOnlyForNullOrEmptyString() {
        assertTrue(checkPassOnFilters(data, listOf(f("empty", "blank"))))
        assertTrue(checkPassOnFilters(data, listOf(f("missing", "blank"))))
        assertFalse(checkPassOnFilters(data, listOf(f("ev", "blank"))))
        assertFalse(checkPassOnFilters(data, listOf(f("count", "blank"))))
    }

    @Test
    fun notBlankRequiresANonEmptyValue() {
        assertTrue(checkPassOnFilters(data, listOf(f("ev", "not_blank"))))
        assertFalse(checkPassOnFilters(data, listOf(f("empty", "not_blank"))))
        assertFalse(checkPassOnFilters(data, listOf(f("missing", "not_blank"))))
    }

    @Test
    fun existAndNotExist() {
        assertTrue(checkPassOnFilters(data, listOf(f("empty", "exist"))))
        assertFalse(checkPassOnFilters(data, listOf(f("missing", "exist"))))
        assertTrue(checkPassOnFilters(data, listOf(f("missing", "not_exist"))))
        assertFalse(checkPassOnFilters(data, listOf(f("ev", "not_exist"))))
    }

    @Test
    fun unknownOperatorPassesForForwardCompatibility() {
        assertTrue(checkPassOnFilters(data, listOf(f("ev", "future_op", "whatever"))))
        assertTrue(checkPassOnFilters(data, listOf(f("ev", null))))
    }

    @Test
    fun flatListIsAnAndOfAllFilters() {
        assertTrue(
            checkPassOnFilters(data, listOf(f("ev", "eq", "Purchase"), f("currency", "eq", "AUD")))
        )
        assertFalse(
            checkPassOnFilters(data, listOf(f("ev", "eq", "Purchase"), f("currency", "eq", "USD")))
        )
    }

    @Test
    fun dotPathKeysResolveIntoNestedData() {
        assertTrue(checkPassOnFilters(data, listOf(f("nested.deep", "eq", "value"))))
    }

    @Test
    fun eqCoercesArrayValuesLikeJavaScript() {
        // dotAccess through an array yields a list; JS ["Purchase"] == "Purchase".
        val arrayData = mapOf("items" to listOf(mapOf("name" to "Purchase")))
        assertTrue(checkPassOnFilters(arrayData, listOf(f("items.name", "eq", "Purchase"))))
        assertFalse(checkPassOnFilters(arrayData, listOf(f("items.name", "eq", "Lead"))))
        // Multi-element lists join with commas, again like JS.
        val multi = mapOf("items" to listOf(mapOf("name" to "A"), mapOf("name" to "B")))
        assertTrue(checkPassOnFilters(multi, listOf(f("items.name", "eq", "A,B"))))
    }

    @Test
    fun groupsAreOredAndAllAnyWorkWithinAGroup() {
        // (ev=Lead AND currency=AUD) OR (ev=Purchase) is true via the second group.
        assertTrue(
            checkPassOnFilterGroups(
                data,
                listOf(
                    WireFilterGroup("all", listOf(f("ev", "eq", "Lead"), f("currency", "eq", "AUD"))),
                    WireFilterGroup("all", listOf(f("ev", "eq", "Purchase"))),
                )
            )
        )
        // Any-group: one of two matching is enough.
        assertTrue(
            checkPassOnFilterGroups(
                data,
                listOf(WireFilterGroup("any", listOf(f("ev", "eq", "Lead"), f("currency", "eq", "AUD"))))
            )
        )
        // No group satisfied.
        assertFalse(
            checkPassOnFilterGroups(
                data,
                listOf(
                    WireFilterGroup("all", listOf(f("ev", "eq", "Lead"))),
                    WireFilterGroup("any", listOf(f("currency", "eq", "USD"), f("id", "eq", "nope"))),
                )
            )
        )
    }

    @Test
    fun emptyOrAbsentGroupsPassEverything() {
        assertTrue(checkPassOnFilterGroups(data, emptyList()))
        assertTrue(checkPassOnFilterGroups(data, null))
        // A group with zero conditions is skipped, not an automatic pass.
        assertFalse(
            checkPassOnFilterGroups(
                data,
                listOf(
                    WireFilterGroup("all", emptyList()),
                    WireFilterGroup("all", listOf(f("ev", "eq", "Lead"))),
                )
            )
        )
    }
}
