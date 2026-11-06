package at.zentriert.setlist.sync

import kotlinx.serialization.Serializable

/** One synced record as the band's encrypted manifest lists it. */
@Serializable
data class ManifestEntry(
    val type: String,
    val id: String,
    val rev: Long,
    val updatedAt: Long,
    val updatedBy: String = "",
    /** SHA-256 (hex) of the encrypted blob holding this revision. */
    val blobId: String,
    val deleted: Boolean = false,
) {
    val key get() = "$type/$id"

    companion object {
        const val SONG = "song"
        const val SETLIST = "setlist"
        const val SCORE = "score"
        const val MIX = "mix"
    }
}

/** The manifest as stored on the server (encrypted); [version] is the server's plaintext counter. */
@Serializable
data class Manifest(val entries: List<ManifestEntry> = emptyList()) {
    fun byKey() = entries.associateBy { it.key }
}

/**
 * What a sync has to do, given what this phone has ([local]), what the server has ([remote]) and
 * what both agreed on last time ([base]).
 */
data class MergePlan(
    /** Remote revisions to download and apply. */
    val download: List<ManifestEntry>,
    /** Local revisions to upload (the remote has none or an older one). */
    val upload: List<ManifestEntry>,
    /** Both sides changed since the base: the remote wins, the local version is kept as a copy. */
    val conflicts: List<Conflict>,
) {
    data class Conflict(val local: ManifestEntry, val remote: ManifestEntry)
    val isEmpty get() = download.isEmpty() && upload.isEmpty() && conflicts.isEmpty()
}

object Merge {
    fun plan(local: List<ManifestEntry>, remote: List<ManifestEntry>, base: List<ManifestEntry>): MergePlan {
        val l = local.associateBy { it.key }
        val r = remote.associateBy { it.key }
        val b = base.associateBy { it.key }
        val download = ArrayList<ManifestEntry>()
        val upload = ArrayList<ManifestEntry>()
        val conflicts = ArrayList<MergePlan.Conflict>()
        for (key in (l.keys + r.keys)) {
            val mine = l[key]
            val theirs = r[key]
            val agreed = b[key]
            when {
                theirs == null -> upload += mine!!
                mine == null -> download += theirs
                mine.rev == theirs.rev && mine.blobId == theirs.blobId -> {}
                // Both moved on from the last common state → keep both versions.
                agreed != null && mine.rev > agreed.rev && theirs.rev > agreed.rev && mine.blobId != theirs.blobId ->
                    conflicts += MergePlan.Conflict(mine, theirs)
                // Same revision number but different content with no common base: also a conflict.
                mine.rev == theirs.rev -> conflicts += MergePlan.Conflict(mine, theirs)
                mine.rev > theirs.rev -> upload += mine
                else -> download += theirs
            }
        }
        return MergePlan(download, upload, conflicts)
    }
}
