package us.fireshare.tweet.datamodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class TweetInteractionStateTest {
    @Before
    fun setUp() {
        Tweet.clearAllInstances()
    }

    @After
    fun tearDown() {
        Tweet.clearAllInstances()
    }

    @Test
    fun clearInteractionOverrides_removesLoginStateWithoutDroppingCachedTweet() {
        val tweet = Tweet.getInstance(mid = "comment-id", authorId = "author-id")
        val parent = Tweet.getInstance(mid = "parent-id", authorId = "parent-author-id")
        tweet.favoriteOverride = true
        tweet.bookmarkOverride = false
        tweet.savedParentTweet = parent

        Tweet.clearInteractionOverrides()

        assertNull(tweet.favoriteOverride)
        assertNull(tweet.bookmarkOverride)
        assertSame(parent, tweet.savedParentTweet)
        assertSame(tweet, Tweet.findInstance("comment-id"))
        assertEquals("comment-id", tweet.mid)
    }
}
