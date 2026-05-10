package us.fireshare.tweet.tweet

import us.fireshare.tweet.datamodel.MimeiId

/**
 * Tracks which tweet rows currently have their text "Show more"-expanded.
 *
 * This is purely a side-channel from [us.fireshare.tweet.widget.SelectableText]
 * to [TweetItem]'s `onGloballyPositioned` so that the latter can refuse to
 * write the (transient) expanded height into [TweetHeightCache].
 *
 * Why this matters: when a user expands a tweet, scrolls away (or navigates
 * to another screen) and comes back, the row is recomposed from scratch —
 * its `isExpanded` state goes back to `false` and the text collapses. But
 * if we had cached the expanded height in [TweetHeightCache], the row's
 * `Modifier.heightIn(min = cachedHeightDp)` floor would keep it tall,
 * leaving collapsed content inside an oversized row.
 *
 * By skipping cache writes while expanded, the cached height stays at the
 * collapsed value, so the remounted row sizes correctly.
 */
object TweetExpansionCache {
    private val expanded = HashSet<MimeiId>()
    private val lock = Any()

    fun isExpanded(key: MimeiId): Boolean = synchronized(lock) { key in expanded }

    fun setExpanded(key: MimeiId, value: Boolean) {
        synchronized(lock) {
            if (value) expanded.add(key) else expanded.remove(key)
        }
    }

    fun clear() = synchronized(lock) { expanded.clear() }
}
