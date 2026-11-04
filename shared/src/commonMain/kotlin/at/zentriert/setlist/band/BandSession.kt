package at.zentriert.setlist.band

import at.zentriert.setlist.monotonicNanos
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray

enum class Role { HOST, MEMBER }

/** What the session reports back to the view model (always on the session's own scope). */
sealed class BandEvent {
    data class Status(val text: String) : BandEvent()
    data class Peers(val names: List<String>) : BandEvent()
    data class Message(val msg: Msg) : BandEvent()
    data class Clock(val offsetMs: Double, val rttMs: Double, val errorMs: Double) : BandEvent()
    data class Ended(val reason: String) : BandEvent()
}

/**
 * One band session: the host accepts members over TCP (JSON lines) and answers clock-sync
 * datagrams over UDP; members keep a [ClockSync] estimate of the host's clock.
 */
class BandSession(private val myName: String, private val onEvent: (BandEvent) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val selector = SelectorManager(Dispatchers.Default)
    var role: Role? = null; private set
    val clock = ClockSync()

    // host side
    private var server: ServerSocket? = null
    private val members = mutableMapOf<String, Pair<ByteWriteChannel, String>>()  // peerId -> (writer, name)
    private val membersLock = Mutex()
    private var lastSetlist: Msg.SetlistSync? = null

    // member side
    private var socket: Socket? = null
    private var writer: ByteWriteChannel? = null
    private var udp: BoundDatagramSocket? = null
    private var syncJob: Job? = null

    fun host(port: Int = DEFAULT_PORT) {
        role = Role.HOST
        scope.launch {
            try {
                val tcp = aSocket(selector).tcp().bind("0.0.0.0", port)
                server = tcp
                val udpSocket = aSocket(selector).udp().bind(InetSocketAddress("0.0.0.0", port))
                udp = udpSocket
                launch { answerClockRequests(udpSocket) }
                onEvent(BandEvent.Status("Band gehostet – warte auf Mitglieder"))
                while (isActive) {
                    val client = tcp.accept()
                    launch { serveMember(client, port) }
                }
            } catch (e: Exception) {
                onEvent(BandEvent.Ended("Hosten fehlgeschlagen: ${e.message}"))
            }
        }
    }

    private suspend fun serveMember(client: Socket, udpPort: Int) {
        val peerId = client.remoteAddress.toString()
        val input = client.openReadChannel()
        val output = client.openWriteChannel(autoFlush = true)
        var name = peerId
        try {
            while (scope.isActive) {
                val line = input.readUTF8Line() ?: break
                when (val msg = Msg.decode(line)) {
                    is Msg.Hello -> {
                        name = msg.name
                        membersLock.withLock { members[peerId] = output to name }
                        output.writeStringUtf8(Msg.encode(Msg.Welcome(myName, peerId, udpPort)) + "\n")
                        lastSetlist?.let { output.writeStringUtf8(Msg.encode(it) + "\n") }
                        broadcastPeers()
                    }
                    is Msg.Bye, null -> if (msg is Msg.Bye) break
                    else -> onEvent(BandEvent.Message(msg))
                }
            }
        } catch (_: Exception) {
        } finally {
            membersLock.withLock { members.remove(peerId) }
            runCatching { client.close() }
            broadcastPeers()
        }
    }

    private suspend fun broadcastPeers() {
        val names = membersLock.withLock { members.values.map { it.second } }
        onEvent(BandEvent.Peers(names))
        broadcast(Msg.Peers(listOf(myName) + names))
    }

    private suspend fun answerClockRequests(socket: BoundDatagramSocket) {
        while (scope.isActive) {
            val datagram = socket.receive()
            val t2 = monotonicNanos()
            val (t1, _, _) = ClockSync.decode(datagram.packet.readByteArray()) ?: continue
            val reply = ClockSync.encode(t1, t2, monotonicNanos())
            socket.send(Datagram(buildPacket { writeFully(reply) }, datagram.address))
        }
    }

    /** Host → all members. Remembers the setlist so late joiners get it too. */
    fun broadcast(msg: Msg) {
        if (msg is Msg.SetlistSync) lastSetlist = msg
        scope.launch {
            val line = Msg.encode(msg) + "\n"
            val targets = membersLock.withLock { members.values.map { it.first } }
            for (w in targets) runCatching { w.writeStringUtf8(line) }
        }
    }

    fun join(host: String, port: Int = DEFAULT_PORT, instrument: at.zentriert.setlist.Instrument?) {
        role = Role.MEMBER
        scope.launch {
            try {
                onEvent(BandEvent.Status("Verbinde mit $host …"))
                val s = aSocket(selector).tcp().connect(host, port)
                socket = s
                val input = s.openReadChannel()
                val output = s.openWriteChannel(autoFlush = true)
                writer = output
                output.writeStringUtf8(Msg.encode(Msg.Hello(myName, instrument)) + "\n")
                while (isActive) {
                    val line = input.readUTF8Line() ?: break
                    when (val msg = Msg.decode(line)) {
                        is Msg.Welcome -> {
                            onEvent(BandEvent.Status("Verbunden mit ${msg.hostName}"))
                            syncJob = launch { runClockSync(host, msg.udpPort) }
                        }
                        is Msg.Peers -> onEvent(BandEvent.Peers(msg.names))
                        is Msg.Bye -> break
                        null -> {}
                        else -> onEvent(BandEvent.Message(msg))
                    }
                }
                onEvent(BandEvent.Ended("Verbindung beendet"))
            } catch (e: Exception) {
                onEvent(BandEvent.Ended("Verbindung fehlgeschlagen: ${e.message}"))
            }
        }
    }

    /** Sends a sync request every 250 ms; the reply completes one round trip for [clock]. */
    private suspend fun runClockSync(host: String, port: Int) {
        val socket = aSocket(selector).udp().bind()
        udp = socket
        val target: SocketAddress = InetSocketAddress(host, port)
        scope.launch {
            while (isActive) {
                val datagram = socket.receive()
                val t4 = monotonicNanos()
                val (t1, t2, t3) = ClockSync.decode(datagram.packet.readByteArray()) ?: continue
                clock.add(ClockSync.Sample(t1, t2, t3, t4))
                onEvent(BandEvent.Clock(clock.offsetNanos / 1e6, clock.rttNanos / 1e6, clock.errorNanos / 1e6))
            }
        }
        while (scope.isActive) {
            runCatching { socket.send(Datagram(buildPacket { writeFully(ClockSync.encode(monotonicNanos())) }, target)) }
            delay(250)
        }
    }

    /** Member → host. */
    fun send(msg: Msg) {
        val w = writer ?: return
        scope.launch { runCatching { w.writeStringUtf8(Msg.encode(msg) + "\n") } }
    }

    fun close() {
        scope.launch {
            runCatching { writer?.writeStringUtf8(Msg.encode(Msg.Bye()) + "\n") }
            runCatching { broadcast(Msg.Bye("Host hat die Band beendet")) }
            delay(100)
            runCatching { socket?.close() }
            runCatching { server?.close() }
            runCatching { udp?.close() }
            runCatching { selector.close() }
            scope.cancel()
        }
    }
}
