package at.zentriert.setlist.band

import at.zentriert.setlist.Instrument
import at.zentriert.setlist.monotonicNanos
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Band over the internet via the relay server (server/): one WebSocket, JSON messages, the server's
 * clock as the shared reference (host and members all sync to it).
 */
class CloudSession(
    private val url: String,
    private val room: String,
    private val myName: String,
    private val asHost: Boolean,
    private val instrument: Instrument?,
    private val onEvent: (BandEvent) -> Unit,
) : BandLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient(CIO) { install(WebSockets) }
    private var session: WebSocketSession? = null
    override var role: Role? = null; private set
    override val clock = ClockSync()

    fun connect() {
        scope.launch {
            try {
                onEvent(BandEvent.Status("Verbinde mit Server …"))
                client.webSocket(url) {
                    session = this
                    send(Frame.Text(Msg.encode(Msg.Join(room.trim().uppercase(), myName, asHost, instrument))))
                    val pinger = launch {
                        while (isActive) {
                            runCatching { send(Frame.Text(Msg.encode(Msg.Ping(monotonicNanos())))) }
                            delay(250)
                        }
                    }
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        when (val msg = Msg.decode(frame.readText())) {
                            is Msg.Joined -> {
                                role = if (msg.isHost) Role.HOST else Role.MEMBER
                                onEvent(BandEvent.Status(
                                    if (msg.isHost) "Raum ${msg.room}: du bist Host"
                                    else if (msg.hostPresent) "Raum ${msg.room}: verbunden" else "Raum ${msg.room}: warte auf den Host",
                                ))
                            }
                            is Msg.Pong -> {
                                clock.add(ClockSync.Sample(msg.t1, msg.t2, msg.t3, monotonicNanos()))
                                onEvent(BandEvent.Clock(clock.offsetNanos / 1e6, clock.rttNanos / 1e6, clock.errorNanos / 1e6))
                            }
                            is Msg.Peers -> onEvent(BandEvent.Peers(msg.names))
                            is Msg.Bye -> break
                            null -> {}
                            else -> onEvent(BandEvent.Message(msg))
                        }
                    }
                    pinger.cancel()
                }
                onEvent(BandEvent.Ended("Verbindung zum Server beendet"))
            } catch (e: Exception) {
                onEvent(BandEvent.Ended("Server nicht erreichbar: ${e.message}"))
            }
        }
    }

    private fun sendRaw(msg: Msg) {
        val s = session ?: return
        scope.launch { runCatching { s.send(Frame.Text(Msg.encode(msg))) } }
    }

    override fun broadcast(msg: Msg) = sendRaw(msg)   // the relay fans out host messages
    override fun send(msg: Msg) = sendRaw(msg)        // members' messages reach the host

    override fun close() {
        scope.launch {
            runCatching { session?.send(Frame.Text(Msg.encode(Msg.Bye()))) }
            delay(100)
            runCatching { session?.close() }
            client.close()
            scope.cancel()
        }
    }
}
