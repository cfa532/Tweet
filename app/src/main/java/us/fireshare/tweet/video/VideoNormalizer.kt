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
 * Video normalizer for converting videos to standard MP4 format
 * Can resample to 720p if resolution is higher than 720p
 */
class VideoNormalizer(private val context: Context) {

    companion object {
        private const val TAG = "VideoNormalizer"
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
            // Get video resolution
            val videoResolution = VideoManager.getVideoResolution(context, inputUri)
            
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
                
                // Execute FFmpeg command
                val session = FFmpegKit.execute(ffmpegCommand)
                val returnCode = session.returnCode
                
                if (ReturnCode.isSuccess(returnCode)) {
                    NormalizationResult.Success(outputFile)
                } else {
                    val output = session.output
                    Timber.tag(TAG).e("Video normalization failed with return code: $returnCode, output: $output")
                    NormalizationResult.Error("FFmpeg failed with return code: $returnCode")
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
            
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -vf "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease:force_divisible_by=2" 
                -preset fast
                -crf 23
                -movflags +faststart
                -metadata:s:v:0 rotate=0
                "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
        } else {
            // Just normalize to standard MP4 without resampling
            """
                -i "$inputPath" 
                -c:v libx264
                -c:a aac
                -preset fast
                -crf 23
                -movflags +faststart
                -metadata:s:v:0 rotate=0
                "$outputPath"
            """.trimIndent().replace(Regex("\\s+"), " ")
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

