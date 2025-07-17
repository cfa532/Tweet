package us.fireshare.tweet.widget

import android.content.Context
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import java.io.File

/**
 * Unified IPFS Cache Manager
 * 
 * This manager provides consistent IPFS ID-based caching for all media types
 * (images, videos, HLS segments) where each attachment is indexed by its IPFS CID.
 * 
 * URL Format: http://ip/mm/mimei_id (IPFS-based URLs)
 * Cache Key: Uses IPFS ID (last part of URL) as cache key
 */
@UnstableApi
object IpfsCacheManager {

    /**
     * Calculate directory size recursively
     */
    private fun getDirectorySize(directory: File): Long {
        var size = 0L
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                getDirectorySize(file)
            } else {
                file.length()
            }
        }
        return size
    }
    
    /**
     * Clear all caches (images, videos, HLS segments)
     */
    fun clearAllCaches(context: Context) {
        // Clear image cache
        val imageCacheDir = File(context.cacheDir, "image_cache")
        if (imageCacheDir.exists()) {
            imageCacheDir.deleteRecursively()
            Timber.d("Cleared image cache")
        }
        
        // Clear video cache (handles both progressive and HLS)
        SimplifiedVideoCacheManager.clearVideoCache(context)
        
        Timber.d("Cleared all IPFS caches")
    }
    
    /**
     * Test IPFS ID extraction for all media types
     */
    fun testIpfsIdExtraction(mediaUrl: String): String {
        val ipfsId = mediaUrl.getMimeiKeyFromUrl()
        
        return buildString {
            appendLine("IPFS ID Extraction Test:")
            appendLine("Original URL: $mediaUrl")
            appendLine("Extracted IPFS ID: $ipfsId")
            appendLine()
            appendLine("Generated Cache Keys:")
            appendLine("- Image: ${ipfsId}_preview.jpg")
            appendLine("- Video: $ipfsId")
        }
    }

    /**
     * Get total cache size across all media types
     */
    fun getTotalCacheSize(context: Context): Long {
        val imageCacheDir = File(context.cacheDir, "image_cache")
        val videoCacheDir = File(context.cacheDir, "video_cache")
        
        var totalSize = 0L
        
        if (imageCacheDir.exists()) {
            totalSize += getDirectorySize(imageCacheDir)
        }
        if (videoCacheDir.exists()) {
            totalSize += getDirectorySize(videoCacheDir)
        }
        
        return totalSize
    }
    
    /**
     * Get cache statistics summary
     */
    fun getCacheSummary(context: Context): String {
        val totalSize = getTotalCacheSize(context)
        val totalSizeMB = totalSize / (1024 * 1024)
        
        val imageCacheDir = File(context.cacheDir, "image_cache")
        val videoCacheDir = File(context.cacheDir, "video_cache")
        
        val imageCount = if (imageCacheDir.exists()) imageCacheDir.listFiles()?.size ?: 0 else 0
        val videoCount = if (videoCacheDir.exists()) videoCacheDir.listFiles()?.size ?: 0 else 0
        
        return buildString {
            appendLine("Total Cache Size: ${totalSizeMB}MB")
            appendLine("Cached Items:")
            appendLine("- Images: $imageCount")
            appendLine("- Videos (Progressive + HLS): $videoCount")
        }
    }
} 