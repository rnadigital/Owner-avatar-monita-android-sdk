// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JsonValueTest {

    @Test
    fun doublesSerializeInPlainDecimalNotation() {
        val encoded = JsonValue.encode(linkedMapOf<String, Any?>("tm" to 1724745600.123))
        assertEquals("""{"tm":1724745600.123}""", encoded)
        assertFalse(encoded.contains("E"))
        assertFalse(encoded.contains("e+"))
    }

    @Test
    fun largeAndSmallDoublesStayPlain() {
        assertEquals("[1724745600]", JsonValue.encode(listOf(1724745600.0)))
        assertEquals("[0.0001]", JsonValue.encode(listOf(0.0001)))
    }

    @Test
    fun nonFiniteDoublesBecomeNullLikeJsonStringify() {
        assertEquals("[null,null,null]", JsonValue.encode(listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)))
    }

    @Test
    fun longsAndStringsRoundTrip() {
        assertEquals(
            """{"n":8117817644981394,"s":"x"}""",
            JsonValue.encode(linkedMapOf<String, Any?>("n" to 8117817644981394L, "s" to "x"))
        )
    }
}
