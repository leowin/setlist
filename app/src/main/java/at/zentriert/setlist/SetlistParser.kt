package at.zentriert.setlist

/**
 * Turns the text of a note into songs. A "cut" is the index of a line that starts a new song:
 * that line becomes the title, the following lines (up to the next cut) the instructions.
 * Lines before the first cut (e.g. a heading) are ignored.
 */
object SetlistParser {
    private val numbered = Regex("""^\s*(?:[☐☑☒□■•*-]\s*)?#?(\d{1,3})\s*[.):]\s*\S""")
    private val leadingNumber = Regex("""^#?\d{1,3}\s*[.):]\s*""")
    private val leadingBullet = Regex("""^(?:\[[ xX]?]|[☐☑☒✓✔□■▪●•◦‣*+–-])\s*""")
    private val leadingCheckbox = Regex("""^(\s*)(?:\[[ xX]?]|[☐☑☒□])\s*""")

    fun lines(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trimEnd() }

    /**
     * Guesses where songs start, in this order:
     * 1. lines numbered 1., 2., 3. … (in sequence, so "1. Strophe" inside a song is skipped),
     * 2. blocks separated by blank lines,
     * 3. every non-blank line is a song.
     */
    fun suggestCuts(lines: List<String>): Set<Int> {
        val nonBlank = lines.indices.filter { lines[it].isNotBlank() }
        if (nonBlank.isEmpty()) return emptySet()

        val sequence = mutableListOf<Int>()
        var expected: Int? = null
        for (i in nonBlank) {
            val n = numbered.find(lines[i])?.groupValues?.get(1)?.toInt() ?: continue
            if (expected == null || n == expected) {
                sequence += i
                expected = n + 1
            }
        }
        if (sequence.size >= 2) return sequence.toSet()

        val blockStarts = nonBlank.filter { it == nonBlank.first() || lines[it - 1].isBlank() }
        if (blockStarts.size >= 2) return blockStarts.toSet()

        return nonBlank.toSet()
    }

    fun split(lines: List<String>, cuts: Set<Int>): List<Song> {
        val starts = cuts.filter { it in lines.indices && lines[it].isNotBlank() }.sorted()
        return starts.mapIndexed { k, start ->
            val end = starts.getOrNull(k + 1) ?: lines.size
            Song(
                title = cleanTitle(lines[start]),
                notes = tidy(lines.subList(start + 1, end).map(::cleanNoteLine)),
            )
        }
    }

    fun cleanTitle(line: String): String {
        var s = line.trim()
        while (true) {
            val next = s.replaceFirst(leadingBullet, "").replaceFirst(leadingNumber, "").trim()
            if (next == s) break
            s = next
        }
        return s.ifEmpty { line.trim() }
    }

    private fun cleanNoteLine(line: String): String = line.replaceFirst(leadingCheckbox, "$1")

    /** Drops leading/trailing blank lines and collapses runs of blank lines. */
    private fun tidy(lines: List<String>): String {
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank() && (out.isEmpty() || out.last().isBlank())) continue
            out += line
        }
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)
        return out.joinToString("\n")
    }
}
