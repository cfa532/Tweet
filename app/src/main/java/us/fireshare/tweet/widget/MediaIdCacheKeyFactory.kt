package us.fireshare.tweet.widget

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheKeyFactory
import timber.log.Timber

/**
 * Custom CacheKeyFactory that uses media ID (mid) extracted from URLs as cache keys.
 * This ensures that the same media accessed from different nodes (different URLs)
 * uses the same cache entry.
 *
 * URL formats:
 * - http://ip/mm/QmMediaId
 * - http://ip/ipfs/QmMediaId
 * - http://ip/mm/QmMediaId/master.m3u8
 * - http://ip/mm/QmMediaId/playlist.m3u8
 *
 * Cache key: QmMediaId (the media ID extracted from the URL)
 */
@OptIn(UnstableApi::class)
class MediaIdCacheKeyFactory : CacheKeyFactory {
    
    override fun buildCacheKey(dataSpec: androidx.media3.datasource.DataSpec): String {
        val url = dataSpec.uri.toString()
        return extractMediaIdFromUrl(url)
    }
    
    /**
     * Extract media ID and path from URL for cache key.
     * 
     * This preserves the path after media ID to ensure unique cache keys for HLS segments.
     * 
     * Examples:
     * - http://192.168.1.1:8080/mm/QmVideo123 -> QmVideo123
     * - http://192.168.1.1:8080/ipfs/QmVideo123 -> QmVideo123
     * - http://192.168.1.1:8080/mm/QmVideo123/master.m3u8 -> QmVideo123/master.m3u8
     * - http://192.168.1.1:8080/mm/QmVideo123/playlist.m3u8 -> QmVideo123/playlist.m3u8
     * - http://192.168.1.1:8080/mm/QmVideo123/segment0.ts -> QmVideo123/segment0.ts
     * 
     * Different nodes serving the same video will share cache:
     * - http://192.168.1.1:8080/mm/QmVideo123/master.m3u8 -> QmVideo123/master.m3u8
     * - http://192.168.1.2:8080/mm/QmVideo123/master.m3u8 -> QmVideo123/master.m3u8 (same key!)
     */
    private fun extractMediaIdFromUrl(url: String): String {
        return try {
            // Remove trailing slashes
            val cleanUrl = url.trimEnd('/')
            
            // Split by / and find the media ID
            val parts = cleanUrl.split("/")
            
            // Find index of "mm" or "ipfs"
            val mmIndex = parts.indexOfFirst { it == "mm" || it == "ipfs" }
            
            if (mmIndex >= 0 && mmIndex + 1 < parts.size) {
                // Get media ID and everything after it (for HLS segments, playlists, etc.)
                val remainingParts = parts.subList(mmIndex + 1, parts.size)
                val cacheKey = remainingParts.joinToString("/")
                
                // Remove any query parameters if present
                val cleanCacheKey = cacheKey.substringBefore('?')
                
                Timber.tag("MediaIdCacheKeyFactory").d("Extracted cache key: $cleanCacheKey from URL: $url")
                cleanCacheKey
            } else {
                // Fallback to URL if we can't extract media ID
                Timber.tag("MediaIdCacheKeyFactory").w("Could not extract media ID from URL: $url, using full URL as cache key")
                url
            }
        } catch (e: Exception) {
            // Fallback to URL if extraction fails
            Timber.tag("MediaIdCacheKeyFactory").e(e, "Error extracting media ID from URL: $url, using full URL as cache key")
            url
        }
    }
}

