package us.fireshare.tweet.tweet

import us.fireshare.tweet.datamodel.MimeiId

/**
 * In-memory cache of measured tweet row heights (in px) for scroll stability.
 * Matches iOS TweetHeightCache + tweet.cachedHeight: when scrolling upward, rows
 * that re-enter the viewport use cached height so the list doesn't jump.
 */
object TweetHeightCache {
    private const val MAX_ENTRIES = 2000
    private val heights = mutableMapOf<MimeiId, Float>()
    private val lock = Any()

    fun getHeightPx(mid: MimeiId): Float? = synchronized(lock) { heights[mid] }

    fun setHeight(mid: MimeiId, heightPx: Float) {
        synchronized(lock) {
            heights[mid] = heightPx
            if (heights.size > MAX_ENTRIES) {
                val toRemove = heights.keys.take(heights.size - MAX_ENTRIES)
                toRemove.forEach { heights.remove(it) }
            }
        }
    }

    fun remove(mid: MimeiId) = synchronized(lock) { heights.remove(mid) }

    fun clear() = synchronized(lock) { heights.clear() }
}
