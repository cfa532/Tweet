package us.fireshare.tweet.video

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.widget.VideoManager
import java.io.File
import java.io.FileOutputStream

/**
 * Video normalizer for converting videos to standard MP4 format
 * Can resample to 720p if resolution is higher than 720p
 */
class VideoNormalizer(private val context: Context) {

    companion object {
        private const val TAG = "VideoNormalizer"

        // Dynamic timeout constants (in milliseconds)
        private const val MIN_TIMEOUT_MS = 5 * 60 * 1000L   // 5 minutes minimum
        private const val MAX_TIMEOUT_MS = 2 * 60 * 60 * 1000L  // 2 hours maximum
        private const val BASE_PROCESSING_RATE_MS_PER_SECOND = 1500L  // 1.5 seconds processing per 1 second video
        private const val FILE_SIZE_MULTIPLIER = 0.001  // Additional time per MB of file size
    }

    /**
     * Execute FFmpeg command asynchronously with real-time logging
     */
    private suspend fun executeFFmpegAsync(
        command: String,
        operation: String = "normalization"
    ): Boolean = suspendCancellableCoroutine { cont ->
        Timber.tag(TAG).d("Starting async FFmpeg execution for $operation")

        val session = FFmpegKit.executeAsync(
            command,
            { completedSession: FFmpegSession ->
                val rc = completedSession.returnCode
                if (ReturnCode.isSuccess(rc)) {
                    Timber.tag(TAG).d("FFmpeg $operation succeeded")
                    cont.resume(true)
                } else {
                    val logs = completedSession.allLogsAsString
                    Timber.tag(TAG).e("FFmpeg $operation failed (rc=$rc): $logs")
                    cont.resume(false)
                }
            },
            { log: Log ->
                // Real-time log output from FFmpeg
                Timber.tag(TAG).d("FFmpeg $operation log: ${log.message}")
            },
            { stats: Statistics ->
                // Real-time statistics/progress from FFmpeg
                Timber.tag(TAG).d("FFmpeg $operation stats: time=${stats.time}ms, size=${stats.size}B, bitrate=${stats.bitrate}bps, speed=${stats.speed}x")
            }
        )

        cont.invokeOnCancellation {
            Timber.tag(TAG).w("Cancelling FFmpeg execution for $operation")
            session.cancel()
        }
    }

    /**
     * Calculate dynamic timeout based on video duration and file size
     * @param videoDurationMs Video duration in milliseconds
     * @param fileSizeBytes File size in bytes
     * @return Timeout in milliseconds, clamped between MIN_TIMEOUT_MS and MAX_TIMEOUT_MS
     */
    private fun calculateDynamicTimeout(videoDurationMs: Long?, fileSizeBytes: Long): Long {
        // Base timeout from video duration (1.5 seconds processing per 1 second video for normalization)
        val durationBasedTimeout = videoDurationMs?.let { duration ->
            val durationSeconds = duration / 1000.0
            (durationSeconds * BASE_PROCESSING_RATE_MS_PER_SECOND).toLong()
        } ?: (15 * 60 * 1000L) // 15 minutes fallback if duration unknown

        // Additional time based on file size (0.001 ms per byte = ~1 second per MB)
        val fileSizeBasedAddition = (fileSizeBytes * FILE_SIZE_MULTIPLIER).toLong()

        // Total timeout
        val totalTimeout = durationBasedTimeout + fileSizeBasedAddition

        // Clamp between min and max
        val finalTimeout = totalTimeout.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        Timber.tag(TAG).d("Dynamic timeout calculation: duration=${videoDurationMs}ms, fileSize=${fileSizeBytes}bytes, timeout=${finalTimeout}ms")
        return finalTimeout
    }

    /**
     * Normalize video to 720p and 1000k bitrate, but preserve original if any metric is lower
     * This is used as a preprocessing step before routing to different upload paths
     * @param inputUri Input video URI
     * @param outputFile Output file path
     * @return NormalizationResult with output file or error
     */
    suspend fun normalizeTo720p1000k(
        inputUri: Uri,
        outputFile: File
    ): NormalizationResult = withContext(Dispatchers.IO) {
        try {
            // Get video resolution, duration, and bitrate for timeout calculation and bitrate preservation
            val videoResolution = VideoManager.getVideoResolution(context, inputUri)
            val videoDurationMs = VideoManager.getVideoDuration(context, inputUri)
            val originalBitrateBps = VideoManager.getVideoBitrate(context, inputUri)

            // Calculate file size for timeout calculation
            val fileSizeBytes = try {
                context.contentResolver.openInputStream(inputUri)?.use { stream ->
                    var size = 0L
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        size += bytesRead
                    }
                    size
                } ?: 0L
            } catch (e: Exception) {
                Timber.tag(TAG).w("Could not calculate file size: ${e.message}")
                0L
            }

            // Calculate dynamic timeout based on video duration and file size
            val dynamicTimeoutMs = calculateDynamicTimeout(videoDurationMs, fileSizeBytes)

            // Determine target bitrate: preserve original if lower than 1000k, otherwise use 1000k
            val targetBitrateK = if (originalBitrateBps != null) {
                val originalBitrateK = originalBitrateBps / 1000
                val targetK = minOf(originalBitrateK, 1000)
                Timber.tag(TAG).d("Original bitrate: ${originalBitrateK}k, target bitrate: ${targetK}k")
                targetK
            } else {
                Timber.tag(TAG).w("Could not get original bitrate, defaulting to 1000k")
                1000
            }

            Timber.tag(TAG).d("Normalize to 720p/${targetBitrateK}k: resolution=$videoResolution, duration=${videoDurationMs}ms, size=${fileSizeBytes}bytes")
            Timber.tag(TAG).d("Dynamic timeout set to: ${dynamicTimeoutMs}ms (${dynamicTimeoutMs / 1000 / 60} minutes)")

            // Copy input file to a temporary location for FFmpeg processing
            val tempInputFile = File(context.cacheDir, "temp_normalize_720p_${System.currentTimeMillis()}.mp4")
            try {
                copyUriToFile(inputUri, tempInputFile)
                
                // Build FFmpeg command - normalize to 720p/targetBitrate but preserve if original is lower
                val ffmpegCommand = buildNormalizeTo720p1000kCommand(
                    tempInputFile.absolutePath,
                    outputFile.absolutePath,
                    videoResolution,
                    targetBitrateK
                )
                
                // Execute FFmpeg command asynchronously (dynamic timeout)
                val success = withTimeout(dynamicTimeoutMs) {
                    executeFFmpegAsync(ffmpegCommand, "normalize to 720p/1000k")
                }

                if (success) {
                    NormalizationResult.Success(outputFile)
                } else {
                    Timber.tag(TAG).e("Video normalization to 720p/1000k failed")
                    NormalizationResult.Error("FFmpeg normalization failed")
                }
            } finally {
                // Clean up temp input file
                if (tempInputFile.exists()) {
                    tempInputFile.delete()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during video normalization to 720p/1000k")
            NormalizationResult.Error("Normalization error: ${e.message}")
        }
    }

    /**
     * Build FFmpeg command for normalizing to 720p/targetBitrate while preserving original if lower
     * @param targetBitrateK Target bitrate in kilobits per second (will use min of original and 1000k)
     */
    private fun buildNormalizeTo720p1000kCommand(
        inputPath: String,
        outputPath: String,
        videoResolution: Pair<Int, Int>?,
        targetBitrateK: Int
    ): String {
        val targetBitrateStr = "${targetBitrateK}k"
        
        if (videoResolution == null) {
            // Default to 720p if resolution unknown
            return """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=1280:720:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -preset veryfast
                -threads 4
                -b:v $targetBitrateStr
                -b:a 128k
                -maxrate $targetBitrateStr
                -bufsize $targetBitrateStr
                -movflags +faststart
                -metadata:s:v:0 rotate=0
                "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
        }

        val (width, height) = videoResolution
        val isLandscape = width > height
        
        // Calculate 720p target dimensions
        val (targetWidth, targetHeight) = if (isLandscape) {
            val aspectRatio = width.toFloat() / height.toFloat()
            val targetHeight = 720
            val targetWidth = (720 * aspectRatio).toInt()
            val evenWidth = if (targetWidth % 2 == 0) targetWidth else targetWidth - 1
            val cappedWidth = if (evenWidth > 1280) 1280 else evenWidth
            Pair(cappedWidth, targetHeight)
        } else {
            val aspectRatio = width.toFloat() / height.toFloat()
            val targetWidth = 720
            val targetHeight = (720 / aspectRatio).toInt()
            val evenHeight = if (targetHeight % 2 == 0) targetHeight else targetHeight - 1
            val cappedHeight = if (evenHeight > 1280) 1280 else evenHeight
            Pair(targetWidth, cappedHeight)
        }

        // Determine if we need to scale (only if source is larger than 720p)
        val needsScaling = if (isLandscape) {
            width > targetWidth || height > targetHeight
        } else {
            width > targetWidth || height > targetHeight
        }

        // Use 1500k bitrate, but if original resolution is lower, we'll preserve it via scaling
        val finalWidth = if (needsScaling) targetWidth else width
        val finalHeight = if (needsScaling) targetHeight else height
        
        // Ensure dimensions are even
        val evenWidth = if (finalWidth % 2 == 0) finalWidth else finalWidth - 1
        val evenHeight = if (finalHeight % 2 == 0) finalHeight else finalHeight - 1

        return if (needsScaling) {
            // Scale down to 720p
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=$evenWidth:$evenHeight:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -preset veryfast
                -threads 4
                -b:v $targetBitrateStr
                -b:a 128k
                -maxrate $targetBitrateStr
                -bufsize $targetBitrateStr
                -movflags +faststart
                -metadata:s:v:0 rotate=0
                "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
        } else {
            // Keep original resolution but encode with target bitrate (preserved if lower than 1500k)
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -preset veryfast
                -threads 4
                -b:v $targetBitrateStr
                -b:a 128k
                -maxrate $targetBitrateStr
                -bufsize $targetBitrateStr
                -movflags +faststart
                -metadata:s:v:0 rotate=0
                "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
        }
    }

    /**
     * Normalize video to standard MP4 format
     * @param inputUri Input video URI
     * @param outputFile Output file path
     * @param resampleTo720p If true, resample to 720p resolution (only if source is > 720p)
     * @return True if conversion successful, false otherwise
     */
    suspend fun normalizeVideo(
        inputUri: Uri,
        outputFile: File,
        resampleTo720p: Boolean = false
    ): NormalizationResult = withContext(Dispatchers.IO) {
        try {
            // Get video resolution and duration for timeout calculation
            val videoResolution = VideoManager.getVideoResolution(context, inputUri)
            val videoDurationMs = VideoManager.getVideoDuration(context, inputUri)

            // Calculate file size for timeout calculation
            val fileSizeBytes = try {
                context.contentResolver.openInputStream(inputUri)?.use { stream ->
                    var size = 0L
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        size += bytesRead
                    }
                    size
                } ?: 0L
            } catch (e: Exception) {
                Timber.tag(TAG).w("Could not calculate file size: ${e.message}")
                0L
            }

            // Calculate dynamic timeout based on video duration and file size
            val dynamicTimeoutMs = calculateDynamicTimeout(videoDurationMs, fileSizeBytes)

            Timber.tag(TAG).d("Video normalization: resolution=$videoResolution, duration=${videoDurationMs}ms, size=${fileSizeBytes}bytes")
            Timber.tag(TAG).d("Dynamic timeout set to: ${dynamicTimeoutMs}ms (${dynamicTimeoutMs / 1000 / 60} minutes)")

            // Copy input file to a temporary location for FFmpeg processing
            val tempInputFile = File(context.cacheDir, "temp_normalize_input_${System.currentTimeMillis()}.mp4")
            try {
                copyUriToFile(inputUri, tempInputFile)
                
                // Build FFmpeg command
                val ffmpegCommand = buildNormalizationCommand(
                    tempInputFile.absolutePath,
                    outputFile.absolutePath,
                    videoResolution,
                    resampleTo720p
                )
                
                // Execute FFmpeg command asynchronously (dynamic timeout)
                val success = withTimeout(dynamicTimeoutMs) {
                    executeFFmpegAsync(ffmpegCommand, "normalization")
                }

                if (success) {
                    NormalizationResult.Success(outputFile)
                } else {
                    Timber.tag(TAG).e("Video normalization failed")
                    NormalizationResult.Error("FFmpeg normalization failed")
                }
            } finally {
                // Clean up temp input file
                if (tempInputFile.exists()) {
                    tempInputFile.delete()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during video normalization")
            NormalizationResult.Error("Normalization error: ${e.message}")
        }
    }

    /**
     * Build FFmpeg command for video normalization
     */
    private fun buildNormalizationCommand(
        inputPath: String,
        outputPath: String,
        videoResolution: Pair<Int, Int>?,
        resampleTo720p: Boolean
    ): String {
        val (width, height) = videoResolution ?: Pair(1280, 720)
        
        // Determine if we need to resample
        val needsResampling = resampleTo720p && (width > 1280 || height > 720)
        
        return if (needsResampling) {
            // Resample to 720p while maintaining aspect ratio
            val (targetWidth, targetHeight) = calculate720pDimensions(width, height)
            
            // Determine bitrate based on resolution
            val bitrate = getBitrateForResolution(targetWidth, targetHeight)
            
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -preset veryfast
                -threads 4
                -b:v $bitrate
                -b:a 128k
                -maxrate $bitrate
                -bufsize $bitrate
                -movflags +faststart
                -metadata:s:v:0 rotate=0
                "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
        } else {
            // Just normalize to standard MP4 without resampling
            // Determine bitrate based on original resolution
            val bitrate = getBitrateForResolution(width, height)
            
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -preset veryfast
                -threads 4
                -b:v $bitrate
                -b:a 128k
                -maxrate $bitrate
                -bufsize $bitrate
                -movflags +faststart
                -metadata:s:v:0 rotate=0
                "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
        }
    }

    /**
     * Get bitrate for a given resolution
     * 720p: 1000k, 480p: 600k, 360p: 400k, other resolutions adjusted proportionally
     */
    private fun getBitrateForResolution(width: Int, height: Int): String {
        val maxDimension = maxOf(width, height)
        
        return when {
            maxDimension >= 720 -> "1000k"  // 720p and above
            maxDimension >= 480 -> "600k"   // 480p
            maxDimension >= 360 -> "400k"   // 360p
            else -> "300k"                   // Lower resolutions
        }
    }

    /**
     * Calculate 720p dimensions while maintaining aspect ratio
     */
    private fun calculate720pDimensions(width: Int, height: Int): Pair<Int, Int> {
        val aspectRatio = width.toFloat() / height.toFloat()
        
        return if (aspectRatio < 1.0f) {
            // Portrait: scale to target width 720
            val targetWidth = 720
            val targetHeight = (720 / aspectRatio).toInt()
            // Ensure height is even and not more than 1280
            val evenHeight = if (targetHeight % 2 == 0) targetHeight else targetHeight - 1
            val cappedHeight = if (evenHeight > 1280) 1280 else evenHeight
            Pair(targetWidth, cappedHeight)
        } else {
            // Landscape: scale to target height 720
            val targetHeight = 720
            val targetWidth = (720 * aspectRatio).toInt()
            // Ensure width is even and not more than 1280
            val evenWidth = if (targetWidth % 2 == 0) targetWidth else targetWidth - 1
            val cappedWidth = if (evenWidth > 1280) 1280 else evenWidth
            Pair(cappedWidth, targetHeight)
        }
    }

    /**
     * Copy URI content to a file
     */
    private fun copyUriToFile(uri: Uri, file: File) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    /**
     * Result of video normalization
     */
    sealed class NormalizationResult {
        data class Success(val outputFile: File) : NormalizationResult()
        data class Error(val message: String) : NormalizationResult()
    }
}

