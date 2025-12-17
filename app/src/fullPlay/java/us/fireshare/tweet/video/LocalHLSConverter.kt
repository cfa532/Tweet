package us.fireshare.tweet.video

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.widget.VideoManager
import java.io.File
import java.io.FileOutputStream

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
    }

    /**
     * Convert video to HLS format with multiple resolutions
     * @param inputUri Input video URI
     * @param outputDir Output directory for HLS files
     * @param fileName Original filename (without extension)
     * @param fileSizeBytes File size in bytes for determining resolution/bitrate configuration
     * @return Result containing the output directory path and success status
     */
    suspend fun convertToHLS(
        inputUri: Uri,
        outputDir: File,
        fileName: String,
        fileSizeBytes: Long
    ): HLSConversionResult = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).d("Starting multi-resolution HLS conversion for: $fileName")
            
            // Determine configuration based on file size
            val fileSizeMB = fileSizeBytes / (1024.0 * 1024.0)
            val sizeThreshold256MB = 256.0
            
            val resolution720pBitrate: String
            val lowerResolution: Int
            val lowerResolutionBitrate: String
            
            if (fileSizeMB >= sizeThreshold256MB) {
                // >= 256MB: 720p (1500kb) + 360p (750kb)
                resolution720pBitrate = "1500k"
                lowerResolution = 360
                lowerResolutionBitrate = "750k"
                Timber.tag(TAG).d("File size ${String.format("%.1f", fileSizeMB)}MB >= 256MB, using 720p (1500k) + 360p (750k)")
            } else {
                // < 256MB: 720p (1500kb) + 480p (1000kb)
                resolution720pBitrate = "1500k"
                lowerResolution = 480
                lowerResolutionBitrate = "1000k"
                Timber.tag(TAG).d("File size ${String.format("%.1f", fileSizeMB)}MB < 256MB, using 720p (1500k) + ${lowerResolution}p (${lowerResolutionBitrate})")
            }
            
            // Check video resolution to determine if we should use COPY codec
            val videoResolution = VideoManager.getVideoResolution(context, inputUri)
            val shouldUseCopyCodec = shouldUseCopyCodec(videoResolution)
            
            Timber.tag(TAG).d("Video resolution: $videoResolution, using COPY codec: $shouldUseCopyCodec")
            
            // Ensure output directory exists
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            // Copy input file to a temporary location for FFmpeg processing
            val tempInputFile = File(outputDir, "temp_input.mp4")
            copyUriToFile(inputUri, tempInputFile)

            // Create resolution-specific directories
            val dir720 = File(outputDir, "720p")
            val lowerResDir = File(outputDir, "${lowerResolution}p")
            dir720.mkdirs()
            lowerResDir.mkdirs()

            // Create master playlist and individual resolution playlists in their folders
            val masterPlaylistPath = File(outputDir, "master.m3u8").absolutePath
            val playlist720Path = File(dir720, "playlist.m3u8").absolutePath
            val lowerResPlaylistPath = File(lowerResDir, "playlist.m3u8").absolutePath

            // Calculate proper 720p dimensions based on aspect ratio
            val (width720, height720) = calculateActualResolution(720, videoResolution)
            
            // Execute FFmpeg command for 720p with fallback
            val success720 = executeFFmpegWithFallback(
                inputPath = tempInputFile.absolutePath,
                outputPath = playlist720Path,
                width = width720,
                height = height720,
                bitrate = resolution720pBitrate,
                audioBitrate = "128k",
                shouldUseCopyCodec = shouldUseCopyCodec,
                resolution = "720p"
            )

            if (!success720) {
                tempInputFile.delete()
                return@withContext HLSConversionResult.Error("FFmpeg 720p conversion failed with both COPY and libx264")
            }

            // Calculate proper lower resolution dimensions based on aspect ratio
            val (widthLower, heightLower) = calculateActualResolution(lowerResolution, videoResolution)
            
            // Execute FFmpeg command for lower resolution with fallback
            val successLower = executeFFmpegWithFallback(
                inputPath = tempInputFile.absolutePath,
                outputPath = lowerResPlaylistPath,
                width = widthLower,
                height = heightLower,
                bitrate = lowerResolutionBitrate,
                audioBitrate = "128k",
                shouldUseCopyCodec = shouldUseCopyCodec,
                resolution = "${lowerResolution}p"
            )

            if (!successLower) {
                tempInputFile.delete()
                return@withContext HLSConversionResult.Error("FFmpeg ${lowerResolution}p conversion failed with both COPY and libx264")
            }

            // Clean up temporary input file
            tempInputFile.delete()

            // Both conversions succeeded, create master playlist
            Timber.tag(TAG).d("Multi-resolution HLS conversion completed successfully")
            
            // Create master playlist manually to ensure proper structure
            createMasterPlaylist(
                outputDir, 
                width720, 
                height720, 
                widthLower, 
                heightLower,
                resolution720pBitrate,
                lowerResolution,
                lowerResolutionBitrate
            )
            
            // Verify that required files were created
            val masterFile = File(masterPlaylistPath)
            val playlist720File = File(playlist720Path)
            val lowerResPlaylistFile = File(lowerResPlaylistPath)
            
            if (masterFile.exists() && playlist720File.exists() && lowerResPlaylistFile.exists()) {
                Timber.tag(TAG).d("HLS conversion successful - all required files created")
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
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during HLS conversion")
            HLSConversionResult.Error("Conversion error: ${e.message}")
        }
    }

    /**
     * Execute FFmpeg command with fallback from COPY codec to libx264
     * If COPY codec fails, automatically retry with libx264 encoding
     */
    private fun executeFFmpegWithFallback(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        bitrate: String,
        audioBitrate: String,
        shouldUseCopyCodec: Boolean,
        resolution: String
    ): Boolean {
        // First, try with the recommended codec (COPY if applicable, libx264 otherwise)
        val firstCommand = buildSingleResolutionFFmpegCommand(
            inputPath = inputPath,
            outputPath = outputPath,
            width = width,
            height = height,
            bitrate = bitrate,
            audioBitrate = audioBitrate,
            useCopyPreset = shouldUseCopyCodec
        )

        Timber.tag(TAG).d("FFmpeg command for $resolution (first attempt): $firstCommand")

        val firstSession = FFmpegKit.execute(firstCommand)
        val firstReturnCode = firstSession.returnCode

        if (ReturnCode.isSuccess(firstReturnCode)) {
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
                useCopyPreset = false // Force libx264
            )

            Timber.tag(TAG).d("FFmpeg command for $resolution (fallback): $fallbackCommand")

            val fallbackSession = FFmpegKit.execute(fallbackCommand)
            val fallbackReturnCode = fallbackSession.returnCode

            if (ReturnCode.isSuccess(fallbackReturnCode)) {
                Timber.tag(TAG).d("FFmpeg $resolution conversion succeeded with libx264 fallback")
                return true
            } else {
                val logs = fallbackSession.allLogsAsString
                Timber.tag(TAG).e("FFmpeg $resolution conversion failed with libx264 fallback: $logs")
                return false
            }
        } else {
            // If libx264 failed, no fallback available
            val logs = firstSession.allLogsAsString
            Timber.tag(TAG).e("FFmpeg $resolution conversion failed with libx264: $logs")
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
     * Determine if COPY codec should be used based on video resolution
     * For landscape videos: check if width <= 1280 AND height <= 720 (720p resolution)
     * For portrait videos: check if width <= 720 AND height <= 1280 (720p resolution)
     */
    private fun shouldUseCopyCodec(videoResolution: Pair<Int, Int>?): Boolean {
        if (videoResolution == null) {
            Timber.tag(TAG).w("Video resolution is null, defaulting to normal conversion")
            return false
        }
        
        val (width, height) = videoResolution
        
        // Determine if video is landscape or portrait
        val isLandscape = width > height
        
        // Use COPY codec only if both dimensions are <= 720p resolution
        // For landscape: width <= 1280 (720p width), height <= 720 (720p height)
        // For portrait: width <= 720 (720p width), height <= 1280 (720p height)
        val shouldUseCopy = if (isLandscape) {
            width <= 1280 && height <= 720
        } else {
            width <= 720 && height <= 1280
        }
        
        Timber.tag(TAG).d("Video resolution check: ${width}x${height}, isLandscape: $isLandscape, shouldUseCopy: $shouldUseCopy")
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
        useCopyPreset: Boolean = false
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
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=$width:$height:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -b:v $bitrate
                -b:a $audioBitrate
                -preset veryfast
                -tune zerolatency
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
