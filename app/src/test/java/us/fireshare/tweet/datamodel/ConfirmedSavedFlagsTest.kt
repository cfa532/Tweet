package us.fireshare.tweet.datamodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedSavedFlagsTest {
    private fun staleServerCopy(mid: String) = Tweet(
        mid = mid,
        authorId = "author",
        timestamp = 1L,
        favorites = mutableListOf(false, false, false),
    )

    @Test
    fun overrideAloneIsInvisibleToListEquality() {
        // Why the confirmed state is written into favorites: lists and rows detect
        // changes by data-class equality, which never sees the override.
        val cached = staleServerCopy("t1")
        val withOverride = staleServerCopy("t1").apply { favoriteOverride = true }
        assertTrue(withOverride.isFavorite)
        assertEquals(listOf(cached), listOf(withOverride))
    }

    @Test
    fun confirmedLikeIsWrittenIntoStaleServerFlags() {
        Tweet.recordConfirmedSavedFlag("viewer-a", "t2", 0, true)
        val fromLaggingNode = staleServerCopy("t2")

        Tweet.applyConfirmedSavedFlags(fromLaggingNode, "viewer-a", readAtViewerRoot = false)

        assertTrue(fromLaggingNode.isFavorite)
        assertFalse(fromLaggingNode.isBookmarked)
        assertNotEquals(listOf(staleServerCopy("t2")), listOf(fromLaggingNode))
    }

    @Test
    fun readOnViewerRootReplacesConfirmedState() {
        Tweet.recordConfirmedSavedFlag("viewer-b", "t3", 1, true)
        val fromRoot = staleServerCopy("t3")
        Tweet.applyConfirmedSavedFlags(fromRoot, "viewer-b", readAtViewerRoot = true)
        assertFalse(fromRoot.isBookmarked)

        // The root read dropped the entry, so a later lagging read is left as served.
        val later = staleServerCopy("t3")
        Tweet.applyConfirmedSavedFlags(later, "viewer-b", readAtViewerRoot = false)
        assertFalse(later.isBookmarked)
    }

    @Test
    fun anotherViewersStateIsNotApplied() {
        Tweet.recordConfirmedSavedFlag("viewer-c", "t4", 0, true)
        val tweet = staleServerCopy("t4")
        Tweet.applyConfirmedSavedFlags(tweet, "viewer-d", readAtViewerRoot = false)
        assertFalse(tweet.isFavorite)
    }
}
