package at.zentriert.setlist

import at.zentriert.setlist.sync.Crypto
import at.zentriert.setlist.sync.SharePayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seals a share payload the way the app does and writes it to build/share-fixture.json, so the web
 * preview (server/src/main/resources/web/share.html) can be checked against real app output in a browser.
 */
class ShareFixtureTest {
    @Test
    fun sealedPayloadOpensAgainAndIsWrittenForTheWebCheck() {
        val score = Score().withTempo(132.0).withMeter(6, 8).copy(parts = listOf(Part(Instrument.BASS)))
        val payload = SharePayload(
            setlistName = "Sommerfest",
            songs = listOf(Song(title = "Hey Jude", notes = "Intro 2x\nRefrain laut", scoreId = score.id), Song(title = "Yesterday")),
            scores = mapOf(score.id to score),
            from = "Leo",
        )
        val sealed = payload.seal()
        assertEquals(payload, SharePayload.open(sealed.bytes, sealed.keyBase64))
        val out = File(System.getProperty("fixtureDir") ?: "build", "share-fixture.json")
        out.parentFile.mkdirs()
        out.writeText("""{"key":"${sealed.keyBase64}","hex":"${Crypto.hex(sealed.bytes)}"}""")
    }
}
