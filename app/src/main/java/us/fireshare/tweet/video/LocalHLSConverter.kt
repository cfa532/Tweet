package us.fireshare.tweet.video

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Local HLS converter using FFmpeg Kit
 * Converts uploaded videos to HLS format compatible with simplevideoplayer
 */
class LocalHLSConverter(private val context: Context) {

    companion object {
        private const val TAG = "LocalHLSConverter"
        private const val HLS_SEGMENT_DURATION = 10 // 10 seconds per segment
        private const val HLS_PLAYLIST_SIZE = 3 // Keep 3 segments in playlist
    }

    /**
     * Convert video to HLS format
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
            Timber.tag(TAG).d("Starting HLS conversion for: $fileName")
            
            // Ensure output directory exists
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            // Copy input file to a temporary location for FFmpeg processing
            val tempInputFile = File(outputDir, "temp_input.mp4")
            copyUriToFile(inputUri, tempInputFile)

            // Generate HLS files
            val masterPlaylistPath = File(outputDir, "master.m3u8").absolutePath
            val playlistPath = File(outputDir, "playlist.m3u8").absolutePath
            val segmentPattern = File(outputDir, "segment_%03d.ts").absolutePath

            // FFmpeg command to create HLS with multiple bitrates
            val ffmpegCommand = buildFFmpegCommand(
                inputPath = tempInputFile.absolutePath,
                masterPlaylistPath = masterPlaylistPath,
                playlistPath = playlistPath,
                segmentPattern = segmentPattern
            )

            Timber.tag(TAG).d("FFmpeg command: $ffmpegCommand")

            // Execute FFmpeg command
            val session = FFmpegKit.execute(ffmpegCommand)
            val returnCode = session.returnCode

            // Clean up temporary input file
            tempInputFile.delete()

            if (ReturnCode.isSuccess(returnCode)) {
                Timber.tag(TAG).d("HLS conversion completed successfully")
                
                // Verify that required files were created
                val masterFile = File(masterPlaylistPath)
                val playlistFile = File(playlistPath)
                
                if (masterFile.exists() && playlistFile.exists()) {
                    HLSConversionResult.Success(outputDir)
                } else {
                    Timber.tag(TAG).e("Required HLS files not created")
                    HLSConversionResult.Error("Required HLS files not created")
                }
            } else {
                val logs = session.allLogsAsString
                Timber.tag(TAG).e("FFmpeg conversion failed: $logs")
                HLSConversionResult.Error("FFmpeg conversion failed: $logs")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during HLS conversion")
            HLSConversionResult.Error("Conversion error: ${e.message}")
        }
    }

    /**
     * Build FFmpeg command for HLS conversion with multiple bitrates
     * Using mpeg4 encoder for 16KB build compatibility
     */
    private fun buildFFmpegCommand(
        inputPath: String,
        masterPlaylistPath: String,
        playlistPath: String,
        segmentPattern: String
    ): String {
        return """
            -i "$inputPath" 
            -c:v mpeg4 
            -c:a aac 
            -b:v 1000k 
            -b:a 128k 
            -vf "scale=1280:720" 
            -hls_time $HLS_SEGMENT_DURATION 
            -hls_list_size $HLS_PLAYLIST_SIZE 
            -hls_flags delete_segments 
            -f hls 
            "$playlistPath"
        """.trimIndent().replace(Regex("\\s+"), " ")
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
