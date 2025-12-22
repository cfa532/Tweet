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
import java.util.Locale

/**
 * Local HLS converter using FFmpeg Kit
 * Converts uploaded videos to HLS format with multiple resolutions (720p and 480p)
 * Compatible with videoPreview player expectations
 */
class LocalHLSConverter(private val context: Context) {

    companion object {
        private const val TAG = "LocalHLSConverter"
        private const val HLS_SEGMENT_DURATION = 10 // 10 seconds per segment
        // 0 = keep all segments for VOD playlists; we don't want sliding-window/live behavior here.
        private const val HLS_PLAYLIST_SIZE = 0

        // Base bitrate for 720p video (in kbps) - used for proportional bitrate calculations
        private const val REFERENCE_720P_BITRATE = 1000
        private const val MIN_BITRATE = 300  // Minimum bitrate in kbps (reduced from 500k)

        // Dynamic timeout constants (in milliseconds)
        private const val MIN_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutes minimum
        private const val MAX_TIMEOUT_MS = 3 * 60 * 60 * 1000L  // 3 hours maximum
        private const val BASE_PROCESSING_RATE_MS_PER_SECOND = 2000L  // Conservative: 2 seconds processing per 1 second video
        private const val FILE_SIZE_MULTIPLIER = 0.001  // Additional time per MB of file size
    }

    /**
     * Calculate dynamic timeout based on video duration and file size
     * @param videoDurationMs Video duration in milliseconds
     * @param fileSizeBytes File size in bytes
     * @return Timeout in milliseconds, clamped between MIN_TIMEOUT_MS and MAX_TIMEOUT_MS
     */
    private fun calculateDynamicTimeout(videoDurationMs: Long?, fileSizeBytes: Long): Long {
        // Base timeout from video duration (conservative estimate: 2 seconds processing per 1 second video)
        val durationBasedTimeout = videoDurationMs?.let { duration ->
            val durationSeconds = duration / 1000.0
            (durationSeconds * BASE_PROCESSING_RATE_MS_PER_SECOND).toLong()
        } ?: (30 * 60 * 1000L) // 30 minutes fallback if duration unknown

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
     * Convert video to HLS format with multiple resolutions
     * Matches iOS behavior: automatically decides between dual variant (720p + 480p) or single variant (480p only)
     * based on source video resolution
     * 
     * @param inputUri Input video URI
     * @param outputDir Output directory for HLS files
     * @param fileName Original filename (without extension)
     * @param fileSizeBytes File size in bytes for determining resolution/bitrate configuration
     * @param isNormalized If true, the input video is already normalized
     * @param normalizedResolution The resolution to which the video was normalized (null if not normalized)
     * @return Result containing the output directory path and success status
     */
    suspend fun convertToHLS(
        inputUri: Uri,
        outputDir: File,
        fileName: String,
        fileSizeBytes: Long,
        isNormalized: Boolean = false,
        normalizedResolution: Int? = null
    ): HLSConversionResult = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).d("========== SECOND PASS (HLS Segmentation) ==========")
            Timber.tag(TAG).d("Starting HLS conversion for: $fileName")
            Timber.tag(TAG)
                .d("Input is normalized: $isNormalized (resolution: ${normalizedResolution}p)")

            // Check video resolution, duration for timeout calculation
            val videoResolution = VideoManager.getVideoResolution(context, inputUri)
            val videoResolutionValue = VideoManager.getVideoResolutionValue(videoResolution)
            val videoDurationMs = VideoManager.getVideoDuration(context, inputUri)

            // Use standard configuration for all video sizes
            val fileSizeMB = fileSizeBytes / (1024.0 * 1024.0)

            // Calculate bitrates using proportional algorithm (consistent with server and iOS)
            // High quality bitrate depends on source resolution:
            // - >720p: normalize to 720p @ 1000k
            // - =720p: 720p @ 1000k (base reference)
            // - <720p: original resolution @ proportional bitrate based on pixel count
            val highQualityBitrate = when {
                videoResolutionValue != null && videoResolutionValue > 720 -> 1000
                videoResolutionValue == 720 -> REFERENCE_720P_BITRATE
                videoResolutionValue != null && videoResolutionValue < 720 && videoResolution != null -> {
                    val (width, height) = videoResolution
                    val pixelCount = width * height
                    val REFERENCE_720P_PIXELS = 921600
                    maxOf(
                        MIN_BITRATE,
                        ((pixelCount.toDouble() / REFERENCE_720P_PIXELS) * REFERENCE_720P_BITRATE).toInt()
                    )
                }

                else -> REFERENCE_720P_BITRATE  // fallback if resolution unknown
            }

            // Lower variant: always 480p (matches iOS)
            val lowerResolution = 480
            // Lower variant bitrate: proportional to REFERENCE_720P_BITRATE based on actual pixel count (min 500k)
            // Calculate actual dimensions based on aspect ratio, then use pixel count
            val REFERENCE_720P_PIXELS = 921600
            val lowerResolutionBitrate = if (videoResolution != null) {
                val (width, height) = videoResolution
                val aspectRatio = width.toFloat() / height.toFloat()
                val (lowerWidth, lowerHeight) = if (aspectRatio < 1.0f) {
                    // Portrait: scale to target width
                    val targetWidth = minOf(width, lowerResolution)
                    val targetHeight = (targetWidth / aspectRatio).toInt()
                    Pair(targetWidth, targetHeight)
                } else {
                    // Landscape: scale to target height
                    val targetHeight = minOf(height, lowerResolution)
                    val targetWidth = (targetHeight * aspectRatio).toInt()
                    Pair(targetWidth, targetHeight)
                }
                val lowerPixelCount = lowerWidth * lowerHeight
                val calculatedBitrate =
                    ((lowerPixelCount.toDouble() / REFERENCE_720P_PIXELS) * REFERENCE_720P_BITRATE).toInt()
                Timber.tag(TAG)
                    .d("Lower variant pixel-based calculation: ${lowerWidth}×${lowerHeight} (${lowerPixelCount} pixels) = ${calculatedBitrate}k")
                maxOf(MIN_BITRATE, calculatedBitrate)
            } else {
                // Fallback if resolution unknown
                maxOf(
                    MIN_BITRATE,
                    ((lowerResolution * lowerResolution).toDouble() / REFERENCE_720P_PIXELS * REFERENCE_720P_BITRATE).toInt()
                )
            }

            Timber.tag(TAG).d("File size ${String.format(Locale.US, "%.1f", fileSizeMB)}MB")
            Timber.tag(TAG)
                .d("High quality: ${videoResolutionValue}p @ ${highQualityBitrate}k, Lower: 480p @ ${lowerResolutionBitrate}k")

            // Calculate dynamic timeout based on video duration and file size
            val dynamicTimeoutMs = calculateDynamicTimeout(videoDurationMs, fileSizeBytes)

            Timber.tag(TAG)
                .d("Video resolution: $videoResolution (${videoResolutionValue}p), duration: ${videoDurationMs}ms")
            Timber.tag(TAG)
                .d("Dynamic timeout set to: ${dynamicTimeoutMs}ms (${dynamicTimeoutMs / 1000 / 60} minutes)")

            // Determine if we should create 720p variant based on source resolution (matches iOS logic)
            // - If source resolution > 480p: create dual variant (720p + 480p)
            // - Otherwise: create single variant (480p only)
            val shouldCreate720p = videoResolutionValue != null && videoResolutionValue > 480

            if (shouldCreate720p) {
                Timber.tag(TAG)
                    .d("Source resolution ${videoResolutionValue}p > 480p: creating dual variant (720p + 480p)")
            } else {
                Timber.tag(TAG)
                    .d("Source resolution ${videoResolutionValue}p ≤ 480p: creating single variant (480p only)")
            }

            // Ensure output directory exists
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            // Copy input file to a temporary location for FFmpeg processing
            val tempInputFile = File(outputDir, "temp_input.mp4")
            copyUriToFile(inputUri, tempInputFile)

            // Always create master.m3u8 for both single and dual variants
            // This matches iOS behavior and provides consistent HLS structure
            val masterPlaylistPath = File(outputDir, "master.m3u8").absolutePath

            val (playlist720Path, lowerResPlaylistPath) = if (shouldCreate720p) {
                // Dual variant: use subdirectories
                val dir720 = File(outputDir, "720p")
                val dir480 = File(outputDir, "480p")
                dir720.mkdirs()
                dir480.mkdirs()
                Pair(
                    File(dir720, "playlist.m3u8").absolutePath,
                    File(dir480, "playlist.m3u8").absolutePath
                )
            } else {
                // Single variant: playlist at root level
                Pair(
                    "", // No 720p path needed
                    File(outputDir, "playlist.m3u8").absolutePath
                )
            }

            // Convert 720p only if shouldCreate720p is true
            var finalWidth720 = 0
            var finalHeight720 = 0
            var success720 = false

            if (shouldCreate720p) {
                // Calculate proper 720p dimensions based on aspect ratio
                val (targetWidth720, targetHeight720) = calculateActualResolution(
                    720,
                    videoResolution
                )

                // Never increase resolution: if source is lower than 720p, keep original resolution
                // Use resolution value (HEIGHT for landscape, WIDTH for portrait) for comparison
                val (w720, h720) = videoResolution?.let { (width, height) ->
                    val resolutionValue = VideoManager.getVideoResolutionValue(videoResolution) ?: 0

                    if (resolutionValue < 720) {
                        // Source is lower than 720p, keep original resolution (no upscaling)
                        Timber.tag(TAG)
                            .d("Source resolution ${width}x${height} (${resolutionValue}p) is lower than 720p, keeping original resolution")
                        Pair(width, height)
                    } else {
                        // Source is >= 720p, use calculated 720p dimensions
                        Pair(targetWidth720, targetHeight720)
                    }
                } ?: Pair(targetWidth720, targetHeight720)

                finalWidth720 = w720
                finalHeight720 = h720

                // Use calculated high quality bitrate (proportional algorithm)
                val target720pBitrate = "${highQualityBitrate}k"

                // For 720p HLS stream: Use COPY codec if normalized resolution is >480p and ≤720p
                // This preserves native quality without upscaling (matches iOS logic)
                val shouldUseCopyFor720p = isNormalized &&
                        normalizedResolution != null &&
                        normalizedResolution > 480 &&
                        normalizedResolution <= 720

                if (shouldUseCopyFor720p) {
                    Timber.tag(TAG)
                        .d("========== 720p VARIANT: COPY CODEC (No Re-encoding) ==========")
                    Timber.tag(TAG).d("  Video already normalized to ${normalizedResolution}p")
                    Timber.tag(TAG).d("  Target: ${normalizedResolution}p (no scaling needed)")
                    Timber.tag(TAG).d("  Method: COPY codec - preserves standardized quality")
                    Timber.tag(TAG).d("  No second normalization - just segmenting for HLS")
                    Timber.tag(TAG)
                        .d("==============================================================")
                } else {
                    Timber.tag(TAG).d("========== 720p VARIANT: RE-ENCODING (libx264) ==========")
                    Timber.tag(TAG).d("  Source: ${videoResolution} (${videoResolutionValue}p)")
                    Timber.tag(TAG).d("  Target: ${finalWidth720}x${finalHeight720}")
                    Timber.tag(TAG).d("  Method: libx264 re-encoding")
                    Timber.tag(TAG).d("  Bitrate: ${target720pBitrate}")
                    Timber.tag(TAG).d("==========================================================")
                }

                // Execute FFmpeg command for 720p with fallback (dynamic timeout)
                success720 = withTimeout(dynamicTimeoutMs) {
                    executeFFmpegWithFallback(
                        inputPath = tempInputFile.absolutePath,
                        outputPath = playlist720Path,
                        width = finalWidth720,
                        height = finalHeight720,
                        bitrate = target720pBitrate,
                        audioBitrate = "128k",
                        shouldUseCopyCodec = shouldUseCopyFor720p,
                        resolution = "720p"
                    )
                }

                if (!success720) {
                    tempInputFile.delete()
                    return@withContext HLSConversionResult.Error("FFmpeg 720p conversion failed with both COPY and libx264")
                }
            }

            // Calculate proper lower resolution dimensions based on aspect ratio
            val (targetWidthLower, targetHeightLower) = calculateActualResolution(
                lowerResolution,
                videoResolution
            )

            // Never increase resolution: if source is lower than target, keep original resolution
            // Use resolution value (HEIGHT for landscape, WIDTH for portrait) for comparison
            val (finalWidthLower, finalHeightLower) = videoResolution?.let { (width, height) ->
                val resolutionValue = VideoManager.getVideoResolutionValue(videoResolution) ?: 0

                if (resolutionValue < lowerResolution) {
                    // Source is lower than target resolution, keep original resolution (no upscaling)
                    Timber.tag(TAG)
                        .d("Source resolution ${width}x${height} (${resolutionValue}p) is lower than ${lowerResolution}p, keeping original resolution")
                    Pair(width, height)
                } else {
                    // Source is >= target resolution, use calculated target dimensions
                    Pair(targetWidthLower, targetHeightLower)
                }
            } ?: Pair(targetWidthLower, targetHeightLower)

            // Use calculated lower bitrate (proportional algorithm)
            val targetLowerBitrate = "${lowerResolutionBitrate}k"

            // For 480p HLS stream: Use COPY codec if normalized resolution is ≤480p
            // This preserves native quality without upscaling (matches iOS logic)
            val shouldUseCopyForLower = isNormalized &&
                    normalizedResolution != null &&
                    normalizedResolution <= 480

            if (shouldUseCopyForLower) {
                Timber.tag(TAG).d("========== 480p VARIANT: COPY CODEC (No Re-encoding) ==========")
                Timber.tag(TAG).d("  Video already normalized to ${normalizedResolution}p")
                Timber.tag(TAG).d("  Target: ${normalizedResolution}p (no scaling needed)")
                Timber.tag(TAG).d("  Method: COPY codec - preserves standardized quality")
                Timber.tag(TAG).d("  No second normalization - just segmenting for HLS")
                Timber.tag(TAG).d("==============================================================")
            } else {
                Timber.tag(TAG).d("========== 480p VARIANT: RE-ENCODING (libx264) ==========")
                Timber.tag(TAG).d("  Source: ${videoResolution} (${videoResolutionValue}p)")
                Timber.tag(TAG).d("  Target: ${finalWidthLower}x${finalHeightLower}")
                Timber.tag(TAG).d("  Method: libx264 re-encoding")
                Timber.tag(TAG).d("  Bitrate: ${targetLowerBitrate}")
                Timber.tag(TAG).d("==========================================================")
            }

            // Execute FFmpeg command for lower resolution with fallback (dynamic timeout)
            val successLower = withTimeout(dynamicTimeoutMs) {
                executeFFmpegWithFallback(
                    inputPath = tempInputFile.absolutePath,
                    outputPath = lowerResPlaylistPath,
                    width = finalWidthLower,
                    height = finalHeightLower,
                    bitrate = targetLowerBitrate,
                    audioBitrate = "128k",
                    shouldUseCopyCodec = shouldUseCopyForLower,
                    resolution = "480p",
                    segmentsAtRoot = !shouldCreate720p // For single variant, segments go at root level
                )
            }

            if (!successLower) {
                tempInputFile.delete()
                return@withContext HLSConversionResult.Error("FFmpeg 480p conversion failed with both COPY and libx264")
            }

            // Clean up temporary input file
            tempInputFile.delete()

            // Create master playlist for both single and dual variants
            if (shouldCreate720p) {
                Timber.tag(TAG)
                    .d("Dual variant HLS conversion completed successfully (720p + 480p)")
                createMasterPlaylist(
                    outputDir,
                    finalWidth720,
                    finalHeight720,
                    finalWidthLower,
                    finalHeightLower,
                    "${highQualityBitrate}k",
                    "${lowerResolutionBitrate}k"
                )
            } else {
                // Single variant: create master playlist pointing to root-level playlist.m3u8
                Timber.tag(TAG)
                    .d("Single variant HLS conversion completed successfully (480p only)")
                createSingleVariantMasterPlaylist(
                    outputDir,
                    finalWidthLower,
                    finalHeightLower,
                    "${lowerResolutionBitrate}k"
                )
            }

            // Verify that required files were created
            val masterFile = File(masterPlaylistPath)
            val lowerResPlaylistFile = File(lowerResPlaylistPath)

            if (shouldCreate720p) {
                // Dual variant: verify master playlist, 720p playlist, and 480p playlist
                val playlist720File = File(playlist720Path)

                if (masterFile.exists() && playlist720File.exists() && lowerResPlaylistFile.exists()) {
                    Timber.tag(TAG).d("HLS conversion successful - all dual variant files created")
                    Timber.tag(TAG).d("Master playlist: ${masterFile.absolutePath}")
                    Timber.tag(TAG).d("720p playlist: ${playlist720File.absolutePath}")
                    Timber.tag(TAG).d("480p playlist: ${lowerResPlaylistFile.absolutePath}")
                    HLSConversionResult.Success(outputDir)
                } else {
                    Timber.tag(TAG).e("Required HLS files not created")
                    Timber.tag(TAG).e("Master exists: ${masterFile.exists()}")
                    Timber.tag(TAG).e("720p playlist exists: ${playlist720File.exists()}")
                    Timber.tag(TAG).e("480p playlist exists: ${lowerResPlaylistFile.exists()}")
                    HLSConversionResult.Error("Required HLS files not created")
                }
            } else {
                // Single variant: verify master playlist and single playlist at root level
                if (masterFile.exists() && lowerResPlaylistFile.exists()) {
                    Timber.tag(TAG).d("HLS conversion successful - single variant files created")
                    Timber.tag(TAG).d("Master playlist: ${masterFile.absolutePath}")
                    Timber.tag(TAG).d("480p playlist: ${lowerResPlaylistFile.absolutePath}")
                    HLSConversionResult.Success(outputDir)
                } else {
                    Timber.tag(TAG).e("Required HLS files not created")
                    Timber.tag(TAG).e("Master exists: ${masterFile.exists()}")
                    Timber.tag(TAG).e("480p playlist exists: ${lowerResPlaylistFile.exists()}")
                    HLSConversionResult.Error("Required HLS files not created")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during HLS conversion")
            HLSConversionResult.Error("Conversion error: ${e.message}")
        }
    }

    /**
     * Execute FFmpeg command asynchronously with real-time logging and progress tracking
     */
    private suspend fun executeFFmpegAsync(
        command: String,
        resolution: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        Timber.tag(TAG).d("Starting async FFmpeg execution for $resolution")

        val session = FFmpegKit.executeAsync(
            command,
            { completedSession: FFmpegSession ->
                val rc = completedSession.returnCode
                if (ReturnCode.isSuccess(rc)) {
                    Timber.tag(TAG).d("FFmpeg $resolution conversion succeeded")
                    cont.resume(true)
                } else {
                    val logs = completedSession.allLogsAsString
                    Timber.tag(TAG).e("FFmpeg $resolution failed (rc=$rc): $logs")
                    cont.resume(false)
                }
            },
            { log: Log ->
                // Real-time log output from FFmpeg
                Timber.tag(TAG).d("FFmpeg $resolution log: ${log.message}")
            },
            { stats: Statistics ->
                // Real-time statistics/progress from FFmpeg
                Timber.tag(TAG).d("FFmpeg $resolution stats: time=${stats.time}ms, size=${stats.size}B, bitrate=${stats.bitrate}bps, speed=${stats.speed}x")
            }
        )

        cont.invokeOnCancellation {
            Timber.tag(TAG).w("Cancelling FFmpeg execution for $resolution")
            session.cancel()
        }
    }

    /**
     * Execute FFmpeg command with fallback from COPY codec to libx264
     * If COPY codec fails, automatically retry with libx264 encoding
     */
    private suspend fun executeFFmpegWithFallback(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        bitrate: String,
        audioBitrate: String,
        shouldUseCopyCodec: Boolean,
        resolution: String,
        segmentsAtRoot: Boolean = false
    ): Boolean {
        // First, try with the recommended codec (COPY if applicable, libx264 otherwise)
        val firstCommand = buildSingleResolutionFFmpegCommand(
            inputPath = inputPath,
            outputPath = outputPath,
            width = width,
            height = height,
            bitrate = bitrate,
            audioBitrate = audioBitrate,
            useCopyPreset = shouldUseCopyCodec,
            segmentsAtRoot = segmentsAtRoot
        )

        Timber.tag(TAG).d("FFmpeg command for $resolution (first attempt): $firstCommand")

        val firstSuccess = executeFFmpegAsync(firstCommand, "$resolution (first attempt)")

        if (firstSuccess) {
            Timber.tag(TAG).d("FFmpeg $resolution conversion succeeded with first attempt")
            return true
        }

        // If COPY codec failed and we should have used it, try libx264 fallback
        if (shouldUseCopyCodec) {
            Timber.tag(TAG).w("COPY codec failed for $resolution, falling back to libx264")

            val fallbackCommand = buildSingleResolutionFFmpegCommand(
                inputPath = inputPath,
                outputPath = outputPath,
                width = width,
                height = height,
                bitrate = bitrate,
                audioBitrate = audioBitrate,
                useCopyPreset = false, // Force libx264
                segmentsAtRoot = segmentsAtRoot
            )

            Timber.tag(TAG).d("FFmpeg command for $resolution (fallback): $fallbackCommand")

            val fallbackSuccess = executeFFmpegAsync(fallbackCommand, "$resolution (libx264 fallback)")

            if (fallbackSuccess) {
                Timber.tag(TAG).d("FFmpeg $resolution conversion succeeded with libx264 fallback")
                return true
            } else {
                Timber.tag(TAG).e("FFmpeg $resolution conversion failed with libx264 fallback")
                return false
            }
        } else {
            // If libx264 failed, no fallback available
            Timber.tag(TAG).e("FFmpeg $resolution conversion failed with libx264 (no fallback available)")
            return false
        }
    }

    /**
     * Calculate actual resolution based on target resolution and aspect ratio
     * Similar to Swift version's calculateActualResolution function
     */
    private fun calculateActualResolution(targetResolution: Int, videoResolution: Pair<Int, Int>?): Pair<Int, Int> {
        if (videoResolution == null) {
            // Default to landscape if no aspect ratio
            return when (targetResolution) {
                720 -> Pair(1280, 720)
                480 -> Pair(854, 480)
                360 -> Pair(640, 360)
                else -> Pair(targetResolution * 16 / 9, targetResolution)
            }
        }
        
        val (width, height) = videoResolution
        val aspectRatio = width.toFloat() / height.toFloat()
        
        return if (aspectRatio < 1.0f) {
            // Portrait: scale to target width, calculate height
            val targetWidth = targetResolution
            val targetHeight = (targetResolution / aspectRatio).toInt()
            // Ensure height is even
            val evenHeight = if (targetHeight % 2 == 0) targetHeight else targetHeight - 1
            Pair(targetWidth, evenHeight)
        } else {
            // Landscape: scale to target height, calculate width
            val targetHeight = targetResolution
            val targetWidth = (targetResolution * aspectRatio).toInt()
            // Ensure width is even
            val evenWidth = if (targetWidth % 2 == 0) targetWidth else targetWidth - 1
            Pair(evenWidth, targetHeight)
        }
    }

    /**
     * Determine if COPY codec should be used based on video resolution and target resolution
     * Use COPY if source resolution <= target resolution to preserve original quality and bitrate
     */
    private fun shouldUseCopyCodecForResolution(videoResolution: Pair<Int, Int>?, targetResolution: Int): Boolean {
        if (videoResolution == null) {
            Timber.tag(TAG).w("Video resolution is null, defaulting to normal conversion")
            return false
        }

        val (width, height) = videoResolution

        // Determine if video is landscape or portrait
        val isLandscape = width > height

        // Calculate target dimensions for the given resolution
        val (targetWidth, targetHeight) = when (targetResolution) {
            720 -> if (isLandscape) Pair(1280, 720) else Pair(720, 1280)
            480 -> if (isLandscape) Pair(854, 480) else Pair(480, 854)
            360 -> if (isLandscape) Pair(640, 360) else Pair(360, 640)
            else -> {
                // Fallback for any other resolution - calculate based on 16:9 aspect ratio
                val aspectRatio = if (isLandscape) 16.0/9.0 else 9.0/16.0
                if (isLandscape) {
                    Pair((targetResolution * aspectRatio).toInt(), targetResolution)
                } else {
                    Pair(targetResolution, (targetResolution / aspectRatio).toInt())
                }
            }
        }

        // Use COPY codec if source dimensions are <= target dimensions
        // This preserves original resolution and bitrate when source is already at or below target quality
        val shouldUseCopy = if (isLandscape) {
            width <= targetWidth && height <= targetHeight
        } else {
            width <= targetWidth && height <= targetHeight
        }

        Timber.tag(TAG).d("Video resolution check for ${targetResolution}p: source=${width}x${height}, target=${targetWidth}x${targetHeight}, isLandscape: $isLandscape, shouldUseCopy: $shouldUseCopy")
        return shouldUseCopy
    }

    /**
     * Build FFmpeg command for single resolution HLS conversion
     * This method will be called twice - once for 720p and once for 480p
     * Enhanced with better stream formatting to reduce PesReader errors
     */
    private fun buildSingleResolutionFFmpegCommand(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        bitrate: String,
        audioBitrate: String,
        useCopyPreset: Boolean = false,
        segmentsAtRoot: Boolean = false
    ): String {
        // Extract directory from outputPath to create absolute path for segments
        val outputDir = File(outputPath).parent ?: ""
        val segmentPath = "$outputDir/segment%03d.ts"
        
        return if (useCopyPreset) {
            // Use COPY codec - no re-encoding, just copy streams with improved formatting
            """
                -i "$inputPath" 
                -c copy 
                -fflags +genpts+igndts+flush_packets
                -avoid_negative_ts make_zero
                -max_interleave_delta 0
                -max_muxing_queue_size 1024
                -hls_time $HLS_SEGMENT_DURATION -hls_list_size $HLS_PLAYLIST_SIZE 
                -hls_flags independent_segments
                -hls_segment_type mpegts
                -hls_segment_filename "$segmentPath"
                -f hls "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
        } else {
            // Use normal conversion with scaling and encoding, enhanced for better stream compatibility
            // Added iOS/VideoJs compatibility: profile baseline, yuv420p pixel format, keyframe interval, level
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=$width:$height:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -b:v $bitrate
                -b:a $audioBitrate
                -preset veryfast
                -tune zerolatency
                -profile:v baseline
                -pix_fmt yuv420p
                -g 30
                -level 3.1
                -threads 4
                -max_muxing_queue_size 1024
                -fflags +genpts+igndts+flush_packets
                -avoid_negative_ts make_zero
                -max_interleave_delta 0
                -bufsize $bitrate
                -maxrate $bitrate
                -metadata:s:v:0 rotate=0
                -hls_time $HLS_SEGMENT_DURATION -hls_list_size $HLS_PLAYLIST_SIZE 
                -hls_flags independent_segments
                -hls_segment_type mpegts
                -hls_segment_filename "$segmentPath"
                -f hls "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
        }
    }

    /**
     * Create master playlist file for dual variant (720p + 480p)
     * Matches iOS behavior with fixed 480p lower variant
     */
    private fun createMasterPlaylist(
        outputDir: File, 
        width720: Int, 
        height720: Int, 
        width480: Int, 
        height480: Int,
        resolution720pBitrate: String,
        resolution480pBitrate: String
    ) {
        // Convert bitrate strings (e.g., "1000k") to bandwidth integers (e.g., 1000000)
        val bandwidth720p = resolution720pBitrate.replace("k", "").toIntOrNull() ?: 1000
        val bandwidth480p = resolution480pBitrate.replace("k", "").toIntOrNull() ?: 500
        
        // Create master.m3u8 with dual variant structure
        val masterPlaylistContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=${bandwidth720p * 1000},RESOLUTION=${width720}x${height720}
            720p/playlist.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=${bandwidth480p * 1000},RESOLUTION=${width480}x${height480}
            480p/playlist.m3u8
        """.trimIndent()

        val masterFile = File(outputDir, "master.m3u8")
        masterFile.writeText(masterPlaylistContent)
        
        Timber.tag(TAG).d("Created dual variant master playlist: ${masterFile.absolutePath}")
        Timber.tag(TAG).d("720p: ${width720}x${height720} @ ${resolution720pBitrate}, 480p: ${width480}x${height480} @ ${resolution480pBitrate}")
    }

    /**
     * Create single variant master playlist (480p only)
     * For single variant, playlist.m3u8 is at root level (not in subdirectory)
     * Matches iOS behavior
     */
    private fun createSingleVariantMasterPlaylist(
        outputDir: File,
        width480: Int,
        height480: Int,
        resolution480pBitrate: String
    ) {
        // Convert bitrate string (e.g., "500k") to bandwidth integer (e.g., 500000)
        val bandwidth480p = resolution480pBitrate.replace("k", "").toIntOrNull() ?: 500
        
        // Create master.m3u8 pointing to root-level playlist.m3u8
        // Matches iOS behavior: master.m3u8 and playlist.m3u8 both at root
        val masterPlaylistContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=${bandwidth480p * 1000},RESOLUTION=${width480}x${height480}
            playlist.m3u8
        """.trimIndent()

        val masterFile = File(outputDir, "master.m3u8")
        masterFile.writeText(masterPlaylistContent)
        
        Timber.tag(TAG).d("Created single variant master playlist: ${masterFile.absolutePath}")
        Timber.tag(TAG).d("480p: ${width480}x${height480} @ ${resolution480pBitrate}")
    }

    /**
     * Copy URI content to file
     */
    private fun copyUriToFile(uri: Uri, outputFile: File) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw Exception("Could not open input stream from URI: $uri")
    }

    /**
     * Result of HLS conversion
     */
    sealed class HLSConversionResult {
        data class Success(val outputDirectory: File) : HLSConversionResult()
        data class Error(val message: String) : HLSConversionResult()
    }
}
