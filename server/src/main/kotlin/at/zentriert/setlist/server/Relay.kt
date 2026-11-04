package at.zentriert.setlist.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * One room per code. The first member who joins as host leads; the host's messages go to every
 * member, members' messages go to the host. The server is the clock reference: Ping/Pong carries
 * server timestamps so every phone (host included) syncs to the same clock.
 */
class RoomStore(private val dir: File?) {
    class Member(val session: WebSocketSession, val name: String, val isHost: Boolean)
    class Room(val code: String) {
        val members = mutableListOf<Member>()
        var lastSetlist: String? = null
        val host get() = members.firstOrNull { it.isHost }
    }
    private val rooms = HashMap<String, Room>()
    val lock = Mutex()

    init { dir?.mkdirs() }

    fun room(code: String): Room = rooms.getOrPut(code) {
        Room(code).also { r -> dir?.let { File(it, "$code.json").takeIf(File::exists)?.readText()?.let { t -> r.lastSetlist = t } } }
    }

    fun remember(room: Room, setlistJson: String) {
        room.lastSetlist = setlistJson
        dir?.let { runCatching { File(it, "${room.code}.json").writeText(setlistJson) } }
    }
}

private val json = Json { ignoreUnknownKeys = true }

private fun nanos() = System.nanoTime()

fun Application.bandRelay(store: RoomStore) {
    install(WebSockets) { pingPeriodMillis = 20_000; timeoutMillis = 60_000 }
    routing {
        get("/") { call.respondText("Setlist band relay – connect a client to /ws") }
        get("/health") { call.respondText("ok") }
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
                            val code = obj["room"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
                            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "?"
                            val wantsHost = obj["host"]?.jsonPrimitive?.booleanOrNull ?: false
                            val (joinedRoom, member, isHost) = store.lock.withLock {
                                val r = store.room(code)
                                val isHost = wantsHost && r.host == null
                                val m = RoomStore.Member(this, name, isHost)
                                r.members += m
                                Triple(r, m, isHost)
                            }
                            room = joinedRoom; me = member
                            send(Frame.Text("""{"t":"Joined","room":"$code","isHost":$isHost,"hostPresent":${joinedRoom.host != null}}"""))
                            if (!isHost) joinedRoom.lastSetlist?.let { send(Frame.Text(it)) }
                            broadcastPeers(store, joinedRoom)
                        }
                        else -> {
                            val r = room ?: continue
                            val m = me ?: continue
                            if (obj["t"]?.jsonPrimitive?.contentOrNull == "SetlistSync" && m.isHost) store.remember(r, text)
                            val targets = store.lock.withLock {
                                if (m.isHost) r.members.filter { it !== m } else listOfNotNull(r.host)
                            }
                            for (t in targets) runCatching { t.session.send(Frame.Text(text)) }
                        }
                    }
                }
            } finally {
                val r = room; val m = me
                if (r != null && m != null) {
                    store.lock.withLock { r.members.remove(m) }
                    broadcastPeers(store, r)
                }
            }
        }
    }
}

private suspend fun broadcastPeers(store: RoomStore, room: RoomStore.Room) {
    val (names, targets) = store.lock.withLock {
        val host = room.host
        val names = listOfNotNull(host?.name) + room.members.filter { !it.isHost }.map { it.name }
        names to room.members.toList()
    }
    val msg = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
        "t" to JsonPrimitive("Peers"),
        "names" to kotlinx.serialization.json.JsonArray(names.map { JsonPrimitive(it) }),
    )))
    for (t in targets) runCatching { t.session.send(Frame.Text(msg)) }
}
