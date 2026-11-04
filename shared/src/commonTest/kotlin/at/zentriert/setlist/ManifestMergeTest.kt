package at.zentriert.setlist

import at.zentriert.setlist.sync.ManifestEntry
import at.zentriert.setlist.sync.Merge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestMergeTest {
    private fun e(id: String, rev: Long, blob: String = "b$rev", type: String = ManifestEntry.SONG, deleted: Boolean = false) =
        ManifestEntry(type, id, rev, rev * 1000, "dev", blob, deleted)

    @Test
    fun newEntriesFlowBothWays() {
        val plan = Merge.plan(local = listOf(e("a", 1)), remote = listOf(e("b", 3)), base = emptyList())
        assertEquals(listOf("b"), plan.download.map { it.id })
        assertEquals(listOf("a"), plan.upload.map { it.id })
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun identicalEntriesNeedNothing() {
        val plan = Merge.plan(listOf(e("a", 2)), listOf(e("a", 2)), listOf(e("a", 2)))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun higherRevisionWinsWhenOnlyOneSideChanged() {
        val base = listOf(e("a", 2), e("b", 2))
        val plan = Merge.plan(local = listOf(e("a", 3), e("b", 2)), remote = listOf(e("a", 2), e("b", 4)), base = base)
        assertEquals(listOf("a"), plan.upload.map { it.id })
        assertEquals(listOf("b"), plan.download.map { it.id })
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun bothChangedSinceBaseIsAConflict() {
        val plan = Merge.plan(local = listOf(e("a", 3, "mine")), remote = listOf(e("a", 3, "theirs")), base = listOf(e("a", 2)))
        assertEquals(1, plan.conflicts.size)
        assertEquals("mine", plan.conflicts.single().local.blobId)
        assertEquals("theirs", plan.conflicts.single().remote.blobId)
        // Even when one side made more edits than the other.
        val plan2 = Merge.plan(listOf(e("a", 5, "mine")), listOf(e("a", 3, "theirs")), listOf(e("a", 2)))
        assertEquals(1, plan2.conflicts.size)
        assertTrue(plan2.upload.isEmpty())
    }

    @Test
    fun tombstonesSyncLikeAnyRevision() {
        val plan = Merge.plan(local = listOf(e("a", 2)), remote = listOf(e("a", 3, deleted = true)), base = listOf(e("a", 2)))
        assertEquals(true, plan.download.single().deleted)
    }

    @Test
    fun sameRevisionDifferentContentWithoutBaseIsAConflict() {
        val plan = Merge.plan(listOf(e("a", 1, "x")), listOf(e("a", 1, "y")), emptyList())
        assertEquals(1, plan.conflicts.size)
    }
}
