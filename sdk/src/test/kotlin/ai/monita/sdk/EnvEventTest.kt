// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.MonitaCore
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The monita_env event: sent once per session shortly after init, through
 * the same envelope, with the mobile environment snapshot in dt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvEventTest {

    private val token = "dom_envtesttoken1234567890x"
    private lateinit var server: MockWebServer
    private val collectBodies = LinkedBlockingQueue<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/custom-config/") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"monitoringVersion":"7","vendors":[]}""")
                    path.startsWith("/api/v1") -> {
                        collectBodies.add(request.body.readUtf8())
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric's default network omits NET_CAPABILITY_INTERNET; real devices have it.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        org.robolectric.Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        MonitaCore.testFlushDelayMsOverride = 100
        MonitaCore.testEnvDelayMsOverride = 100
        Monita.initialize(
            context,
            MonitaConfig.Builder(token)
                .configEndpoint(server.url("/custom-config/$token.json").toString())
                .collectEndpoint(server.url("/api/v1").toString())
                .build()
        )
    }

    @After
    fun tearDown() {
        MonitaCore.testFlushDelayMsOverride = null
        MonitaCore.testEnvDelayMsOverride = null
        Monita.shutdownForTesting()
        server.shutdown()
    }

    @Test
    fun envEventShipsOncePerSessionWithTheSnapshotShape() {
        val body = collectBodies.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("No env event arrived")
        val flat: JsonObject = Json.parseToJsonElement(body).jsonObject
        assertEquals("monita_env", flat["e"]!!.jsonPrimitive.content)
        assertEquals("Monita", flat["vn"]!!.jsonPrimitive.content)
        assertEquals(token, flat["t"]!!.jsonPrimitive.content)
        assertEquals("app", flat["dm"]!!.jsonPrimitive.content)
        assertEquals("7", flat["sv"]!!.jsonPrimitive.content)
        // Env events carry no request fields.
        assertFalse(flat.containsKey("m"))
        assertFalse(flat.containsKey("vu"))
        assertFalse(flat.containsKey("st"))
        assertFalse(flat.containsKey("np"))
        val env = flat["dt"]!!.jsonArray[0].jsonObject
        assertEquals(
            listOf("app_build", "app_version", "cmp", "host", "model", "os", "sdk_version", "sdks", "tcf"),
            env.keys.sorted()
        )
        assertTrue(env["os"]!!.jsonPrimitive.content.startsWith("android "))
        assertEquals("2.0.0", env["sdk_version"]!!.jsonPrimitive.content)
        assertEquals(
            ApplicationProvider.getApplicationContext<Context>().packageName,
            env["host"]!!.jsonPrimitive.content
        )
        assertEquals("false", env["tcf"]!!.jsonPrimitive.content)

        // A second config refresh in the same session does not resend it.
        Monita.refreshConfig()
        assertEquals(null, collectBodies.poll(1, TimeUnit.SECONDS))
    }
}
