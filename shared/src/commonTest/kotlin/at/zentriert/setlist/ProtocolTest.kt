package at.zentriert.setlist

import at.zentriert.setlist.band.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolTest {
    @Test
    fun messagesRoundTripAsSingleLines() {
        val msgs = listOf(
            Msg.Hello("Leo", Instrument.BASS),
            Msg.Start(123456789L, "s1", 1920, 120.0, 4, 4, 1, 1, true),
            Msg.SetlistSync(Setlist(name = "Gig", songs = listOf(Song(title = "A", scoreId = "s1"))), mapOf("s1" to Score())),
            Msg.SyncTest(true),
        )
        for (m in msgs) {
            val line = Msg.encode(m)
            assertEquals(-1, line.indexOf('\n'))
            assertEquals(m, Msg.decode(line))
        }
        assertNull(Msg.decode("{\"t\":\"nope\"}"))
    }
}
