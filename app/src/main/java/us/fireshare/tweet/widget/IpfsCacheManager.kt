package us.fireshare.tweet.widget

import android.content.Context
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
object IpfsCacheManager {
    
    /**
     * Get unified cache statistics for all media types
     */
    fun getUnifiedCacheStats(context: Context): String {
        val imageCache = CacheManager(context)
        val videoCache = SimplifiedVideoCacheManager.getCacheStats(context)
        
        return buildString {
            appendLine("Unified IPFS Cache Statistics:")
            appendLine("Image Cache: ${getImageCacheStats(context)}")
            appendLine("Video Cache (Progressive + HLS): $videoCache")
        }
    }
    
    /**
     * Get image cache statistics
     */
    private fun getImageCacheStats(context: Context): String {
        val imageCacheDir = File(context.cacheDir, "image_cache")
        if (!imageCacheDir.exists()) {
            return "0MB / 0MB (0%)"
        }
        
        val totalSize = getDirectorySize(imageCacheDir)
        val maxSize = 500L * 1024 * 1024 // 500MB max for images
        val usedPercentage = (totalSize * 100 / maxSize).toInt()
        
        return "${totalSize / (1024 * 1024)}MB / ${maxSize / (1024 * 1024)}MB ($usedPercentage%)"
    }
    
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
     * Check if any media type is cached using IPFS ID
     */
    fun isMediaCached(context: Context, mediaUrl: String): Boolean {
        val ipfsId = mediaUrl.getMimeiKeyFromUrl()
        
        // Check image cache
        val imageCache = CacheManager(context)
        if (imageCache.isCached(mediaUrl)) {
            Timber.d("Media cached as image: $ipfsId")
            return true
        }
        
        // Check video cache (handles both progressive and HLS)
        if (SimplifiedVideoCacheManager.isVideoCached(context, mediaUrl)) {
            Timber.d("Media cached as video: $ipfsId")
            return true
        }
        
        return false
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
     * Get detailed cache information for all media types
     */
    fun getDetailedCacheInfo(context: Context): String {
        return buildString {
            appendLine("=== IPFS Cache Information ===")
            appendLine()
            appendLine("Image Cache:")
            appendLine(CacheManager(context).let { cache ->
                val imageCacheDir = File(context.cacheDir, "image_cache")
                "Directory: ${imageCacheDir.absolutePath}"
            })
            appendLine()
            appendLine("Video Cache (Progressive + HLS):")
            appendLine(SimplifiedVideoCacheManager.getDetailedCacheInfo(context))
        }
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
     * Get cache key for a specific media type
     */
    fun getCacheKey(mediaUrl: String, mediaType: String): String {
        val ipfsId = mediaUrl.getMimeiKeyFromUrl()
        
        return when (mediaType.lowercase()) {
            "image" -> "${ipfsId}_preview.jpg"
            "video" -> ipfsId
            else -> ipfsId
        }
    }
    
    /**
     * Get cache directory for a specific media type
     */
    fun getCacheDirectory(context: Context, mediaType: String): File {
        return when (mediaType.lowercase()) {
            "image" -> File(context.cacheDir, "image_cache")
            "video" -> File(context.cacheDir, "video_cache")
            else -> File(context.cacheDir, "unknown_cache")
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