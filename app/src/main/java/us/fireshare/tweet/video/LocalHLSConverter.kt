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
        private const val HLS_PLAYLIST_SIZE = 3 // Keep 3 segments in playlist
    }

    /**
     * Convert video to HLS format with multiple resolutions
     * @param inputUri Input video URI
     * @param outputDir Output directory for HLS files
     * @param fileName Original filename (without extension)
     * @return Result containing the output directory path and success status
     */
    suspend fun convertToHLS(
        inputUri: Uri,
        outputDir: File,
        fileName: String
    ): HLSConversionResult = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).d("Starting multi-resolution HLS conversion for: $fileName")
            
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
            val dir720 = File(outputDir, "720")
            val dir480 = File(outputDir, "480")
            dir720.mkdirs()
            dir480.mkdirs()

            // Create master playlist and individual resolution playlists in their folders
            val masterPlaylistPath = File(outputDir, "master.m3u8").absolutePath
            val playlist720Path = File(dir720, "playlist.m3u8").absolutePath
            val playlist480Path = File(dir480, "playlist.m3u8").absolutePath

            // Calculate proper 720p dimensions based on aspect ratio
            val (width720, height720) = calculateActualResolution(720, videoResolution)
            
            // Execute FFmpeg command for 720p with fallback
            val success720 = executeFFmpegWithFallback(
                inputPath = tempInputFile.absolutePath,
                outputPath = playlist720Path,
                width = width720,
                height = height720,
                bitrate = "1000k",
                audioBitrate = "128k",
                shouldUseCopyCodec = shouldUseCopyCodec,
                resolution = "720p"
            )

            if (!success720) {
                tempInputFile.delete()
                return@withContext HLSConversionResult.Error("FFmpeg 720p conversion failed with both COPY and libx264")
            }

            // Calculate proper 480p dimensions based on aspect ratio
            val (width480, height480) = calculateActualResolution(480, videoResolution)
            
            // Execute FFmpeg command for 480p with fallback
            val success480 = executeFFmpegWithFallback(
                inputPath = tempInputFile.absolutePath,
                outputPath = playlist480Path,
                width = width480,
                height = height480,
                bitrate = "500k",
                audioBitrate = "96k",
                shouldUseCopyCodec = shouldUseCopyCodec,
                resolution = "480p"
            )

            if (!success480) {
                tempInputFile.delete()
                return@withContext HLSConversionResult.Error("FFmpeg 480p conversion failed with both COPY and libx264")
            }

            // Clean up temporary input file
            tempInputFile.delete()

            // Both conversions succeeded, create master playlist
            Timber.tag(TAG).d("Multi-resolution HLS conversion completed successfully")
            
            // Create master playlist manually to ensure proper structure
            createMasterPlaylist(outputDir, width720, height720, width480, height480)
            
            // Verify that required files were created
            val masterFile = File(masterPlaylistPath)
            val playlist720File = File(playlist720Path)
            val playlist480File = File(playlist480Path)
            
            if (masterFile.exists() && playlist720File.exists() && playlist480File.exists()) {
                Timber.tag(TAG).d("HLS conversion successful - all required files created")
                Timber.tag(TAG).d("Master playlist: ${masterFile.absolutePath}")
                Timber.tag(TAG).d("720p playlist: ${playlist720File.absolutePath}")
                Timber.tag(TAG).d("480p playlist: ${playlist480File.absolutePath}")
                HLSConversionResult.Success(outputDir)
            } else {
                Timber.tag(TAG).e("Required HLS files not created")
                Timber.tag(TAG).e("Master exists: ${masterFile.exists()}")
                Timber.tag(TAG).e("720p playlist exists: ${playlist720File.exists()}")
                Timber.tag(TAG).e("480p playlist exists: ${playlist480File.exists()}")
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
            return if (targetResolution == 720) Pair(1280, 720) else Pair(854, 480)
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
                -hls_flags delete_segments+independent_segments+split_by_time
                -hls_segment_type mpegts
                -hls_segment_filename "%03d.ts"
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
                -preset fast
                -tune zerolatency
                -threads 2
                -max_muxing_queue_size 1024
                -fflags +genpts+igndts+flush_packets
                -avoid_negative_ts make_zero
                -max_interleave_delta 0
                -bufsize $bitrate
                -maxrate $bitrate
                -metadata:s:v:0 rotate=0
                -hls_time $HLS_SEGMENT_DURATION -hls_list_size $HLS_PLAYLIST_SIZE 
                -hls_flags delete_segments+independent_segments+split_by_time
                -hls_segment_type mpegts
                -hls_segment_filename "%03d.ts"
                -f hls "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
        }
    }

    /**
     * Create master playlist file for videoPreview compatibility
     * videoPreview expects master.m3u8 in root with folder-based structure
     */
    private fun createMasterPlaylist(outputDir: File, width720: Int, height720: Int, width480: Int, height480: Int) {
        // Create master.m3u8 with calculated resolution references
        val masterPlaylistContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=1128000,RESOLUTION=${width720}x${height720}
            720/playlist.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=596000,RESOLUTION=${width480}x${height480}
            480/playlist.m3u8
        """.trimIndent()

        val masterFile = File(outputDir, "master.m3u8")
        masterFile.writeText(masterPlaylistContent)
        
        Timber.tag(TAG).d("Created master playlist: ${masterFile.absolutePath}")
        Timber.tag(TAG).d("720p resolution: ${width720}x${height720}, 480p resolution: ${width480}x${height480}")
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
