package staff.android.release

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual repair Activity and loopback HTTP adapter; no test controls enter shopper UI. */
@RunWith(AndroidJUnit4::class)
class ReleaseFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun request(path: String, body: JSONObject? = null): JSONObject {
        val connection = URL("http://127.0.0.1:8876$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            assertEquals("Fixture administration failed", 200, connection.responseCode)
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }
    @Before fun resetFixture() {
        assertEquals("This suite must execute against repairDebug", "repair", BuildConfig.RELEASE_STAGE)
        request("/admin/reset", JSONObject())
    }
    private fun configure(vararg entries: Pair<String, Any>) {
        request("/admin/config", JSONObject().apply { entries.forEach { (key, value) -> put(key, value) } })
    }
    private fun openUpdates() {
        ui.onNodeWithTag("open_order").performScrollTo().performClick()
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("view_updates").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithTag("view_updates").performScrollTo().performClick()
    }
    private fun awaitTag(tag: String) {
        ui.waitUntil(20_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    }
    private fun rows(array: JSONArray): List<JSONObject> = (0 until array.length()).map(array::getJSONObject)
    private fun events(): List<JSONObject> = rows(request("/admin/events").getJSONArray("events"))
    private fun awaitOutcomes(vararg expected: String): List<JSONObject> {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            val current = events()
            val outcomes = current.filter { it.getString("kind") == "journey" }.map { it.getString("outcome") }
            if (outcomes == expected.toList()) return current
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Expected closed journey outcomes ${expected.toList()}")
    }
    private fun retain(name: String, events: List<JSONObject>, observations: JSONObject) {
        val record = JSONObject().put("case", name).put("build", BuildConfig.VERSION_CODE)
            .put("releaseStage", BuildConfig.RELEASE_STAGE).put("events", JSONArray(events))
            .put("observations", observations)
        File(context.filesDir, "release-flow-$name.json").writeText(record.toString(2))
    }
    private fun assertOneAttempt(events: List<JSONObject>) {
        val journeys = events.filter { it.getString("kind") == "journey" }
        assertEquals(1, journeys.map { it.getString("attempt") }.distinct().size)
        assertTrue(journeys.all { it.getInt("build") == 2603 })
    }

    @Test fun unknownStatusPublishesNeutralTextAndKeepsTheSavedReceipt() {
        configure("status" to "awaiting_collection")
        openUpdates()
        awaitTag("delivery_status")
        ui.onNodeWithTag("delivery_status").assertTextEquals("A new delivery update")
        ui.onNodeWithTag("delivery_detail").assertTextContains("Your order and receipt are still available.", substring = true)
        ui.onAllNodesWithText("Delivered").assertCountEquals(0)
        ui.onNodeWithTag("order_id").performScrollTo().assertTextEquals("Order CG-1042")
        val events = awaitOutcomes("started", "shown")
        assertOneAttempt(events)
        assertEquals(1, events.count { it.getString("kind") == "timeline_requested" && it.getString("vocabulary") == "new" })
        retain("unknown-neutral", events, JSONObject().put("status", "neutral").put("receiptVisible", true))
    }

    @Test fun freshDisabledPolicyDoesNotRequestTheTimeline() {
        configure("timelineEnabled" to false, "status" to "awaiting_collection")
        openUpdates()
        awaitTag("updates_disabled")
        val events = awaitOutcomes("started", "disabled")
        assertOneAttempt(events)
        assertEquals(1, events.count { it.getString("kind") == "policy" })
        assertEquals(0, events.count { it.getString("kind").startsWith("timeline_") })
        retain("disabled-before-request", events, JSONObject().put("disabledVisible", true).put("timelineRequests", 0))
    }

    @Test fun leaseExpiringDuringStatusIoCannotPublishTheArrivingStatus() {
        configure("ttlSeconds" to 1, "delayMs" to 2200, "status" to "awaiting_collection")
        openUpdates()
        awaitTag("updates_disabled")
        ui.onAllNodesWithTag("delivery_status").assertCountEquals(0)
        val events = awaitOutcomes("started", "disabled")
        assertOneAttempt(events)
        assertEquals(1, events.count { it.getString("kind") == "timeline_requested" })
        assertEquals(1, events.count { it.getString("kind") == "timeline_completed" })
        retain("expiry-after-io", events, JSONObject().put("ttlSeconds", 1).put("fixtureDelayMs", 2200).put("statusPublished", false))
    }

    @Test fun failedPolicyFetchWithANewGateDefaultsToDisabled() {
        configure("policyHttp" to 503, "status" to "awaiting_collection")
        openUpdates()
        awaitTag("updates_disabled")
        val events = awaitOutcomes("started", "disabled")
        assertOneAttempt(events)
        assertEquals(1, events.count { it.getString("kind") == "policy" && it.getInt("http") == 503 })
        assertEquals(0, events.count { it.getString("kind").startsWith("timeline_") })
        retain("new-gate-policy-failure", events, JSONObject().put("newActivityViewModel", true).put("timelineRequests", 0))
    }

    @Test fun unavailableTimelineRecoversOnExplicitRefresh() {
        configure("timelineHttp" to 503)
        openUpdates()
        awaitTag("updates_unavailable")
        awaitOutcomes("started", "unavailable")
        configure("timelineHttp" to 200, "status" to "in_transit")
        ui.onNodeWithTag("refresh_updates").performScrollTo().performClick()
        awaitTag("delivery_status")
        ui.onNodeWithTag("delivery_status").assertTextEquals("On its way")
        val events = awaitOutcomes("started", "unavailable", "started", "shown")
        assertEquals(2, events.filter { it.getString("kind") == "journey" }.map { it.getString("attempt") }.distinct().size)
        assertEquals(listOf(503, 200), events.filter { it.getString("kind") == "timeline_requested" }.map { it.getInt("http") })
        retain("unavailable-recovery", events, JSONObject().put("explicitRefresh", true).put("status", "in_transit"))
    }
}
