// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.MonitaCore
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden payload test: initializes the SDK against a local server, drives
 * real OkHttp traffic through MonitaInterceptor, and asserts the exact wire
 * shape (envelope and flat) matching the JS SDK reference output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndToEndGoldenTest {

    private val token = "dom_goldentesttoken12345678"
    private lateinit var server: MockWebServer
    private val collectBodies = java.util.concurrent.LinkedBlockingQueue<String>()

    private val sharedKeys =
        listOf("t", "dm", "mv", "sv", "u", "p", "vid", "sid", "s", "do", "rl", "env", "et", "cn")
    private val eventKeys = listOf("tm", "e", "vn", "st", "m", "vu", "dt", "np")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/custom-config/") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("ETag", "\"etag-1\"")
                        .setBody(configBody())
                    path.startsWith("/api/v1") -> {
                        collectBodies.add(request.body.readUtf8())
                        MockResponse().setResponseCode(204)
                    }
                    path.startsWith("/tr") -> MockResponse().setResponseCode(200).setBody("ok")
                    path.startsWith("/fail") -> MockResponse().setResponseCode(500).setBody("boom")
                    path.startsWith("/fan") -> MockResponse().setResponseCode(200).setBody("ok")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val context = ApplicationProvider.getApplicationContext<Context>()
        declareInternetCapability(context)
        MonitaCore.testFlushDelayMsOverride = 300
        MonitaCore.testEnvDelayMsOverride = 10 * 60 * 1000L
        Monita.initialize(
            context,
            MonitaConfig.Builder(token)
                .configEndpoint(server.url("/custom-config/$token.json").toString())
                .collectEndpoint(server.url("/api/v1").toString())
                .build()
        )
        val core = Monita.coreOrNull()!!
        awaitTrue("config applied") { core.configManager.current != null }
    }

    @After
    fun tearDown() {
        MonitaCore.testFlushDelayMsOverride = null
        MonitaCore.testEnvDelayMsOverride = null
        Monita.shutdownForTesting()
        server.shutdown()
    }

    private fun configBody(): String {
        val host = "localhost:${server.port}"
        return """
        {
          "monitoringVersion": "46",
          "allowManualMonitoring": true,
          "vendors": [
            {
              "vendorName": "Fan Vendor",
              "urlPatternMatches": ["$host/fan"],
              "eventParamter": "{{items.name}}"
            },
            {
              "vendorName": "Facebook (Meta Pixel)",
              "urlPatternMatches": ["$host/tr", "$host/fail"],
              "eventParamter": "{{ev}}",
              "execludeParameters": ["secret"],
              "filters": [{"key": "ev", "op": "not_blank"}]
            }
          ]
        }
        """.trimIndent()
    }

    /** Robolectric's default network omits NET_CAPABILITY_INTERNET; real devices have it. */
    private fun declareInternetCapability(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        org.robolectric.Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun client(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(MonitaInterceptor()).build()

    private fun nextCollectBody(): JsonObject {
        val body = collectBodies.poll(10, TimeUnit.SECONDS)
            ?: throw AssertionError("No collect POST arrived")
        return Json.parseToJsonElement(body).jsonObject
    }

    @Test
    fun capturedTrafficShipsWithTheExactWireShape() {
        val http = client()

        // 1. A single matched request ships in the legacy flat shape with the
        //    exact shared plus per event key set, in order.
        http.newCall(
            Request.Builder()
                .url(server.url("/tr/?id=8117&ev=Purchase"))
                .post("""{"currency":"AUD","value":10,"secret":"hide-me"}""".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().close()

        val flat = nextCollectBody()
        assertEquals((sharedKeys + eventKeys).sorted(), flat.keys.sorted())
        assertEquals(token, flat["t"]!!.jsonPrimitive.content)
        assertEquals("app", flat["dm"]!!.jsonPrimitive.content)
        assertEquals("2.0.0", flat["mv"]!!.jsonPrimitive.content)
        assertEquals("46", flat["sv"]!!.jsonPrimitive.content)
        val packageName = ApplicationProvider.getApplicationContext<Context>().packageName
        assertEquals("app://$packageName", flat["u"]!!.jsonPrimitive.content)
        assertEquals("", flat["p"]!!.jsonPrimitive.content)
        assertTrue(flat["vid"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(flat["sid"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals("android-sdk", flat["s"]!!.jsonPrimitive.content)
        assertEquals(packageName, flat["do"]!!.jsonPrimitive.content)
        assertTrue(flat["rl"]!!.jsonPrimitive.content.startsWith("android "))
        assertEquals("production", flat["env"]!!.jsonPrimitive.content)
        assertEquals("", flat["et"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, flat["cn"])
        assertNull(flat["cid"])
        assertTrue(flat["tm"]!!.jsonPrimitive.content.toDouble() > 1_000_000_000.0)
        assertEquals("Purchase", flat["e"]!!.jsonPrimitive.content)
        assertEquals("Facebook (Meta Pixel)", flat["vn"]!!.jsonPrimitive.content)
        assertEquals("success", flat["st"]!!.jsonPrimitive.content)
        assertEquals("POST", flat["m"]!!.jsonPrimitive.content)
        assertTrue(flat["vu"]!!.jsonPrimitive.content.contains("/tr/?id=8117&ev=Purchase"))
        assertEquals(0, flat["np"]!!.jsonArray.size)
        val dt = flat["dt"]!!.jsonArray
        assertEquals(1, dt.size)
        val params = dt[0].jsonObject
        assertEquals("8117", params["id"]!!.jsonPrimitive.content)
        assertEquals("Purchase", params["ev"]!!.jsonPrimitive.content)
        assertEquals("AUD", params["currency"]!!.jsonPrimitive.content)
        assertEquals("10", params["value"]!!.jsonPrimitive.content)
        assertNull(params["secret"]) // execludeParameters removed it
        assertTrue(params["__mon_url"]!!.jsonPrimitive.content.contains("/tr/"))
        assertEquals("localhost:${server.port}", params["__mon_host"]!!.jsonPrimitive.content)
        assertEquals("POST", params["__mon_method"]!!.jsonPrimitive.content)
        assertEquals("Facebook (Meta Pixel)", params["vendorName"]!!.jsonPrimitive.content)

        // 2. Two rapid events ship as ONE envelope: shared context hoisted once,
        //    per event fields inside "events".
        http.newCall(Request.Builder().url(server.url("/tr/?ev=AddToCart")).get().build()).execute().close()
        http.newCall(Request.Builder().url(server.url("/tr/?ev=Lead")).get().build()).execute().close()
        val envelope = nextCollectBody()
        assertEquals((sharedKeys + "events").sorted(), envelope.keys.sorted())
        val events = envelope["events"]!!.jsonArray
        assertEquals(2, events.size)
        assertEquals(listOf("AddToCart", "Lead"), events.map { it.jsonObject["e"]!!.jsonPrimitive.content })
        for (entry in events) {
            assertEquals(eventKeys.sorted(), entry.jsonObject.keys.sorted())
            assertEquals("GET", entry.jsonObject["m"]!!.jsonPrimitive.content)
        }

        // 3. A failed vendor response is reported with st failure.
        http.newCall(Request.Builder().url(server.url("/fail?ev=Broken")).get().build()).execute().close()
        val failed = nextCollectBody()
        assertEquals("failure", failed["st"]!!.jsonPrimitive.content)
        assertEquals("Broken", failed["e"]!!.jsonPrimitive.content)

        // 4. Array fan-out: one request whose template resolves to two values
        //    yields two events sharing one envelope.
        http.newCall(
            Request.Builder()
                .url(server.url("/fan"))
                .post("""{"items":[{"name":"view_a"},{"name":"view_b"}]}""".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().close()
        val fanned = nextCollectBody()
        val fannedEvents = fanned["events"]!!.jsonArray
        assertEquals(
            listOf("view_a", "view_b"),
            fannedEvents.map { it.jsonObject["e"]!!.jsonPrimitive.content }
        )
        assertEquals("Fan Vendor", fannedEvents[0].jsonObject["vn"]!!.jsonPrimitive.content)

        // 5. Filters drop unmatched traffic: ev is blank so not_blank fails.
        http.newCall(Request.Builder().url(server.url("/tr/?id=99")).get().build()).execute().close()
        assertNull(collectBodies.poll(1, TimeUnit.SECONDS))

        // 6. Manual send flows through the same pipeline when enabled.
        Monita.send("Facebook (Meta Pixel)", "manual_purchase", mapOf("ev" to "manual_purchase", "value" to 5))
        val manual = nextCollectBody()
        assertEquals("manual_purchase", manual["e"]!!.jsonPrimitive.content)
        assertEquals("Facebook (Meta Pixel)", manual["vn"]!!.jsonPrimitive.content)
        assertEquals("POST", manual["m"]!!.jsonPrimitive.content)
        assertNull(manual["st"]) // manual events carry no tag status

        // 7. Screen and customer id ride the shared context once set.
        Monita.setScreen("Checkout")
        Monita.setCustomerId("cust-1")
        http.newCall(Request.Builder().url(server.url("/tr/?ev=Screened")).get().build()).execute().close()
        val screened = nextCollectBody()
        assertEquals("app://$packageName/Checkout", screened["u"]!!.jsonPrimitive.content)
        assertEquals("Checkout", screened["p"]!!.jsonPrimitive.content)
        assertEquals("cust-1", screened["cid"]!!.jsonPrimitive.content)

        // 8. Opt out stops capture entirely.
        Monita.optOut()
        http.newCall(Request.Builder().url(server.url("/tr/?ev=AfterOptOut")).get().build()).execute().close()
        assertNull(collectBodies.poll(1, TimeUnit.SECONDS))
        Monita.optIn()

        // 9. The event filter hook can veto payloads.
        Monita.setEventFilter { payload -> payload["e"] != "Vetoed" }
        http.newCall(Request.Builder().url(server.url("/tr/?ev=Vetoed")).get().build()).execute().close()
        assertNull(collectBodies.poll(1, TimeUnit.SECONDS))
        Monita.setEventFilter(null)

        // 10. Requests to the SDK's own endpoints are never captured.
        val core = Monita.coreOrNull()!!
        assertTrue(core.isInternalEndpoint(server.url("/api/v1").toString()))
        assertNotNull(core)
    }
}
