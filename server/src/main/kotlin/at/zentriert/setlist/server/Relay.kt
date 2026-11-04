package at.zentriert.setlist.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Live WebSocket rooms: the host's messages go to every member, members' messages go to the host. */
class RoomStore {
    class Member(val session: WebSocketSession, val name: String, val isHost: Boolean)
    class Room(val code: String) {
        val members = mutableListOf<Member>()
        val host get() = members.firstOrNull { it.isHost }
    }
    private val rooms = HashMap<String, Room>()
    val lock = Mutex()

    fun room(code: String): Room = rooms.getOrPut(code) { Room(code) }
    fun forget(room: Room) { if (room.members.isEmpty()) rooms.remove(room.code) }
}

/** What a deployment needs to know; all optional. */
data class ServerConfig(
    val roomTtlDays: Int = 90,
    val androidPackage: String = "",
    val androidSha256: String = "",
    val iosAppId: String = "",
    /** false in tests: no background sweeper. */
    val sweep: Boolean = true,
)

private val json = Json { ignoreUnknownKeys = true }
private const val MAX_BLOB = 4 * 1024 * 1024
private const val MAX_SHARE = 2 * 1024 * 1024
private val SHARE_TTL_DAYS = setOf(7, 30, 90)

private fun nanos() = System.nanoTime()

fun Application.bandRelay(rooms: RoomStore, store: FileStore, config: ServerConfig = ServerConfig()) {
    install(WebSockets) { pingPeriodMillis = 20_000; timeoutMillis = 60_000 }

    if (config.sweep) launch {
        while (isActive) {
            runCatching { store.sweep(config.roomTtlDays * 24L * 3600_000L) }
            delay(3600_000L)
        }
    }

    suspend fun RoutingCall.authorized(roomId: String, body: ByteArray): Boolean {
        val ok = Auth.verify(store.authKey(roomId), request.header(Auth.HEADER), request.httpMethod.value, request.path(), body)
        if (!ok) respond(HttpStatusCode.Unauthorized)
        return ok
    }

    routing {
        get("/") { call.respondText("Setlist band server – app clients connect to /ws, /rooms and /shares") }
        get("/health") { call.respondText("ok") }
        get("/privacy") { call.respondText(resource("web/privacy.html"), ContentType.Text.Html) }

        // --- app links ---
        get("/.well-known/assetlinks.json") {
            if (config.androidPackage.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(
                """[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"${config.androidPackage}","sha256_cert_fingerprints":[${config.androidSha256.split(',').filter { it.isNotBlank() }.joinToString(",") { "\"${it.trim()}\"" }}]}}]""",
                ContentType.Application.Json,
            )
        }
        get("/.well-known/apple-app-site-association") {
            if (config.iosAppId.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(
                """{"applinks":{"apps":[],"details":[{"appID":"${config.iosAppId}","paths":["/join","/s/*"]}]}}""",
                ContentType.Application.Json,
            )
        }
        get("/join") { call.respondText(resource("web/join.html"), ContentType.Text.Html) }

        // --- rooms: encrypted blobs + manifest per band ---
        put("/rooms/{id}") {
            val id = call.roomId() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val key = call.receive<ByteArray>()
            if (key.size != 32) return@put call.respond(HttpStatusCode.BadRequest)
            // The creator signs with the key it registers.
            if (!Auth.verify(key, call.request.header(Auth.HEADER), "PUT", call.request.path(), key)) return@put call.respond(HttpStatusCode.Unauthorized)
            val created = store.createRoom(id, key)
            if (created) call.respond(HttpStatusCode.Created)
            else if (store.authKey(id)?.contentEquals(key) == true) call.respond(HttpStatusCode.OK)
            else call.respond(HttpStatusCode.Conflict)
        }
        delete("/rooms/{id}") {
            val id = call.roomId() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (!call.authorized(id, ByteArray(0))) return@delete
            store.deleteRoom(id)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/rooms/{id}/manifest") {
            val id = call.roomId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (!call.authorized(id, ByteArray(0))) return@get
            store.touch(id)
            val m = store.manifest(id)
            call.response.header("ETag", "\"${m?.version ?: 0}\"")
            if (m == null) call.respond(HttpStatusCode.NoContent) else call.respondBytes(m.blob, ContentType.Application.OctetStream)
        }
        put("/rooms/{id}/manifest") {
            val id = call.roomId() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<ByteArray>()
            if (body.size > MAX_BLOB) return@put call.respond(HttpStatusCode.PayloadTooLarge)
            if (!call.authorized(id, body)) return@put
            val expected = call.request.header("If-Match")?.trim('"', ' ')?.toLongOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val version = store.putManifest(id, expected, body) ?: return@put call.respond(HttpStatusCode.PreconditionFailed)
            call.response.header("ETag", "\"$version\"")
            call.respond(HttpStatusCode.OK)
            notifyChanged(rooms, id, version)
        }
        get("/rooms/{id}/blobs/{sha}") {
            val id = call.roomId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (!call.authorized(id, ByteArray(0))) return@get
            val bytes = store.blob(id, call.parameters["sha"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
        put("/rooms/{id}/blobs/{sha}") {
            val id = call.roomId() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<ByteArray>()
            if (body.size > MAX_BLOB) return@put call.respond(HttpStatusCode.PayloadTooLarge)
            if (!call.authorized(id, body)) return@put
            if (!store.putBlob(id, call.parameters["sha"].orEmpty(), body)) return@put call.respond(HttpStatusCode.BadRequest)
            store.touch(id)
            call.respond(HttpStatusCode.Created)
        }

        // --- share links: encrypted payload, key only in the link's fragment ---
        post("/shares") {
            val ttl = call.request.queryParameters["ttl"]?.toIntOrNull() ?: 30
            if (ttl !in SHARE_TTL_DAYS) return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<ByteArray>()
            if (body.isEmpty() || body.size > MAX_SHARE) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            val id = store.createShare(body, ttl * 24L * 3600_000L)
            call.respondText("""{"id":"$id"}""", ContentType.Application.Json, HttpStatusCode.Created)
        }
        get("/shares/{id}/blob") {
            val bytes = store.share(call.parameters["id"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
        get("/s/{id}") { call.respondText(resource("web/share.html"), ContentType.Text.Html) }

        // --- live room: clock, start/stop, navigation, change notifications ---
        webSocket("/ws") {
            var room: RoomStore.Room? = null
            var me: RoomStore.Member? = null
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                    when (obj["t"]?.jsonPrimitive?.contentOrNull) {
                        "Ping" -> {
                            // Answer right away; t2/t3 are server monotonic nanos.
                            val t1 = obj["t1"]?.jsonPrimitive?.longOrNull ?: continue
                            val t2 = nanos()
                            send(Frame.Text("""{"t":"Pong","t1":$t1,"t2":$t2,"t3":${nanos()}}"""))
                        }
                        "Join" -> {
                            val code = obj["room"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "?"
                            val wantsHost = obj["host"]?.jsonPrimitive?.booleanOrNull ?: false
                            val ts = obj["ts"]?.jsonPrimitive?.longOrNull ?: 0
                            val auth = obj["auth"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (!Auth.verifyJoin(store.authKey(code), code, ts, auth)) {
                                send(Frame.Text("""{"t":"Bye","reason":"Nicht in dieser Band – Einladung nötig"}"""))
                                break
                            }
                            val (joinedRoom, member, isHost) = rooms.lock.withLock {
                                val r = rooms.room(code)
                                val isHost = wantsHost && r.host == null
                                val m = RoomStore.Member(this, name, isHost)
                                r.members += m
                                Triple(r, m, isHost)
                            }
                            room = joinedRoom; me = member
                            store.touch(code)
                            send(Frame.Text("""{"t":"Joined","room":"$code","isHost":$isHost,"hostPresent":${joinedRoom.host != null}}"""))
                            broadcastPeers(rooms, joinedRoom)
                        }
                        "Changed" -> {
                            val r = room ?: continue
                            val m = me ?: continue
                            val targets = rooms.lock.withLock { r.members.filter { it !== m } }
                            for (t in targets) runCatching { t.session.send(Frame.Text(text)) }
                        }
                        else -> {
                            val r = room ?: continue
                            val m = me ?: continue
                            val targets = rooms.lock.withLock {
                                if (m.isHost) r.members.filter { it !== m } else listOfNotNull(r.host)
                            }
                            for (t in targets) runCatching { t.session.send(Frame.Text(text)) }
                        }
                    }
                }
            } finally {
                val r = room; val m = me
                if (r != null && m != null) {
                    rooms.lock.withLock { r.members.remove(m); rooms.forget(r) }
                    broadcastPeers(rooms, r)
                }
            }
        }
    }
}

private fun RoutingCall.roomId(): String? = parameters["id"]?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

private fun resource(name: String): String =
    FileStore::class.java.classLoader.getResource(name)?.readText() ?: "<!doctype html><title>Setlist</title><p>Seite fehlt."

/** Tells everyone connected to the room that the manifest moved to [version]. */
private suspend fun notifyChanged(rooms: RoomStore, roomId: String, version: Long) {
    val targets = rooms.lock.withLock { rooms.room(roomId).also { rooms.forget(it) }.members.toList() }
    val msg = """{"t":"Changed","version":$version}"""
    for (t in targets) runCatching { t.session.send(Frame.Text(msg)) }
}

private suspend fun broadcastPeers(rooms: RoomStore, room: RoomStore.Room) {
    val (names, targets) = rooms.lock.withLock {
        val host = room.host
        val names = listOfNotNull(host?.name) + room.members.filter { !it.isHost }.map { it.name }
        names to room.members.toList()
    }
    val msg = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
        "t" to JsonPrimitive("Peers"),
        "names" to JsonArray(names.map { JsonPrimitive(it) }),
    )))
    for (t in targets) runCatching { t.session.send(Frame.Text(msg)) }
}
