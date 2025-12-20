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
     * Normalize video with proportional bitrate calculation:
     * - If resolution > 720p: Normalize to 720p with 1000k bitrate
     * - If resolution ≤ 720p: Keep original resolution with proportional bitrate (1000k × resolution/720)
     * 
     * Resolution detection:
     * - Landscape (width >= height): Resolution = HEIGHT
     * - Portrait (height > width): Resolution = WIDTH
     * 
     * @param inputUri Input video URI
     * @param outputFile Output file path
     * @return NormalizationResult with output file or error
     */
    suspend fun normalizeTo720p1000k(
        inputUri: Uri,
        outputFile: File
    ): NormalizationResult = withContext(Dispatchers.IO) {
        try {
            // Get video resolution and duration for timeout calculation
            val videoResolution = VideoManager.getVideoResolution(context, inputUri)
            val videoDurationMs = VideoManager.getVideoDuration(context, inputUri)
            val resolutionValue = VideoManager.getVideoResolutionValue(videoResolution)

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

            // Calculate target bitrate based on resolution
            // Formula: bitrate = 1000k × (resolution / 720)
            // If resolution > 720p, normalize to 720p with 1000k
            // If resolution ≤ 720p, keep original resolution with proportional bitrate
            val targetBitrateK = if (resolutionValue != null && resolutionValue > 720) {
                // Resolution > 720p: normalize to 720p with 1000k
                1000
            } else if (resolutionValue != null) {
                // Resolution ≤ 720p: proportional bitrate
                val calculatedBitrate = (1000.0 * resolutionValue / 720.0).toInt()
                calculatedBitrate
            } else {
                // Fallback if resolution unknown
                Timber.tag(TAG).w("Could not determine resolution, defaulting to 1000k")
                1000
            }

            Timber.tag(TAG).d("Normalization: resolution=$videoResolution (${resolutionValue}p), target bitrate=${targetBitrateK}k, duration=${videoDurationMs}ms, size=${fileSizeBytes}bytes")
            Timber.tag(TAG).d("Dynamic timeout set to: ${dynamicTimeoutMs}ms (${dynamicTimeoutMs / 1000 / 60} minutes)")

            // Copy input file to a temporary location for FFmpeg processing
            val tempInputFile = File(context.cacheDir, "temp_normalize_720p_${System.currentTimeMillis()}.mp4")
            try {
                copyUriToFile(inputUri, tempInputFile)
                
                // Build FFmpeg command - normalize to 720p/targetBitrate or keep original with proportional bitrate
                val ffmpegCommand = buildNormalizeTo720p1000kCommand(
                    tempInputFile.absolutePath,
                    outputFile.absolutePath,
                    videoResolution,
                    resolutionValue,
                    targetBitrateK
                )
                
                // Create dynamic operation description based on actual target
                val operationDescription = if (resolutionValue != null && resolutionValue > 720) {
                    "normalize to 720p/${targetBitrateK}k"
                } else if (resolutionValue != null) {
                    "normalize to ${resolutionValue}p/${targetBitrateK}k"
                } else {
                    "normalize to 720p/${targetBitrateK}k"
                }
                
                // Execute FFmpeg command asynchronously (dynamic timeout)
                val success = withTimeout(dynamicTimeoutMs) {
                    executeFFmpegAsync(ffmpegCommand, operationDescription)
                }

                if (success) {
                    // Get actual output resolution for logging
                    val outputResolution = VideoManager.getVideoResolution(context, Uri.fromFile(outputFile))
                    val outputResolutionValue = VideoManager.getVideoResolutionValue(outputResolution)
                    val (outputWidth, outputHeight) = outputResolution ?: Pair(0, 0)
                    
                    Timber.tag(TAG).d("Normalized to ${outputWidth}×${outputHeight} (${outputResolutionValue}p) with ${targetBitrateK}k bitrate")
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
     * Build FFmpeg command for normalizing video:
     * - If resolution > 720p: Scale to 720p with 1000k bitrate
     * - If resolution ≤ 720p: Keep original resolution with proportional bitrate
     * 
     * @param videoResolution Pair of (width, height)
     * @param resolutionValue Resolution value (HEIGHT for landscape, WIDTH for portrait)
     * @param targetBitrateK Target bitrate in kilobits per second (calculated proportionally)
     */
    private fun buildNormalizeTo720p1000kCommand(
        inputPath: String,
        outputPath: String,
        videoResolution: Pair<Int, Int>?,
        resolutionValue: Int?,
        targetBitrateK: Int
    ): String {
        val targetBitrateStr = "${targetBitrateK}k"
        
        if (videoResolution == null || resolutionValue == null) {
            // Default to 720p if resolution unknown
            // Added iOS/VideoJs compatibility: profile baseline, yuv420p pixel format, keyframe interval, level
            return """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=1280:720:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -preset veryfast
                -profile:v baseline
                -pix_fmt yuv420p
                -g 30
                -level 3.1
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
        val isLandscape = width >= height
        
        // If resolution > 720p, scale to 720p; otherwise keep original
        val shouldScaleTo720p = resolutionValue > 720
        
        if (shouldScaleTo720p) {
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
            
            // Scale down to 720p
            // Added iOS/VideoJs compatibility: profile baseline, yuv420p pixel format, keyframe interval, level
            return """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -preset veryfast
                -profile:v baseline
                -pix_fmt yuv420p
                -g 30
                -level 3.1
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
            // Keep original resolution but encode with proportional bitrate
            // Ensure dimensions are even
            val evenWidth = if (width % 2 == 0) width else width - 1
            val evenHeight = if (height % 2 == 0) height else height - 1
            
            // Added iOS/VideoJs compatibility: profile baseline, yuv420p pixel format, keyframe interval, level
            return """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=$evenWidth:$evenHeight:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -preset veryfast
                -profile:v baseline
                -pix_fmt yuv420p
                -g 30
                -level 3.1
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
            
            // Added iOS/VideoJs compatibility: profile baseline, yuv420p pixel format, keyframe interval, level
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -preset veryfast
                -profile:v baseline
                -pix_fmt yuv420p
                -g 30
                -level 3.1
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
            
            // Added iOS/VideoJs compatibility: profile baseline, yuv420p pixel format, keyframe interval, level
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -preset veryfast
                -profile:v baseline
                -pix_fmt yuv420p
                -g 30
                -level 3.1
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

