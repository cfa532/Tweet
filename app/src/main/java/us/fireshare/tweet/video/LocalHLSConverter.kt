package us.fireshare.tweet.video

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
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

            // Execute FFmpeg command for 720p
            val ffmpegCommand720 = buildSingleResolutionFFmpegCommand(
                inputPath = tempInputFile.absolutePath,
                outputPath = playlist720Path,
                width = 1280,
                height = 720,
                bitrate = "1000k",
                audioBitrate = "128k"
            )

            Timber.tag(TAG).d("FFmpeg command for 720p: $ffmpegCommand720")

            val session720 = FFmpegKit.execute(ffmpegCommand720)
            val returnCode720 = session720.returnCode

            if (!ReturnCode.isSuccess(returnCode720)) {
                val logs = session720.allLogsAsString
                Timber.tag(TAG).e("FFmpeg 720p conversion failed: $logs")
                tempInputFile.delete()
                return@withContext HLSConversionResult.Error("FFmpeg 720p conversion failed: $logs")
            }

            // Execute FFmpeg command for 480p
            val ffmpegCommand480 = buildSingleResolutionFFmpegCommand(
                inputPath = tempInputFile.absolutePath,
                outputPath = playlist480Path,
                width = 854,
                height = 480,
                bitrate = "500k",
                audioBitrate = "96k"
            )

            Timber.tag(TAG).d("FFmpeg command for 480p: $ffmpegCommand480")

            val session480 = FFmpegKit.execute(ffmpegCommand480)
            val returnCode480 = session480.returnCode

            if (!ReturnCode.isSuccess(returnCode480)) {
                val logs = session480.allLogsAsString
                Timber.tag(TAG).e("FFmpeg 480p conversion failed: $logs")
                tempInputFile.delete()
                return@withContext HLSConversionResult.Error("FFmpeg 480p conversion failed: $logs")
            }

            // Clean up temporary input file
            tempInputFile.delete()

            // Both conversions succeeded, create master playlist
            Timber.tag(TAG).d("Multi-resolution HLS conversion completed successfully")
            
            // Create master playlist manually to ensure proper structure
            createMasterPlaylist(outputDir)
            
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
     * Build FFmpeg command for single resolution HLS conversion
     * This method will be called twice - once for 720p and once for 480p
     */
    private fun buildSingleResolutionFFmpegCommand(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        bitrate: String,
        audioBitrate: String
    ): String {
        return """
            -i "$inputPath" 
            -vf "scale=$width:$height:force_original_aspect_ratio=decrease:force_divisible_by=2" 
            -c:v libx264 -preset superfast -c:a aac -b:v $bitrate -b:a $audioBitrate 
            -hls_time $HLS_SEGMENT_DURATION -hls_list_size $HLS_PLAYLIST_SIZE 
            -hls_flags delete_segments 
            -f hls "$outputPath"
        """.trimIndent().replace(Regex("\\s+"), " ")
    }

    /**
     * Create master playlist file for videoPreview compatibility
     * videoPreview expects master.m3u8 in root with folder-based structure
     */
    private fun createMasterPlaylist(outputDir: File) {
        // Create master.m3u8 with folder-based resolution references
        val masterPlaylistContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=1128000,RESOLUTION=1280x720
            720/playlist.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=596000,RESOLUTION=854x480
            480/playlist.m3u8
        """.trimIndent()

        val masterFile = File(outputDir, "master.m3u8")
        masterFile.writeText(masterPlaylistContent)
        
        Timber.tag(TAG).d("Created master playlist: ${masterFile.absolutePath}")
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
