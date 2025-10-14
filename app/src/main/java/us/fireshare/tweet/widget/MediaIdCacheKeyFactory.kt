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
     * Extract media ID from URL.
     * 
     * Examples:
     * - http://192.168.1.1:8080/mm/QmVideo123 -> QmVideo123
     * - http://192.168.1.1:8080/ipfs/QmVideo123 -> QmVideo123
     * - http://192.168.1.1:8080/mm/QmVideo123/master.m3u8 -> QmVideo123
     * - http://192.168.1.1:8080/mm/QmVideo123/playlist.m3u8 -> QmVideo123
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
                // The media ID is right after "mm" or "ipfs"
                val mediaId = parts[mmIndex + 1]
                
                // Remove any query parameters if present
                val cleanMediaId = mediaId.substringBefore('?')
                
                Timber.tag("MediaIdCacheKeyFactory").d("Extracted cache key: $cleanMediaId from URL: $url")
                cleanMediaId
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

