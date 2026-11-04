package at.zentriert.setlist

import at.zentriert.setlist.band.Band
import at.zentriert.setlist.server.FileStore
import at.zentriert.setlist.server.RoomStore
import at.zentriert.setlist.server.ServerConfig
import at.zentriert.setlist.server.bandRelay
import at.zentriert.setlist.sync.SyncEngine
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two phones, one band, the real server: changes travel, deletions travel, conflicts keep both versions. */
class SyncE2ETest {
    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        bandRelay(RoomStore(), FileStore(Files.createTempDirectory("sync-server").toFile()), ServerConfig(sweep = false))
    }
    private lateinit var url: String
    private val band = Band.create("Die Nachtigallen", "")

    private class Phone(val name: String, band: Band, url: String) {
        val repo = SetlistRepository(Path(Files.createTempDirectory("phone-$name").toString())).also { it.author = name }
        val engine = SyncEngine(repo, band, url, Path(Files.createTempDirectory("sync-$name").toString()), myName = { name })
        fun sync() = runBlocking { engine.sync() }
        fun songs() = repo.data.value.library(engine.band.id).sortedBy { it.title }
        fun setlists() = repo.data.value.setlists(engine.band.id)
    }

    @BeforeTest fun start() {
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        url = "http://127.0.0.1:$port"
    }

    @AfterTest fun stop() { server.stop(100, 500) }

    @Test
    fun changesAndDeletionsReachTheOtherPhone() {
        val a = Phone("Anna", band, url)
        val b = Phone("Ben", band, url)
        a.repo.addBand(band); b.repo.addBand(band)
        val score = Score().withTempo(97.0)
        a.repo.saveScore(score)
        a.repo.remember(listOf(Song(title = "Hey Jude", notes = "intro", scoreId = score.id)), band.id)
        a.repo.upsert(Setlist(name = "Gig", songs = listOf(Song(title = "Hey Jude", scoreId = score.id)), bandId = band.id))
        assertNull(a.sync())
        assertNull(b.sync())
        assertEquals(listOf("Hey Jude"), b.songs().map { it.title })
        assertEquals("intro", b.songs().single().notes)
        assertEquals(97.0, b.repo.score(score.id)?.bpm)
        assertEquals("Gig", b.setlists().single().name)
        assertEquals(listOf("Anna"), b.engine.status.members)

        // Ben edits, Anna gets it; nothing is uploaded twice.
        b.repo.saveLibrarySong(b.songs().single().copy(notes = "intro 2x"))
        b.repo.saveScore(b.repo.score(score.id)!!.withTempo(100.0))
        assertNull(b.sync()); assertNull(a.sync())
        assertEquals("intro 2x", a.songs().single().notes)
        assertEquals(100.0, a.repo.score(score.id)?.bpm)
        assertEquals("Ben", a.songs().single().updatedBy)

        // Anna deletes the setlist; it disappears at Ben's.
        a.repo.delete(a.setlists().single().id)
        assertNull(a.sync()); assertNull(b.sync())
        assertTrue(b.setlists().isEmpty())
        assertTrue(b.repo.data.value.setlists.single().deleted)
    }

    @Test
    fun editingTheSameSongOnBothPhonesKeepsBothVersions() {
        val a = Phone("Anna", band, url)
        val b = Phone("Ben", band, url)
        a.repo.addBand(band); b.repo.addBand(band)
        a.repo.remember(listOf(Song(title = "Yesterday", notes = "v1")), band.id)
        assertNull(a.sync()); assertNull(b.sync())
        a.repo.saveLibrarySong(a.songs().single().copy(notes = "Annas Fassung"))
        b.repo.saveLibrarySong(b.songs().single().copy(notes = "Bens Fassung"))
        assertNull(a.sync())          // Anna's version is on the server first
        assertNull(b.sync())          // Ben sees the conflict: keeps his as a copy, takes Anna's
        assertEquals(listOf("Yesterday", "Yesterday (Fassung von Ben)"), b.songs().map { it.title })
        assertEquals("Annas Fassung", b.songs()[0].notes)
        assertEquals("Bens Fassung", b.songs()[1].notes)
        assertNull(a.sync())          // the copy arrives at Anna's
        assertEquals(listOf("Yesterday", "Yesterday (Fassung von Ben)"), a.songs().map { it.title })
        // Everything settled: another round changes nothing.
        assertNull(a.sync()); assertNull(b.sync())
        assertEquals(2, a.songs().size); assertEquals(2, b.songs().size)
        assertEquals(setOf("Anna", "Ben"), a.engine.status.members.toSet())
    }

    @Test
    fun aWrongKeyIsRefusedAndTheRoomCanBeDeleted() {
        val a = Phone("Anna", band, url)
        a.repo.remember(listOf(Song(title = "X")), band.id)
        assertNull(a.sync())
        // Same room id claimed with another key → the server keeps the first key and refuses.
        val impostor = Phone("Eve", Band(id = band.id, name = "Eve", key = Band.create("x", "").key), url)
        val error = impostor.sync()
        assertNotNull(error)
        assertTrue(impostor.songs().isEmpty())
        runBlocking { a.engine.deleteRoom() }
        assertNull(a.sync())           // recreated from Anna's phone, nothing lost
        assertEquals(1, a.songs().size)
    }
}
