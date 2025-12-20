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
     * @param inputUri Input video URI
     * @param outputDir Output directory for HLS files
     * @param fileName Original filename (without extension)
     * @param fileSizeBytes File size in bytes for determining resolution/bitrate configuration
     * @param useRoute2 If true, use HLS route 2 (720p + 360p), otherwise use route 1 (720p + 480p)
     * @param isNormalized If true, the input video is already normalized to 720p/1000k, use COPY codec for 720p
     * @param shouldCreateDualVariant If true, create dual variant (720p + 480p), otherwise single variant (480p only)
     * @return Result containing the output directory path and success status
     */
    suspend fun convertToHLS(
        inputUri: Uri,
        outputDir: File,
        fileName: String,
        fileSizeBytes: Long,
        useRoute2: Boolean = false,
        isNormalized: Boolean = false,
        shouldCreateDualVariant: Boolean = true
    ): HLSConversionResult = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).d("Starting multi-resolution HLS conversion for: $fileName")
            
            // Use standard configuration for all video sizes
            val fileSizeMB = fileSizeBytes / (1024.0 * 1024.0)

            // Fixed bitrates (always calculated, never detected)
            val resolution720pBitrate = "1000k"
            val (lowerResolution, lowerResolutionBitrate) = if (useRoute2) {
                Pair(360, "500k")  // 360p: 500k
            } else {
                Pair(480, "600k")  // 480p: 600k (fixed, not 750k)
            }

            Timber.tag(TAG).d("File size ${String.format(Locale.US, "%.1f", fileSizeMB)}MB, using HLS route ${if (useRoute2) "2" else "1"}: 720p (1000k) + ${lowerResolution}p (${lowerResolutionBitrate})")
            
            // Check video resolution, duration for timeout calculation
            val videoResolution = VideoManager.getVideoResolution(context, inputUri)
            val videoResolutionValue = VideoManager.getVideoResolutionValue(videoResolution)
            val videoDurationMs = VideoManager.getVideoDuration(context, inputUri)

            // Calculate dynamic timeout based on video duration and file size
            val dynamicTimeoutMs = calculateDynamicTimeout(videoDurationMs, fileSizeBytes)

            Timber.tag(TAG).d("Video resolution: $videoResolution (${videoResolutionValue}p), duration: ${videoDurationMs}ms")
            Timber.tag(TAG).d("Dynamic timeout set to: ${dynamicTimeoutMs}ms (${dynamicTimeoutMs / 1000 / 60} minutes)")
            
            // Determine if we should create 720p variant:
            // - If shouldCreateDualVariant is true and source resolution > lowerResolution: create 720p
            // - Otherwise: skip 720p (single variant only)
            val shouldCreate720p = shouldCreateDualVariant && videoResolutionValue != null && videoResolutionValue > lowerResolution
            
            if (!shouldCreate720p) {
                Timber.tag(TAG).d("Source resolution (${videoResolution?.first}x${videoResolution?.second}, ${videoResolutionValue}p) is not higher than ${lowerResolution}p or dual variant disabled, skipping 720p creation")
            }
            
            // Ensure output directory exists
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            // Copy input file to a temporary location for FFmpeg processing
            val tempInputFile = File(outputDir, "temp_input.mp4")
            copyUriToFile(inputUri, tempInputFile)

            // For single resolution: put playlist and segments at root level
            // For dual resolution: use subdirectories with master playlist
            val masterPlaylistPath = if (shouldCreateDualVariant) {
                File(outputDir, "master.m3u8").absolutePath
            } else {
                null // No master playlist for single resolution
            }
            
            val (playlist720Path, lowerResPlaylistPath) = if (shouldCreateDualVariant) {
                // Dual variant: use subdirectories
                val dir720 = File(outputDir, "720p")
                val lowerResDir = File(outputDir, "${lowerResolution}p")
                dir720.mkdirs()
                lowerResDir.mkdirs()
                Pair(
                    File(dir720, "playlist.m3u8").absolutePath,
                    File(lowerResDir, "playlist.m3u8").absolutePath
                )
            } else {
                // Single resolution: playlist at root level, no 720p directory
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
                val (targetWidth720, targetHeight720) = calculateActualResolution(720, videoResolution)
                
                // Never increase resolution: if source is lower than 720p, keep original resolution
                // Use resolution value (HEIGHT for landscape, WIDTH for portrait) for comparison
                val (w720, h720) = videoResolution?.let { (width, height) ->
                    val resolutionValue = VideoManager.getVideoResolutionValue(videoResolution) ?: 0
                    
                    if (resolutionValue < 720) {
                        // Source is lower than 720p, keep original resolution (no upscaling)
                        Timber.tag(TAG).d("Source resolution ${width}x${height} (${resolutionValue}p) is lower than 720p, keeping original resolution")
                        Pair(width, height)
                    } else {
                        // Source is >= 720p, use calculated 720p dimensions
                        Pair(targetWidth720, targetHeight720)
                    }
                } ?: Pair(targetWidth720, targetHeight720)
                
                finalWidth720 = w720
                finalHeight720 = h720
                
                // Use fixed bitrate (always 1000k for 720p)
                val target720pBitrate = resolution720pBitrate
                
                // For 720p HLS stream: Use COPY codec if video is already normalized to 720p/1000k
                // If not normalized, re-encode with libx264 to ensure iOS/VideoJs compatibility settings
                val shouldUseCopyFor720p = isNormalized && videoResolutionValue == 720
                
                if (shouldUseCopyFor720p) {
                    Timber.tag(TAG).d("720p HLS stream: Using COPY codec (video already normalized to 720p/1000k)")
                } else {
                    Timber.tag(TAG).d("720p HLS stream: source=${videoResolution} (${videoResolutionValue}p), target=${finalWidth720}x${finalHeight720}, re-encoding with libx264 for compatibility, bitrate=${target720pBitrate}")
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
            val (targetWidthLower, targetHeightLower) = calculateActualResolution(lowerResolution, videoResolution)
            
            // Never increase resolution: if source is lower than target, keep original resolution
            // Use resolution value (HEIGHT for landscape, WIDTH for portrait) for comparison
            val (finalWidthLower, finalHeightLower) = videoResolution?.let { (width, height) ->
                val resolutionValue = VideoManager.getVideoResolutionValue(videoResolution) ?: 0
                
                if (resolutionValue < lowerResolution) {
                    // Source is lower than target resolution, keep original resolution (no upscaling)
                    Timber.tag(TAG).d("Source resolution ${width}x${height} (${resolutionValue}p) is lower than ${lowerResolution}p, keeping original resolution")
                    Pair(width, height)
                } else {
                    // Source is >= target resolution, use calculated target dimensions
                    Pair(targetWidthLower, targetHeightLower)
                }
            } ?: Pair(targetWidthLower, targetHeightLower)
            
            // Use fixed bitrate (always calculated, never detected)
            val targetLowerBitrate = lowerResolutionBitrate
            
            // For lower resolution HLS stream: Always re-encode with libx264 (downscale from source)
            // This ensures iOS/VideoJs compatibility settings (baseline profile, yuv420p, etc.) are always applied
            val shouldUseCopyForLower = false
            
            Timber.tag(TAG).d("${lowerResolution}p HLS stream: source=${videoResolution} (${videoResolutionValue}p), target=${finalWidthLower}x${finalHeightLower}, re-encoding with libx264 (downscale), bitrate=${targetLowerBitrate}")
            
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
                    resolution = "${lowerResolution}p",
                    segmentsAtRoot = !shouldCreateDualVariant // For single resolution, segments go at root level
                )
            }

            if (!successLower) {
                tempInputFile.delete()
                return@withContext HLSConversionResult.Error("FFmpeg ${lowerResolution}p conversion failed with both COPY and libx264")
            }

            // Clean up temporary input file
            tempInputFile.delete()

            // Create master playlist only for dual variant
            if (shouldCreateDualVariant && shouldCreate720p) {
                Timber.tag(TAG).d("Dual-resolution HLS conversion completed successfully")
                createMasterPlaylist(
                    outputDir, 
                    finalWidth720, 
                    finalHeight720, 
                    finalWidthLower, 
                    finalHeightLower,
                    resolution720pBitrate,
                    lowerResolution,
                    targetLowerBitrate
                )
            } else {
                // Single resolution: no master playlist needed
                Timber.tag(TAG).d("Single-resolution HLS conversion completed successfully")
            }
            
            // Verify that required files were created
            val lowerResPlaylistFile = File(lowerResPlaylistPath)
            
            if (shouldCreateDualVariant && shouldCreate720p) {
                // Dual variant: verify master playlist, 720p playlist, and lower res playlist
                val masterFile = File(masterPlaylistPath!!)
                val playlist720File = File(playlist720Path)
                
                if (masterFile.exists() && playlist720File.exists() && lowerResPlaylistFile.exists()) {
                    Timber.tag(TAG).d("HLS conversion successful - all dual variant files created")
                    Timber.tag(TAG).d("Master playlist: ${masterFile.absolutePath}")
                    Timber.tag(TAG).d("720p playlist: ${playlist720File.absolutePath}")
                    Timber.tag(TAG).d("${lowerResolution}p playlist: ${lowerResPlaylistFile.absolutePath}")
                    HLSConversionResult.Success(outputDir)
                } else {
                    Timber.tag(TAG).e("Required HLS files not created")
                    Timber.tag(TAG).e("Master exists: ${masterFile.exists()}")
                    Timber.tag(TAG).e("720p playlist exists: ${playlist720File.exists()}")
                    Timber.tag(TAG).e("${lowerResolution}p playlist exists: ${lowerResPlaylistFile.exists()}")
                    HLSConversionResult.Error("Required HLS files not created")
                }
            } else {
                // Single resolution: only verify the single playlist at root level
                if (lowerResPlaylistFile.exists()) {
                    Timber.tag(TAG).d("HLS conversion successful - single resolution files created")
                    Timber.tag(TAG).d("Playlist: ${lowerResPlaylistFile.absolutePath}")
                    HLSConversionResult.Success(outputDir)
                } else {
                    Timber.tag(TAG).e("Required HLS files not created")
                    Timber.tag(TAG).e("Playlist exists: ${lowerResPlaylistFile.exists()}")
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
     * Create master playlist file for videoPreview compatibility
     * videoPreview expects master.m3u8 in root with folder-based structure
     */
    private fun createMasterPlaylist(
        outputDir: File, 
        width720: Int, 
        height720: Int, 
        widthLower: Int, 
        heightLower: Int,
        resolution720pBitrate: String,
        lowerResolution: Int,
        lowerResolutionBitrate: String
    ) {
        // Convert bitrate strings (e.g., "3000k") to bandwidth integers (e.g., 3000000)
        val bandwidth720p = resolution720pBitrate.replace("k", "").toIntOrNull() ?: 2000
        val bandwidthLower = lowerResolutionBitrate.replace("k", "").toIntOrNull() ?: 1000
        
        // Create master.m3u8 with calculated resolution references
        val masterPlaylistContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=${bandwidth720p * 1000},RESOLUTION=${width720}x${height720}
            720p/playlist.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=${bandwidthLower * 1000},RESOLUTION=${widthLower}x${heightLower}
            ${lowerResolution}p/playlist.m3u8
        """.trimIndent()

        val masterFile = File(outputDir, "master.m3u8")
        masterFile.writeText(masterPlaylistContent)
        
        Timber.tag(TAG).d("Created master playlist: ${masterFile.absolutePath}")
        Timber.tag(TAG).d("720p resolution: ${width720}x${height720} (${resolution720pBitrate}), ${lowerResolution}p resolution: ${widthLower}x${heightLower} (${lowerResolutionBitrate})")
    }

    /**
     * Create single-resolution master playlist (only lower resolution variant)
     */
    private fun createSingleResolutionMasterPlaylist(
        outputDir: File,
        widthLower: Int,
        heightLower: Int,
        lowerResolution: Int,
        lowerResolutionBitrate: String
    ) {
        // Convert bitrate string (e.g., "600k") to bandwidth integer (e.g., 600000)
        val bandwidthLower = lowerResolutionBitrate.replace("k", "").toIntOrNull() ?: 1000
        
        // Create master.m3u8 with single resolution
        val masterPlaylistContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=${bandwidthLower * 1000},RESOLUTION=${widthLower}x${heightLower}
            ${lowerResolution}p/playlist.m3u8
        """.trimIndent()

        val masterFile = File(outputDir, "master.m3u8")
        masterFile.writeText(masterPlaylistContent)
        
        Timber.tag(TAG).d("Created single-resolution master playlist: ${masterFile.absolutePath}")
        Timber.tag(TAG).d("${lowerResolution}p resolution: ${widthLower}x${heightLower} (${lowerResolutionBitrate})")
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
