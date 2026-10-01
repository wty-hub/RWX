package io.github.rwx.compatibility

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class GameCompatibilityProbeTest {
    @Test fun `probe is disabled by default and rejects unsafe port or remote peer addresses`() {
        assertNull(GameCompatibilityProbe.configuredPort(emptyMap()))
        assertNull(GameCompatibilityProbe.configuredPort(mapOf("RWX_COMPAT_PROBE_PORT" to "")))
        assertEquals(5124, GameCompatibilityProbe.configuredPort(mapOf("RWX_COMPAT_PROBE_PORT" to "5124")))
        for (port in listOf("0", "1023", "65536", "invalid")) {
            assertFailsWith<IllegalArgumentException> { GameCompatibilityProbe.configuredPort(mapOf("RWX_COMPAT_PROBE_PORT" to port)) }
        }
        assertEquals("127.0.0.1:5123", GameCompatibilityProbe.loopbackAddress("localhost:5123"))
        for (address in listOf("example.com:5123", "192.168.1.1:5123", "127.0.0.1:80", "127.0.0.1:70000")) {
            assertFailsWith<IllegalArgumentException> { GameCompatibilityProbe.loopbackAddress(address) }
        }
    }

    @Test fun `HTTP acknowledgement waits for actual owner execution and preserves its result`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = Executors.newSingleThreadExecutor { Thread(it, "compatibility-test-owner") }
        val server = GameCompatibilityProbe.LoopbackServer(0, { request ->
            CompletableFuture.supplyAsync({
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                buildJsonObject { put("thread", Thread.currentThread().name); put("accepted", false); put("value", request["value"]!!) }
            }, owner)
        })
        try {
            val result = HttpClient.newHttpClient().sendAsync(request(server.port, "{\"op\":\"status\",\"value\":37}"), HttpResponse.BodyHandlers.ofString())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(result.isDone, "HTTP returned before its owner operation completed")
            release.countDown()
            val response = result.get(5, TimeUnit.SECONDS)
            val payload = Json.parseToJsonElement(response.body()).jsonObject
            assertEquals(200, response.statusCode())
            assertTrue(payload["ok"]!!.jsonPrimitive.boolean)
            val data = payload["data"]!!.jsonObject
            assertEquals("compatibility-test-owner", data["thread"]!!.jsonPrimitive.content)
            assertFalse(data["accepted"]!!.jsonPrimitive.boolean)
            assertEquals(37, data["value"]!!.jsonPrimitive.int)
        } finally { release.countDown(); server.close(); owner.shutdownNow() }
    }

    @Test fun `protocol failures do not dispatch engine work and engine failures remain visible`() {
        val dispatched = AtomicInteger()
        val server = GameCompatibilityProbe.LoopbackServer(0, {
            dispatched.incrementAndGet()
            CompletableFuture<JsonObject>().apply { completeExceptionally(IllegalStateException("Actual host failure")) }
        })
        try {
            val client = HttpClient.newHttpClient()
            val bad = client.send(request(server.port, "not-json"), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, bad.statusCode())
            assertEquals(0, dispatched.get())
            val huge = client.send(request(server.port, " ".repeat(16_385)), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, huge.statusCode())
            assertEquals(0, dispatched.get())
            val failed = client.send(request(server.port, "{\"op\":\"host\"}"), HttpResponse.BodyHandlers.ofString())
            assertEquals(409, failed.statusCode())
            assertEquals(1, dispatched.get())
            val payload = Json.parseToJsonElement(failed.body()).jsonObject
            assertFalse(payload["ok"]!!.jsonPrimitive.boolean)
            assertEquals("Actual host failure", payload["error"]!!.jsonPrimitive.content)
        } finally { server.close() }
    }

    private fun request(port: Int, body: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/probe"))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
}
