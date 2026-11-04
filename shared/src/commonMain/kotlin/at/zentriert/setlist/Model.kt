package at.zentriert.setlist

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

@Serializable
data class Song(
    val id: String = newId(),
    val title: String,
    val notes: String = "",
)

@Serializable
data class Setlist(
    val id: String = newId(),
    val name: String,
    val songs: List<Song> = emptyList(),
    val updatedAt: Long = currentTimeMillis(),
) {
    /** Plain-text form, e.g. for sharing back into Google Notizen. */
    fun toText(): String = buildString {
        appendLine(name)
        songs.forEachIndexed { i, song ->
            appendLine()
            appendLine("${i + 1}. ${song.title}")
            if (song.notes.isNotBlank()) appendLine(song.notes)
        }
    }.trimEnd()
}
