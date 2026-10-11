package staff.android.release

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import staff.android.release.core.JourneyEvent

data class PolicyReply(val enabled: Boolean, val ttlSeconds: Int)
class ReleaseApi(private val baseUrl: String = "http://127.0.0.1:8876") {
    private suspend fun request(path: String, body: String? = null): JsonObject = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3_000; connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            if (connection.responseCode != 200) throw IOException("Service unavailable")
            val bytes = connection.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() <= 16_384) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 16_385 - out.size()))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                val bytes = out.toByteArray()
                if (bytes.size > 16_384) throw IOException("Response too large")
                bytes
            }
            try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                Json.parseToJsonElement(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()).jsonObject
            } catch (error: IllegalArgumentException) { throw IOException("Invalid service response", error) }
        } finally { connection.disconnect() }
    }
    suspend fun policy(): PolicyReply {
        val value = request("/config")
        val enabled = (value["timelineEnabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw IOException("Invalid policy")
        val ttl = (value["ttlSeconds"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw IOException("Invalid policy lifetime")
        if (ttl !in 0..60) throw IOException("Unsupported policy lifetime")
        return PolicyReply(enabled, ttl)
    }
    suspend fun status(): String {
        val raw = (request("/v2/timeline")["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IOException("Missing delivery status")
        if (!raw.matches(Regex("[a-z_]{1,32}"))) throw IOException("Invalid delivery status")
        return raw
    }
    suspend fun record(event: JourneyEvent) {
        try { request("/telemetry", buildJsonObject {
            put("attempt", event.attempt); put("build", event.build); put("osApi", event.osApi); put("outcome", event.outcome.name.lowercase())
        }.toString()) } catch (_: IOException) { /* Diagnostics must not decide shopping availability. */ }
    }
}
