// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.DataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataParserTest {

    private fun parse(url: String, body: String? = null, contentType: String? = null): LinkedHashMap<String, Any?> {
        val data = LinkedHashMap<String, Any?>()
        DataParser.getBodyJson(url, body, data, contentType)
        return data
    }

    @Test
    fun standardQueryParametersAreCaptured() {
        val data = parse("https://facebook.com/tr/?id=8117&ev=Purchase")
        assertEquals("8117", data["id"])
        assertEquals("Purchase", data["ev"])
    }

    @Test
    fun queryValuesAreDecodedTwiceLikeTheJsReference() {
        // %2520 decodes to %20 on the first pass, then to a space on the second.
        val data = parse("https://v.com/c?msg=hello%2520world")
        assertEquals("hello world", data["msg"])
    }

    @Test
    fun legacySemicolonQueryParametersAreCaptured() {
        val data = parse("https://v.com/c?a=1;b=2")
        assertEquals("1", data["a"])
        assertEquals("2", data["b"])
    }

    @Test
    fun matrixPathParametersAreCaptured() {
        val data = parse("https://v.com/b/ss/rsid/1/JS-2.22.0/s123;k1=v1;k2=v2")
        assertEquals("v1", data["k1"])
        assertEquals("v2", data["k2"])
    }

    @Test
    fun jsonBodiesMergeIntoTheData() {
        val data = parse(
            "https://v.com/collect",
            body = """{"event":"purchase","value":10,"nested":{"a":true}}""",
            contentType = "application/json",
        )
        assertEquals("purchase", data["event"])
        assertEquals(10L, data["value"])
        assertEquals(mapOf("a" to true), data["nested"])
    }

    @Test
    fun formUrlencodedBodiesParse() {
        val data = parse(
            "https://v.com/collect",
            body = "en=page_view&cur=AUD&n=1",
            contentType = "application/x-www-form-urlencoded",
        )
        assertEquals("page_view", data["en"])
        assertEquals("AUD", data["cur"])
        assertEquals("1", data["n"])
    }

    @Test
    fun legacySemicolonBodiesParse() {
        val data = parse("https://v.com/collect", body = "a=1;b=2;c=3")
        assertEquals("1", data["a"])
        assertEquals("3", data["c"])
    }

    @Test
    fun plainUnparseableBodiesLandUnderTheValueKey() {
        val data = parse("https://v.com/collect", body = "just some text")
        assertEquals("just some text", data["value"])
    }

    @Test
    fun jsonPrimitiveBodiesLandUnderTheValueKey() {
        val data = parse("https://v.com/collect", body = "123")
        assertEquals(123L, data["value"])
    }

    @Test
    fun topLevelJsonArraysAreIndexed() {
        val data = parse("https://v.com/collect", body = """[{"a":1},"text",7]""")
        assertEquals(mapOf("a" to 1L), data["0"])
        assertEquals("text", data["1"])
        assertEquals(7L, data["2"])
    }

    @Test
    fun bodyParametersDoNotEraseQueryParameters() {
        val data = parse("https://v.com/c?q=1", body = """{"b":"2"}""")
        assertEquals("1", data["q"])
        assertEquals("2", data["b"])
    }

    @Test
    fun binaryContentTypesAreNotTextual() {
        assertFalse(DataParser.isTextualContentType("application/octet-stream"))
        assertFalse(DataParser.isTextualContentType("application/x-protobuf"))
        assertFalse(DataParser.isTextualContentType("image/png"))
        assertTrue(DataParser.isTextualContentType("application/json"))
        assertTrue(DataParser.isTextualContentType("application/json; charset=utf-8"))
        assertTrue(DataParser.isTextualContentType("text/plain"))
        assertTrue(DataParser.isTextualContentType(null))
    }

    @Test
    fun malformedUrlsNeverThrow() {
        val data = parse("not a url at all", body = null)
        assertTrue(data.isEmpty())
    }
}
