package at.zentriert.setlist.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayTest {
    private val key = ByteArray(32) { it.toByte() }
    private val room = "0123456789abcdef0123456789abcdef"
    private fun now() = System.currentTimeMillis() / 1000

    private fun tempStore() = FileStore(Files.createTempDirectory("setlist-server").toFile())

    private suspend fun HttpClient.signed(method: String, path: String, body: ByteArray = ByteArray(0), key: ByteArray = this@RelayTest.key, extra: (io.ktor.client.request.HttpRequestBuilder) -> Unit = {}): HttpResponse {
        val auth = Auth.header(key, now(), method, path, body)
        return when (method) {
            "PUT" -> put(path) { header(Auth.HEADER, auth); setBody(body); extra(this) }
            "GET" -> get(path) { header(Auth.HEADER, auth); extra(this) }
            "DELETE" -> delete(path) { header(Auth.HEADER, auth); extra(this) }
            else -> error(method)
        }
    }

    @Test
    fun roomsBlobsAndManifestWithAuth() = testApplication {
        val store = tempStore()
        application { bandRelay(RoomStore(), store, ServerConfig(sweep = false)) }
        val client = createClient { }
        assertEquals(HttpStatusCode.Created, client.signed("PUT", "/rooms/$room", key).status)
        assertEquals(HttpStatusCode.OK, client.signed("PUT", "/rooms/$room", key).status)                 // same key again is fine
        assertEquals(HttpStatusCode.Conflict, client.signed("PUT", "/rooms/$room", ByteArray(32), key = ByteArray(32)).status)
        // Unsigned or wrongly signed requests are refused.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/rooms/$room/manifest").status)
        assertEquals(HttpStatusCode.Unauthorized, client.signed("GET", "/rooms/$room/manifest", key = ByteArray(32)).status)
        // Empty room: no manifest, version 0.
        client.signed("GET", "/rooms/$room/manifest").let { assertEquals(HttpStatusCode.NoContent, it.status); assertEquals("\"0\"", it.headers["ETag"]) }
        // Blobs are named by their hash.
        val blob = "ciphertext".toByteArray()
        val sha = FileStore.sha256(blob)
        assertEquals(HttpStatusCode.BadRequest, client.signed("PUT", "/rooms/$room/blobs/${"0".repeat(64)}", blob).status)
        assertEquals(HttpStatusCode.Created, client.signed("PUT", "/rooms/$room/blobs/$sha", blob).status)
        assertContentEquals(blob, client.signed("GET", "/rooms/$room/blobs/$sha").bodyAsBytes())
        // Manifest: optimistic concurrency via If-Match.
        assertEquals(HttpStatusCode.PreconditionFailed, client.signed("PUT", "/rooms/$room/manifest", "m1".toByteArray()) { it.header("If-Match", "\"3\"") }.status)
        client.signed("PUT", "/rooms/$room/manifest", "m1".toByteArray()) { it.header("If-Match", "\"0\"") }.let { assertEquals(HttpStatusCode.OK, it.status); assertEquals("\"1\"", it.headers["ETag"]) }
        assertEquals(HttpStatusCode.PreconditionFailed, client.signed("PUT", "/rooms/$room/manifest", "m2".toByteArray()) { it.header("If-Match", "\"0\"") }.status)
        client.signed("GET", "/rooms/$room/manifest").let { assertEquals("m1", it.bodyAsText()); assertEquals("\"1\"", it.headers["ETag"]) }
        // Delete wipes everything.
        assertEquals(HttpStatusCode.NoContent, client.signed("DELETE", "/rooms/$room").status)
        assertEquals(HttpStatusCode.Unauthorized, client.signed("GET", "/rooms/$room/manifest").status)
    }

    @Test
    fun staleSignaturesAreRefused() {
        val header = Auth.header(key, 1000, "GET", "/x", ByteArray(0))
        assertTrue(Auth.verify(key, header, "GET", "/x", ByteArray(0), nowSeconds = 1200))
        assertTrue(!Auth.verify(key, header, "GET", "/x", ByteArray(0), nowSeconds = 1400))
        assertTrue(!Auth.verify(key, header, "GET", "/y", ByteArray(0), nowSeconds = 1000))
        assertTrue(!Auth.verify(key, header, "PUT", "/x", ByteArray(0), nowSeconds = 1000))
    }

    @Test
    fun sharesExpire() = testApplication {
        val store = tempStore()
        application { bandRelay(RoomStore(), store, ServerConfig(sweep = false)) }
        val client = createClient { }
        assertEquals(HttpStatusCode.BadRequest, client.post("/shares?ttl=5") { setBody("x".toByteArray()) }.status)
        val created = client.post("/shares?ttl=7") { setBody("sealed".toByteArray()) }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = Regex("\"id\":\"([^\"]+)\"").find(created.bodyAsText())!!.groupValues[1]
        assertContentEquals("sealed".toByteArray(), client.get("/shares/$id/blob").bodyAsBytes())
        assertTrue(client.get("/s/$id").bodyAsText().contains("In der Setlist-App öffnen"))
        assertEquals(HttpStatusCode.NotFound, client.get("/shares/nope/blob").status)
        // The sweeper removes it once expired (and untouched rooms).
        assertEquals(0, store.sweep(roomTtlMillis = 90L * 86_400_000, now = System.currentTimeMillis()))
        assertEquals(1, store.sweep(roomTtlMillis = 90L * 86_400_000, now = System.currentTimeMillis() + 8L * 86_400_000))
        assertNull(store.share(id))
    }

    @Test
    fun sweepRemovesUntouchedRooms() {
        val store = tempStore()
        kotlinx.coroutines.runBlocking { store.createRoom(room, key) }
        assertNotNull(store.authKey(room))
        assertEquals(0, store.sweep(1000, System.currentTimeMillis()))
        assertEquals(1, store.sweep(1000, System.currentTimeMillis() + 5000))
        assertNull(store.authKey(room))
    }

    @Test
    fun webSocketNeedsMembershipAndRelaysChanges() = testApplication {
        val store = tempStore()
        kotlinx.coroutines.runBlocking { store.createRoom(room, key) }
        application { bandRelay(RoomStore(), store, ServerConfig(sweep = false)) }
        val client = createClient { install(WebSockets) }
        val ts = now()
        val auth = Auth.hmacHex(key, Auth.signedText(ts, "JOIN", room, ByteArray(0)))

        // Wrong signature → Bye.
        client.webSocket("/ws") {
            send(Frame.Text("""{"t":"Join","room":"$room","name":"X","host":true,"ts":$ts,"auth":"deadbeef"}"""))
            val answer = (incoming.receive() as Frame.Text).readText()
            assertTrue(answer.contains("\"Bye\""), answer)
        }

        val received = mutableListOf<String>()
        client.webSocket("/ws") {
            send(Frame.Text("""{"t":"Join","room":"$room","name":"Host","host":true,"ts":$ts,"auth":"$auth"}"""))
            val joined = (incoming.receive() as Frame.Text).readText()
            assertTrue(joined.contains("\"isHost\":true"), joined)
            val hostSession = this
            val memberJob = launch {
                client.webSocket("/ws") {
                    send(Frame.Text("""{"t":"Join","room":"$room","name":"Leo","host":false,"ts":$ts,"auth":"$auth"}"""))
                    send(Frame.Text("""{"t":"Ping","t1":42}"""))
                    withTimeout(5000) {
                        while (received.none { it.contains("Navigate") } || received.none { it.contains("Pong") } || received.none { it.contains("Changed") }) {
                            val f = incoming.receive()
                            if (f is Frame.Text) received += f.readText()
                        }
                    }
                }
            }
            withTimeout(5000) {
                while (true) {
                    val f = incoming.receive()
                    if (f is Frame.Text && f.readText().contains("Leo")) break
                }
            }
            hostSession.send(Frame.Text("""{"t":"Navigate","index":2}"""))
            // A manifest update over HTTP notifies everyone in the live room.
            client.signed("PUT", "/rooms/$room/manifest", "m".toByteArray()) { it.header("If-Match", "\"0\"") }
            memberJob.join()
        }
        assertTrue(received.any { it.contains("\"t\":\"Pong\"") && it.contains("\"t1\":42") }, received.toString())
        assertTrue(received.any { it.contains("\"index\":2") }, received.toString())
        assertTrue(received.any { it.contains("\"t\":\"Changed\"") && it.contains("\"version\":1") }, received.toString())
    }

    @Test
    fun appLinkFilesComeFromConfig() = testApplication {
        application { bandRelay(RoomStore(), tempStore(), ServerConfig(sweep = false, androidPackage = "at.zentriert.setlist", androidSha256 = "AA:BB, CC:DD")) }
        val body = client.get("/.well-known/assetlinks.json").bodyAsText()
        assertTrue(body.contains("\"at.zentriert.setlist\"") && body.contains("\"AA:BB\",\"CC:DD\""), body)
        assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/apple-app-site-association").status)
        assertTrue(client.get("/privacy").bodyAsText().contains("speichert"))
        File("/dev/null") // keep java.io.File import honest
    }
}
