package at.zentriert.setlist

import at.zentriert.setlist.band.BandEvent
import at.zentriert.setlist.band.BandSession
import at.zentriert.setlist.band.Msg
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Host and member on localhost: hello/welcome, setlist sync, navigation, clock samples. */
class BandSessionTest {
    @Test
    fun hostAndMemberTalkAndSyncClocks() = runBlocking {
        val port = 47300 + (System.nanoTime() % 100).toInt()
        val hostEvents = CopyOnWriteArrayList<BandEvent>()
        val memberEvents = CopyOnWriteArrayList<BandEvent>()
        val host = BandSession("Host") { hostEvents += it }
        val member = BandSession("Leo") { memberEvents += it }
        try {
            host.host(port)
            delay(300)
            host.broadcast(Msg.SetlistSync(Setlist(name = "Gig", songs = listOf(Song(title = "A"))), emptyMap()))
            member.join("127.0.0.1", port, Instrument.BASS)

            withTimeout(5000) {
                while (hostEvents.none { it is BandEvent.Peers && "Leo" in it.names }) delay(50)
                while (memberEvents.none { it is BandEvent.Message && it.msg is Msg.SetlistSync }) delay(50)
            }
            host.broadcast(Msg.Navigate(3))
            withTimeout(3000) { while (memberEvents.none { it is BandEvent.Message && it.msg == Msg.Navigate(3) }) delay(50) }

            // Clock sync: a few round trips within a second, tiny RTT on localhost.
            withTimeout(4000) { while (memberEvents.count { it is BandEvent.Clock } < 4) delay(50) }
            val clock = memberEvents.filterIsInstance<BandEvent.Clock>().last()
            assertTrue("rtt ${clock.rttMs}", clock.rttMs < 50.0)
            assertTrue("offset ${clock.offsetMs}", kotlin.math.abs(clock.offsetMs) < 20.0)  // same machine, same clock
            assertEquals(listOf("Leo"), hostEvents.filterIsInstance<BandEvent.Peers>().last().names)
        } finally {
            member.close(); host.close(); delay(200)
        }
    }
}
