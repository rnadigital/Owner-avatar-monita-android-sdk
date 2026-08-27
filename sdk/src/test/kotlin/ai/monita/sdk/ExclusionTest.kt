// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.deleteProperty
import ai.monita.sdk.internal.filterData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionTest {

    private fun sampleData(): LinkedHashMap<String, Any?> = linkedMapOf(
        "ev" to "Purchase",
        "email" to "person@example.com",
        "user" to linkedMapOf<String, Any?>(
            "email" to "nested@example.com",
            "name" to "Jane",
            "token_a" to "1",
            "token_b" to "2",
        ),
        "keep" to "yes",
    )

    @Test
    fun deletesATopLevelKey() {
        val data = sampleData()
        deleteProperty(data, "email")
        assertFalse(data.containsKey("email"))
        assertTrue(data.containsKey("ev"))
    }

    @Test
    fun deletesByDotPath() {
        val data = sampleData()
        deleteProperty(data, "user.email")
        val user = data["user"] as Map<*, *>
        assertFalse(user.containsKey("email"))
        assertTrue(user.containsKey("name"))
    }

    @Test
    fun regexSegmentsDeleteEveryMatchingKey() {
        val data = sampleData()
        deleteProperty(data, "user./^token_/")
        val user = data["user"] as Map<*, *>
        assertFalse(user.containsKey("token_a"))
        assertFalse(user.containsKey("token_b"))
        assertTrue(user.containsKey("name"))
    }

    @Test
    fun regexSegmentCanAppearMidPath() {
        val data = linkedMapOf<String, Any?>(
            "a1" to linkedMapOf<String, Any?>("secret" to "x", "ok" to "y"),
            "a2" to linkedMapOf<String, Any?>("secret" to "x"),
            "b" to linkedMapOf<String, Any?>("secret" to "x"),
        )
        deleteProperty(data, "/^a/.secret")
        assertFalse((data["a1"] as Map<*, *>).containsKey("secret"))
        assertFalse((data["a2"] as Map<*, *>).containsKey("secret"))
        assertTrue((data["b"] as Map<*, *>).containsKey("secret"))
        assertTrue((data["a1"] as Map<*, *>).containsKey("ok"))
    }

    @Test
    fun numericSegmentsAddressArrayElements() {
        val data = linkedMapOf<String, Any?>(
            "products" to arrayListOf<Any?>(
                linkedMapOf<String, Any?>("email" to "a@example.com", "sku" to "s1"),
                linkedMapOf<String, Any?>("email" to "b@example.com", "sku" to "s2"),
            ),
        )
        deleteProperty(data, "products.0.email")
        val products = data["products"] as List<*>
        assertFalse((products[0] as Map<*, *>).containsKey("email"))
        assertTrue((products[1] as Map<*, *>).containsKey("email"))
        assertEquals("s1", (products[0] as Map<*, *>)["sku"])
    }

    @Test
    fun regexSegmentsFanOutOverArrayIndices() {
        val data = linkedMapOf<String, Any?>(
            "products" to arrayListOf<Any?>(
                linkedMapOf<String, Any?>("email" to "a@example.com", "sku" to "s1"),
                linkedMapOf<String, Any?>("email" to "b@example.com", "sku" to "s2"),
                linkedMapOf<String, Any?>("email" to "c@example.com", "sku" to "s3"),
            ),
        )
        deleteProperty(data, "products./\\d+/.email")
        val products = data["products"] as List<*>
        for (product in products) {
            assertFalse((product as Map<*, *>).containsKey("email"))
            assertTrue(product.containsKey("sku"))
        }
    }

    @Test
    fun deletingAnArrayLeafNullsTheElementLikeAJsHole() {
        val data = linkedMapOf<String, Any?>(
            "tokens" to arrayListOf<Any?>("secret-1", "secret-2", "keep"),
        )
        deleteProperty(data, "tokens.1")
        val tokens = data["tokens"] as List<*>
        assertEquals("secret-1", tokens[0])
        assertEquals(null, tokens[1])
        assertEquals("keep", tokens[2])
        assertEquals(3, tokens.size)
    }

    @Test
    fun outOfRangeAndNonNumericArraySegmentsAreNoOps() {
        val data = linkedMapOf<String, Any?>(
            "items" to arrayListOf<Any?>(linkedMapOf<String, Any?>("a" to "1")),
        )
        deleteProperty(data, "items.9.a")
        deleteProperty(data, "items.notanindex.a")
        assertEquals("1", ((data["items"] as List<*>)[0] as Map<*, *>)["a"])
    }

    @Test
    fun missingPathsAndBadRegexNeverThrow() {
        val data = sampleData()
        deleteProperty(data, "does.not.exist")
        deleteProperty(data, "user./[unclosed/")
        assertEquals("Purchase", data["ev"])
    }

    @Test
    fun filterDataAppliesTheExclusionList() {
        val out = filterData("Vendor", sampleData(), listOf("email", "user.email"))
        assertFalse(out.containsKey("email"))
        assertFalse((out["user"] as Map<*, *>).containsKey("email"))
        assertEquals("yes", out["keep"])
    }

    @Test
    fun filterDataDeepCopiesSoTheSourceIsNeverMutated() {
        val source = sampleData()
        filterData("Vendor", source, listOf("user.email"))
        assertTrue((source["user"] as Map<*, *>).containsKey("email"))
    }

    @Test
    fun filterDataCapsAtOneHundredParams() {
        val big = LinkedHashMap<String, Any?>()
        for (i in 0 until 150) {
            big["k$i"] = i.toLong()
        }
        val out = filterData("Vendor", big, emptyList())
        assertEquals(100, out.size)
        assertTrue(out.containsKey("k0"))
        assertTrue(out.containsKey("k99"))
        assertFalse(out.containsKey("k100"))
    }

    @Test
    fun filterDataWithoutAVendorReturnsEmpty() {
        assertTrue(filterData(null, sampleData(), emptyList()).isEmpty())
        assertTrue(filterData("", sampleData(), emptyList()).isEmpty())
    }
}
