package at.zentriert.setlist.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class RelayTest {
    @Test
    fun hostMessagesReachMembersAndPingIsAnswered() = testApplication {
        application { bandRelay(RoomStore(null)) }
        val client = createClient { install(WebSockets) }
        val received = mutableListOf<String>()
        client.webSocket("/ws") {
            send(Frame.Text("""{"t":"Join","room":"abc","name":"Host","host":true}"""))
            val joined = (incoming.receive() as Frame.Text).readText()
            assertTrue(joined.contains("\"isHost\":true"), joined)
            val hostSession = this
            val memberJob = launch {
                client.webSocket("/ws") {
                    send(Frame.Text("""{"t":"Join","room":"ABC","name":"Leo","host":false}"""))
                    send(Frame.Text("""{"t":"Ping","t1":42}"""))
                    withTimeout(5000) {
                        while (received.none { it.contains("Navigate") } || received.none { it.contains("Pong") }) {
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
            memberJob.join()
        }
        assertTrue(received.any { it.contains("\"t\":\"Pong\"") && it.contains("\"t1\":42") }, received.toString())
        assertTrue(received.any { it.contains("\"index\":2") }, received.toString())
    }
}
