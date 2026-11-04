package at.zentriert.setlist

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

/** Fields every synced record carries: who changed it last, how often, and whether it is gone. */
interface Synced {
    val id: String
    val bandId: String?
    val rev: Long
    val updatedAt: Long
    val updatedBy: String
    val deleted: Boolean
}

@Serializable
data class Song(
    override val id: String = newId(),
    val title: String,
    val notes: String = "",
    /** Tempo, meter and notes live in a [Score] shared by all copies of this song. */
    val scoreId: String? = null,
    /** The band this library song belongs to; null = private to this phone. */
    override val bandId: String? = null,
    override val rev: Long = 0,
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
    override val deleted: Boolean = false,
) : Synced

@Serializable
data class Setlist(
    override val id: String = newId(),
    val name: String,
    val songs: List<Song> = emptyList(),
    override val updatedAt: Long = currentTimeMillis(),
    override val bandId: String? = null,
    override val rev: Long = 0,
    override val updatedBy: String = "",
    override val deleted: Boolean = false,
) : Synced {
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
