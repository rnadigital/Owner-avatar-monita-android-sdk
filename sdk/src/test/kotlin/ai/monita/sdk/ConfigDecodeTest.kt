// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.decodeRemoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDecodeTest {

    @Test
    fun decodesTheExactProdWireKeysIncludingTypos() {
        val config = decodeRemoteConfig(
            """
            {
              "monitoringVersion": "46",
              "vendors": [
                {
                  "vendorName": "Facebook (Meta Pixel)",
                  "urlPatternMatches": ["facebook.com/tr/?id=8117817644981394"],
                  "eventParamter": "{{id}}-{{ev}}",
                  "execludeParameters": ["sw"],
                  "filters": [{"key": "ev", "op": "eq", "val": ["Purchase"]}]
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals("46", config.monitoringVersion)
        val vendor = config.vendors[0]
        assertEquals("Facebook (Meta Pixel)", vendor.vendorName)
        assertEquals(listOf("facebook.com/tr/?id=8117817644981394"), vendor.urlPatternMatches)
        assertEquals("{{id}}-{{ev}}", vendor.eventParameter)
        assertEquals(listOf("sw"), vendor.excludeParameters)
        assertEquals("ev", vendor.filters[0].key)
        assertEquals("eq", vendor.filters[0].op)
        assertEquals(listOf("Purchase"), vendor.filters[0].values)
    }

    @Test
    fun unknownFieldsAreIgnoredNeverFatal() {
        val config = decodeRemoteConfig(
            """
            {
              "monitoringVersion": "1",
              "someFutureField": {"nested": true},
              "vendors": [
                {"vendorName": "X", "urlPatternMatches": ["x.com"], "surpriseKey": [1, 2, 3]}
              ]
            }
            """.trimIndent()
        )
        assertEquals("1", config.monitoringVersion)
        assertEquals("X", config.vendors[0].vendorName)
    }

    @Test
    fun filterValAcceptsBareStringOrArray() {
        val config = decodeRemoteConfig(
            """
            {"monitoringVersion":"1","vendors":[{"vendorName":"X","urlPatternMatches":[],
             "filters":[{"key":"a","op":"eq","val":"single"},{"key":"b","op":"eq","val":["1","2"]}]}]}
            """.trimIndent()
        )
        assertEquals(listOf("single"), config.vendors[0].filters[0].values)
        assertEquals(listOf("1", "2"), config.vendors[0].filters[1].values)
    }

    @Test
    fun filterGroupsDecode() {
        val config = decodeRemoteConfig(
            """
            {"monitoringVersion":"1","vendors":[{"vendorName":"X","urlPatternMatches":[],
             "filterGroups":[
               {"op":"any","filters":[{"key":"ev","op":"eq","val":["A"]},{"key":"ev","op":"eq","val":["B"]}]},
               {"op":"all","filters":[{"key":"currency","op":"eq","val":["AUD"]}]}
             ]}]}
            """.trimIndent()
        )
        val groups = config.vendors[0].filterGroups
        assertEquals(2, groups.size)
        assertEquals("any", groups[0].op)
        assertEquals(2, groups[0].filters.size)
        assertEquals("all", groups[1].op)
    }

    @Test
    fun monitoringStatusAndManualMonitoringDecode() {
        val paused = decodeRemoteConfig("""{"monitoringVersion":"2","monitoringStatus":"paused","vendors":[]}""")
        assertEquals("paused", paused.monitoringStatus)
        val plain = decodeRemoteConfig("""{"monitoringVersion":"2","vendors":[]}""")
        assertNull(plain.monitoringStatus)
        assertFalse(plain.allowManualMonitoring)
        val manual = decodeRemoteConfig("""{"monitoringVersion":"2","allowManualMonitoring":true,"vendors":[]}""")
        assertTrue(manual.allowManualMonitoring)
    }

    @Test
    fun missingOptionalFieldsDefaultSafely() {
        val config = decodeRemoteConfig("""{"monitoringVersion":"9","vendors":[{"vendorName":"Y"}]}""")
        val vendor = config.vendors[0]
        assertTrue(vendor.urlPatternMatches.isEmpty())
        assertNull(vendor.eventParameter)
        assertTrue(vendor.excludeParameters.isEmpty())
        assertTrue(vendor.filters.isEmpty())
        assertTrue(vendor.filterGroups.isEmpty())
    }

    @Test
    fun filterWithUnknownOperatorStillDecodes() {
        val config = decodeRemoteConfig(
            """
            {"monitoringVersion":"1","vendors":[{"vendorName":"X","urlPatternMatches":[],
             "filters":[{"key":"a","op":"matches_fancy_future_op","val":["x"]}]}]}
            """.trimIndent()
        )
        assertEquals("matches_fancy_future_op", config.vendors[0].filters[0].op)
    }
}
