package at.zentriert.setlist

import at.zentriert.setlist.band.Band
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryBandTest {
    private fun newRepo(): SetlistRepository {
        val dir = Path(SystemTemporaryDirectory, "setlist-test-${newId()}")
        SystemFileSystem.createDirectories(dir)
        return SetlistRepository(dir).also { it.author = "phone-1" }
    }

    @Test
    fun librariesAreSeparatePerBand() {
        val repo = newRepo()
        val band = Band.create("A", "")
        repo.addBand(band)
        repo.remember(listOf(Song(title = "Hey Jude", notes = "intro")), bandId = null)
        repo.remember(listOf(Song(title = "Hey Jude", notes = "band version")), bandId = band.id)
        assertEquals("intro", repo.data.value.library(null).single().notes)
        assertEquals("band version", repo.data.value.library(band.id).single().notes)
        assertEquals("band version", repo.findInLibrary("1. Hey Jude", band.id)?.notes)
        assertNull(repo.findInLibrary("Hey Jude", "other"))
    }

    @Test
    fun changesAreStampedAndDeletionsLeaveTombstones() {
        val repo = newRepo()
        val setlist = Setlist(name = "Gig", songs = listOf(Song(title = "A")))
        repo.upsert(setlist)
        val stored = assertNotNull(repo.get(setlist.id))
        assertEquals(1, stored.rev)
        assertEquals("phone-1", stored.updatedBy)
        repo.upsert(stored.copy(name = "Gig 2"))
        assertEquals(2, repo.get(setlist.id)?.rev)
        repo.delete(setlist.id)
        assertNull(repo.get(setlist.id))
        val tomb = repo.data.value.setlists.single()
        assertTrue(tomb.deleted)
        assertEquals(3, tomb.rev)
        assertTrue(repo.data.value.setlists(null).isEmpty())

        repo.remember(listOf(Song(title = "B")), null)
        val song = repo.data.value.library(null).single()
        repo.deleteLibrarySong(song.id)
        assertTrue(repo.data.value.library(null).isEmpty())
        assertTrue(repo.data.value.library.single().deleted)
    }

    @Test
    fun copySongMakesAnIndependentCopyWithItsOwnScore() {
        val repo = newRepo()
        val band = Band.create("A", "")
        repo.addBand(band)
        val score = Score().withTempo(97.0)
        repo.saveScore(score)
        repo.remember(listOf(Song(title = "Song", notes = "n", scoreId = score.id)), null)
        val original = repo.data.value.library(null).single()
        val copy = assertNotNull(repo.copySong(original.id, band.id))
        assertNotEquals(original.id, copy.id)
        assertNotEquals(original.scoreId, copy.scoreId)
        assertEquals(band.id, copy.bandId)
        assertEquals(97.0, repo.score(copy.scoreId)?.bpm)
        // Editing the copy's score leaves the original untouched.
        repo.saveScore(repo.score(copy.scoreId)!!.withTempo(140.0))
        assertEquals(97.0, repo.score(original.scoreId)?.bpm)
        assertEquals(1, repo.data.value.library(null).size)
        assertEquals(1, repo.data.value.library(band.id).size)
    }

    @Test
    fun copySetlistBringsItsSongsAlong() {
        val repo = newRepo()
        val band = Band.create("A", "")
        repo.addBand(band)
        val score = Score()
        repo.saveScore(score)
        repo.upsert(Setlist(name = "Gig", songs = listOf(Song(title = "X", scoreId = score.id), Song(title = "Y"))))
        val copy = assertNotNull(repo.copySetlist(repo.data.value.setlists(null).single().id, band.id))
        assertEquals(band.id, copy.bandId)
        assertEquals(listOf("X", "Y"), copy.songs.map { it.title })
        assertNotEquals(score.id, copy.songs[0].scoreId)
        assertEquals(setOf("X", "Y"), repo.data.value.library(band.id).map { it.title }.toSet())
        assertEquals(1, repo.data.value.setlists(band.id).size)
    }

    @Test
    fun adoptPrivateMovesEverythingIntoTheBandAndLeavingBringsItBack() {
        val repo = newRepo()
        val band = Band.create("A", "")
        repo.addBand(band)
        repo.upsert(Setlist(name = "Gig"))
        repo.remember(listOf(Song(title = "S")), null)
        repo.adoptPrivate(band.id)
        assertTrue(repo.data.value.setlists(null).isEmpty())
        assertEquals(1, repo.data.value.setlists(band.id).size)
        assertEquals(1, repo.data.value.library(band.id).size)
        repo.removeBand(band.id)
        assertTrue(repo.data.value.bands.isEmpty())
        assertEquals(1, repo.data.value.setlists(null).size)
        assertEquals(1, repo.data.value.library(null).size)
    }

    @Test
    fun applyRemoteKeepsRevisionAndAuthorAndSurvivesReload() {
        val repo = newRepo()
        val remote = Song(id = "r1", title = "Remote", bandId = "b", rev = 7, updatedAt = 5, updatedBy = "phone-2")
        repo.applyRemote(songs = listOf(remote), scores = listOf(Score(id = "sc", rev = 3)))
        assertEquals(remote, repo.data.value.library.single())
        assertEquals(3, repo.score("sc")?.rev)
    }
}
