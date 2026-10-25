package at.zentriert.setlist

import org.junit.Assert.assertEquals
import org.junit.Test

class SetlistParserTest {
    private fun songs(text: String): List<Song> {
        val lines = SetlistParser.lines(text)
        return SetlistParser.split(lines, SetlistParser.suggestCuts(lines))
    }

    @Test
    fun blankLinesSeparateSongs() {
        val result = songs("Song A\nleise anfangen\nCapo 2\n\n\nSong B\nmit Gitarre\n\nSong C")
        assertEquals(listOf("Song A", "Song B", "Song C"), result.map { it.title })
        assertEquals("leise anfangen\nCapo 2", result[0].notes)
        assertEquals("", result[2].notes)
    }

    @Test
    fun numberedTitlesWinAndInnerNumberingStaysInNotes() {
        val text = """
            Sommerfest
            1. Song A
            1. Strophe nur Gesang
            2) Song B
            Ende ritardando
            3: Song C
        """.trimIndent()
        val result = songs(text)
        assertEquals(listOf("Song A", "Song B", "Song C"), result.map { it.title })
        assertEquals("1. Strophe nur Gesang", result[0].notes)
    }

    @Test
    fun withoutStructureEveryLineIsASong() {
        assertEquals(listOf("A", "B", "C"), songs("A\nB\nC").map { it.title })
    }

    @Test
    fun keepChecklistMarkersAreRemoved() {
        val result = songs("☐ Song A\n☐ Song B\n☑ Song C")
        assertEquals(listOf("Song A", "Song B", "Song C"), result.map { it.title })
    }

    @Test
    fun linesBeforeFirstCutAreIgnored() {
        val lines = SetlistParser.lines("Header\nSong A\nnote\nSong B")
        val result = SetlistParser.split(lines, setOf(1, 3))
        assertEquals(listOf("Song A", "Song B"), result.map { it.title })
        assertEquals("note", result[0].notes)
    }

    @Test
    fun cleanTitleStripsBulletsAndNumbers() {
        assertEquals("Hallelujah", SetlistParser.cleanTitle("  - 12. Hallelujah "))
        assertEquals("Hallelujah", SetlistParser.cleanTitle("[x] #3) Hallelujah"))
        assertEquals("99 Luftballons", SetlistParser.cleanTitle("99 Luftballons"))
    }
}
