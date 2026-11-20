package us.fireshare.tweet.widget

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import us.fireshare.tweet.datamodel.MimeiId
import java.util.TreeSet

/** Media3 calls these methods under its cache lock. Saved media counts toward the
 * limit but is never selected for eviction; protection comes from the persisted lists.
 */
@OptIn(UnstableApi::class)
internal class SavedMediaCacheEvictor(
    private val maxBytes: Long,
    private val permanentMediaIds: () -> Set<MimeiId>
) : CacheEvictor {
    private val spans = TreeSet(
        compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position }
    )
    private var currentBytes = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        currentBytes += span.length
        evict(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        currentBytes -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        spans.remove(oldSpan)
        spans.add(newSpan)
    }

    private fun evict(cache: Cache, incomingBytes: Long) {
        if (currentBytes + incomingBytes <= maxBytes) return
        // Resolve only when allocation needs eviction, not on every cache read/touch.
        val permanent = permanentMediaIds()
        for (span in spans.toList()) {
            if (currentBytes + incomingBytes <= maxBytes) break
            if (span.key.substringBefore('/') !in permanent) cache.removeSpan(span)
        }
    }
}
