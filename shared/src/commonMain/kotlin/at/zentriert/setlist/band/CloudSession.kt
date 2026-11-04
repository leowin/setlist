package at.zentriert.setlist.band

import at.zentriert.setlist.Instrument
import at.zentriert.setlist.currentTimeMillis
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
 * Band over the internet via the server (server/): one WebSocket, JSON messages, the server's clock as
 * the shared reference (host and members all sync to it). Membership is proven with [joinAuth], a
 * signature derived from the band's secret. Dropped connections (Cloud Run closes sockets after an
 * hour) are reopened until [close] is called.
 */
class CloudSession(
    private val url: String,
    private val room: String,
    private val myName: String,
    private val asHost: Boolean,
    private val instrument: Instrument?,
    private val joinAuth: (nowSeconds: Long) -> String,
    private val onEvent: (BandEvent) -> Unit,
) : BandLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClient(CIO) { install(WebSockets) }
    private var session: WebSocketSession? = null
    private var closed = false
    override var role: Role? = null; private set
    override val clock = ClockSync()

    fun connect() {
        scope.launch {
            var attempt = 0
            while (!closed && isActive) {
                val refused = runOnce(attempt)
                if (closed || refused) break
                attempt++
                onEvent(BandEvent.Status("Verbindung verloren – neuer Versuch …"))
                delay(minOf(30_000L, 2_000L * attempt))
            }
            if (!closed) onEvent(BandEvent.Ended("Verbindung zum Server beendet"))
        }
    }

    /** One connection; returns true when the server refused us (no point in retrying). */
    private suspend fun runOnce(attempt: Int): Boolean {
        var refused = false
        try {
            if (attempt == 0) onEvent(BandEvent.Status("Verbinde mit Server …"))
            client.webSocket(url) {
                session = this
                val ts = currentTimeMillis() / 1000
                send(Frame.Text(Msg.encode(Msg.Join(room, myName, asHost, instrument, ts, joinAuth(ts)))))
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
                                if (msg.isHost) "Raum verbunden: du bist Host"
                                else if (msg.hostPresent) "Raum verbunden" else "Raum verbunden: warte auf den Host",
                            ))
                        }
                        is Msg.Pong -> {
                            clock.add(ClockSync.Sample(msg.t1, msg.t2, msg.t3, monotonicNanos()))
                            onEvent(BandEvent.Clock(clock.offsetNanos / 1e6, clock.rttNanos / 1e6, clock.errorNanos / 1e6))
                        }
                        is Msg.Peers -> onEvent(BandEvent.Peers(msg.names))
                        is Msg.Bye -> { refused = true; closed = true; onEvent(BandEvent.Ended(msg.reason.ifBlank { "Vom Server getrennt" })); break }
                        null -> {}
                        else -> onEvent(BandEvent.Message(msg))
                    }
                }
                pinger.cancel()
            }
        } catch (e: Exception) {
            if (!closed && attempt == 0) onEvent(BandEvent.Status("Server nicht erreichbar: ${e.message}"))
        }
        session = null
        return refused
    }

    private fun sendRaw(msg: Msg) {
        val s = session ?: return
        scope.launch { runCatching { s.send(Frame.Text(Msg.encode(msg))) } }
    }

    override fun broadcast(msg: Msg) = sendRaw(msg)   // the relay fans out host messages
    override fun send(msg: Msg) = sendRaw(msg)        // members' messages reach the host

    override fun close() {
        closed = true
        scope.launch {
            runCatching { session?.send(Frame.Text(Msg.encode(Msg.Bye()))) }
            delay(100)
            runCatching { session?.close() }
            client.close()
            scope.cancel()
        }
    }
}
