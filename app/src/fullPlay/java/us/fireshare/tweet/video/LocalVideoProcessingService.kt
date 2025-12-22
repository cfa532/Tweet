package us.fireshare.tweet.video

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.widget.VideoManager
import java.io.File
import java.io.FileOutputStream

/**
 * Main service that orchestrates local video processing:
 * 1. Convert video to HLS format using FFmpeg
 * 2. Compress HLS files into a zip archive
 * 3. Upload zip to /process-zip endpoint
 */
class LocalVideoProcessingService(
    private val context: Context,
    httpClient: HttpClient,
    appUser: User
) {

    companion object {
        private const val TAG = "LocalVideoProcessingService"
        private const val TEMP_DIR_PREFIX = "hls_conversion_"
        
        // Bitrate constants
        private const val REFERENCE_720P_BITRATE = 1000  // Base bitrate for 720p in kbps
        private const val REFERENCE_720P_PIXELS = 921600  // 1280 × 720 pixels
        private const val MIN_BITRATE = 500  // Minimum bitrate in kbps for quality
        
        // Normalization constants
        private const val NORMALIZATION_THRESHOLD = 720 // Videos >720p get normalized to 720p
        private const val NORMALIZATION_HIGH_BITRATE = "1500k" // For videos >720p
        private const val HLS_SIZE_THRESHOLD = 32 * 1024 * 1024L // 32MB in bytes
    }

    private val hlsConverter = LocalHLSConverter(context)
    private val zipCompressor = ZipCompressor()
    private val zipUploadService = ZipUploadService(context, httpClient, appUser)

    /**
     * Process video locally: normalize, route to progressive or HLS, compress, and upload
     * Matches iOS behavior: automatically decides between dual variant (720p + 480p) or single variant (480p)
     * 
     * @param uri Input video URI
     * @param fileName Original filename
     * @param fileTimestamp File timestamp
     * @param referenceId Reference ID
     * @return Result containing the processed file information
     */
    suspend fun processVideo(
        uri: Uri,
        fileName: String,
        fileTimestamp: Long,
        referenceId: MimeiId?
    ): VideoProcessingResult = withContext(Dispatchers.IO) {
            try {
                // Create temporary directory for processing
                val tempDir = createTempDirectory()
                
                // Calculate original file size
                val originalFileSize = withContext(Dispatchers.IO) {
                    try {
                        var size = 0L
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            val buffer = ByteArray(8192) // 8KB buffer
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                size += bytesRead
                            }
                        }
                        size
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to calculate video file size")
                        0L
                    }
                }
                
                Timber.tag(TAG).d("Original video size: ${originalFileSize / (1024 * 1024)}MB")
                
                try {
                    // Step 1: Normalize video
                    val normalizationResult = normalizeVideo(uri, fileName, tempDir)
                    
                    val processUri: Uri
                    val normalizedSize: Long
                    val isNormalized: Boolean
                    val normalizedResolution: Int?
                    
                    when (normalizationResult) {
                        is NormalizationResult.Success -> {
                            Timber.tag(TAG).d("Video normalized: ${normalizationResult.normalizedResolution}p @ ${normalizationResult.normalizedBitrate}, size=${normalizationResult.sizeBytes / (1024 * 1024)}MB")
                            processUri = normalizationResult.uri
                            normalizedSize = normalizationResult.sizeBytes
                            isNormalized = true
                            normalizedResolution = normalizationResult.normalizedResolution
                        }
                        is NormalizationResult.Skipped -> {
                            Timber.tag(TAG).d("Normalization skipped, using original video")
                            processUri = normalizationResult.originalUri
                            normalizedSize = originalFileSize
                            isNormalized = false
                            normalizedResolution = null
                        }
                        is NormalizationResult.Failed -> {
                            Timber.tag(TAG).w("Normalization failed, using original video")
                            processUri = normalizationResult.originalUri
                            normalizedSize = originalFileSize
                            isNormalized = false
                            normalizedResolution = null
                        }
                    }
                    
                    // Step 2: Route based on normalized size
                    if (normalizedSize <= HLS_SIZE_THRESHOLD) {
                        // Progressive route: video ≤32MB
                        Timber.tag(TAG).d("Routing to progressive video (size ≤32MB)")
                        // TODO: Implement progressive video upload
                        // For now, continue with HLS route
                        Timber.tag(TAG).w("Progressive route not yet implemented, continuing with HLS")
                    } else {
                        Timber.tag(TAG).d("Routing to HLS conversion (size >32MB)")
                    }
                    
                    // Step 3: Convert video to HLS format
                    // Variant selection (dual vs single) is automatically determined based on resolution
                    // Matches iOS logic: >480p → dual variant (720p + 480p), ≤480p → single variant (480p)
                    val hlsResult = hlsConverter.convertToHLS(
                        processUri, 
                        tempDir, 
                        fileName, 
                        normalizedSize, 
                        isNormalized,
                        normalizedResolution
                    )
                
                when (hlsResult) {
                    is LocalHLSConverter.HLSConversionResult.Success -> {
                        // Compress HLS files into zip
                        val zipFile = File(tempDir.parent, "${fileName}_hls.zip")
                        val zipResult = zipCompressor.compressHLSDirectory(hlsResult.outputDirectory, zipFile)
                        
                        when (zipResult) {
                            is ZipCompressor.ZipCompressionResult.Success -> {
                                // Clean up HLS directory immediately after ZIP creation to free disk space
                                try {
                                    Timber.tag(TAG).d("Cleaning up HLS directory after ZIP creation: ${hlsResult.outputDirectory.absolutePath}")
                                    hlsResult.outputDirectory.deleteRecursively()
                                } catch (e: Exception) {
                                    Timber.tag(TAG).w("Failed to clean up HLS directory: ${e.message}")
                                }

                                // Upload zip to /process-zip endpoint and poll for completion
                                val processingResult = zipUploadService.uploadZipFile(
                                    zipResult.zipFile,
                                    fileName,
                                    referenceId
                                )
                                
                                when (processingResult) {
                                    is ZipUploadService.ZipProcessingResult.Success -> {
                                        // Clean up ZIP file after successful processing to free disk space
                                        try {
                                            Timber.tag(TAG).d("Cleaning up ZIP file after successful processing: ${zipResult.zipFile.absolutePath}")
                                            zipResult.zipFile.delete()
                                        } catch (e: Exception) {
                                            Timber.tag(TAG).w("Failed to clean up ZIP file: ${e.message}")
                                        }

                                        // Get aspect ratio for the result
                                        val aspectRatio = VideoManager.getVideoAspectRatio(context, uri)

                                        Timber.tag(TAG).d("HLS video processed: ${processingResult.cid}")
                                        VideoProcessingResult.Success(
                                            MimeiFileType(
                                                processingResult.cid,
                                                MediaType.HLS_VIDEO,
                                                originalFileSize,
                                                fileName,
                                                fileTimestamp,
                                                aspectRatio
                                            )
                                        )
                                    }
                                    is ZipUploadService.ZipProcessingResult.Error -> {
                                        VideoProcessingResult.Error("Processing failed: ${processingResult.message}")
                                    }
                                }
                            }
                            is ZipCompressor.ZipCompressionResult.Error -> {
                                VideoProcessingResult.Error("Compression failed: ${zipResult.message}")
                            }
                        }
                    }
                    is LocalHLSConverter.HLSConversionResult.Error -> {
                        VideoProcessingResult.Error("HLS conversion failed: ${hlsResult.message}")
                    }
                }
            } finally {
                // Clean up temporary directory
                cleanupTempDirectory(tempDir)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during video processing")
            VideoProcessingResult.Error("Processing error: ${e.message}")
        }
    }

    /**
     * Create temporary directory for HLS conversion
     */
    private fun createTempDirectory(): File {
        val tempDir = File(context.cacheDir, "${TEMP_DIR_PREFIX}${System.currentTimeMillis()}")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return tempDir
    }

    /**
     * Clean up temporary directory and its contents
     */
    private fun cleanupTempDirectory(tempDir: File) {
        try {
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to clean up temporary directory")
        }
    }

    /**
     * Detect video resolution based on orientation
     * Landscape (width ≥ height): resolution = HEIGHT
     * Portrait (width < height): resolution = WIDTH
     */
    private fun detectResolution(videoResolution: Pair<Int, Int>?): Int? {
        if (videoResolution == null) return null
        val (width, height) = videoResolution
        return if (width >= height) {
            // Landscape: resolution = height
            height
        } else {
            // Portrait: resolution = width
            width
        }
    }

    /**
     * Calculate normalization parameters based on video resolution
     * Returns: Pair(targetResolution, targetBitrate)
     * Uses pixel-based proportional bitrate calculation for consistency
     */
    private fun calculateNormalizationParams(resolution: Int, videoResolution: Pair<Int, Int>?): Pair<Int, String> {
        return if (resolution > NORMALIZATION_THRESHOLD) {
            // Videos >720p: normalize to 720p @ 1500k bitrate
            Pair(NORMALIZATION_THRESHOLD, NORMALIZATION_HIGH_BITRATE)
        } else {
            // Videos ≤720p: normalize at original resolution @ proportional bitrate based on pixel count
            val proportionalBitrate = if (videoResolution != null) {
                val (width, height) = videoResolution
                val pixelCount = width * height
                // Pixel-based calculation: bitrate = (pixelCount / REFERENCE_720P_PIXELS) * REFERENCE_720P_BITRATE
                val calculatedBitrate = ((pixelCount.toDouble() / REFERENCE_720P_PIXELS) * REFERENCE_720P_BITRATE).toInt()
                maxOf(MIN_BITRATE, calculatedBitrate)
            } else {
                // Fallback to resolution-based if dimensions unknown
                maxOf(MIN_BITRATE, (REFERENCE_720P_BITRATE * resolution / 720))
            }
            Pair(resolution, "${proportionalBitrate}k")
        }
    }

    /**
     * Normalize video to target resolution and bitrate
     * Returns URI of normalized video file, or original URI if normalization fails
     */
    private suspend fun normalizeVideo(
        uri: Uri,
        fileName: String,
        tempDir: File
    ): NormalizationResult = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).d("========== FIRST NORMALIZATION (Standardization) ==========")
            
            // Get original video file size
            val originalFileSize = withContext(Dispatchers.IO) {
                try {
                    var size = 0L
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            size += bytesRead
                        }
                    }
                    size
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to calculate original file size")
                    0L
                }
            }
            
            Timber.tag(TAG).d("Original video: ${originalFileSize / (1024 * 1024)}MB")
            
            // Get video resolution
            val videoResolution = VideoManager.getVideoResolution(context, uri)
            val resolution = detectResolution(videoResolution)
            
            if (resolution == null) {
                Timber.tag(TAG).w("Could not detect video resolution, skipping normalization")
                return@withContext NormalizationResult.Skipped(uri)
            }
            
            Timber.tag(TAG).d("Detected resolution: ${resolution}p (${videoResolution?.first}x${videoResolution?.second})")
            
            // Calculate normalization parameters with pixel-based bitrate
            val (targetResolution, targetBitrate) = calculateNormalizationParams(resolution, videoResolution)
            
            Timber.tag(TAG).d("Standardization target: ${targetResolution}p @ $targetBitrate (pixel-based proportional bitrate)")
            
            // If already at target resolution, check if we need to normalize bitrate
            if (resolution <= NORMALIZATION_THRESHOLD && resolution == targetResolution) {
                // For videos ≤720p, we still normalize to ensure consistent bitrate and format
                // This is necessary for fair 32MB size comparison later
                Timber.tag(TAG).d("Video ≤720p at target resolution, standardizing format/bitrate for 32MB comparison")
            } else if (resolution > NORMALIZATION_THRESHOLD) {
                Timber.tag(TAG).d("Video >720p, downscaling to 720p for standardization")
            }
            
            // Create normalized video file
            val normalizedFile = File(tempDir, "normalized_${fileName}.mp4")
            
            // Copy input to temp file for processing
            val tempInputFile = File(tempDir, "input_${fileName}.mp4")
            copyUriToFile(uri, tempInputFile)
            
            // Calculate target dimensions maintaining aspect ratio
            val (targetWidth, targetHeight) = if (videoResolution != null) {
                val (width, height) = videoResolution
                val aspectRatio = width.toFloat() / height.toFloat()
                
                if (width >= height) {
                    // Landscape: target height = targetResolution
                    val w = (targetResolution * aspectRatio).toInt()
                    val evenW = if (w % 2 == 0) w else w - 1
                    Pair(evenW, targetResolution)
                } else {
                    // Portrait: target width = targetResolution
                    val h = (targetResolution / aspectRatio).toInt()
                    val evenH = if (h % 2 == 0) h else h - 1
                    Pair(targetResolution, evenH)
                }
            } else {
                Pair(1280, 720) // Default fallback
            }
            
            // Build FFmpeg normalization command
            val command = """
                -i "${tempInputFile.absolutePath}"
                -c:v libx264
                -c:a aac
                -vf "scale=${targetWidth}:${targetHeight}:force_original_aspect_ratio=decrease:force_divisible_by=2"
                -b:v $targetBitrate
                -b:a 128k
                -preset veryfast
                -profile:v baseline
                -pix_fmt yuv420p
                -movflags +faststart
                -y
                "${normalizedFile.absolutePath}"
            """.trimIndent().replace(Regex("\\s+"), " ")
            
            Timber.tag(TAG).d("Executing standardization: ${targetWidth}x${targetHeight} @ $targetBitrate")
            Timber.tag(TAG).d("FFmpeg command: $command")
            
            // Execute FFmpeg
            val startTime = System.currentTimeMillis()
            val session = FFmpegKit.execute(command)
            val returnCode = session.returnCode
            val duration = System.currentTimeMillis() - startTime
            
            // Clean up temp input file
            tempInputFile.delete()
            
            if (ReturnCode.isSuccess(returnCode)) {
                val normalizedSize = normalizedFile.length()
                val sizeDiff = normalizedSize - originalFileSize
                val sizeChangePercent = if (originalFileSize > 0) {
                    ((sizeDiff.toFloat() / originalFileSize) * 100).toInt()
                } else 0
                
                Timber.tag(TAG).d("FIRST NORMALIZATION SUCCESS:")
                Timber.tag(TAG).d("  Original: ${originalFileSize / (1024 * 1024)}MB")
                Timber.tag(TAG).d("  Normalized: ${normalizedSize / (1024 * 1024)}MB ($sizeChangePercent%)")
                Timber.tag(TAG).d("  Resolution: ${targetWidth}x${targetHeight} (${targetResolution}p)")
                Timber.tag(TAG).d("  Bitrate: $targetBitrate (standardized)")
                Timber.tag(TAG).d("  Duration: ${duration}ms")
                Timber.tag(TAG).d("  Purpose: Unified format for 32MB routing decision")
                Timber.tag(TAG).d("========================================================")
                
                NormalizationResult.Success(
                    uri = Uri.fromFile(normalizedFile),
                    file = normalizedFile,
                    sizeBytes = normalizedSize,
                    normalizedResolution = targetResolution,
                    normalizedBitrate = targetBitrate
                )
            } else {
                val logs = session.allLogsAsString
                Timber.tag(TAG).e("Normalization failed: $logs")
                // Clean up failed normalized file
                normalizedFile.delete()
                NormalizationResult.Failed(uri)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during video normalization")
            NormalizationResult.Failed(uri)
        }
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
     * Result of video normalization
     */
    private sealed class NormalizationResult {
        data class Success(
            val uri: Uri,
            val file: File,
            val sizeBytes: Long,
            val normalizedResolution: Int,
            val normalizedBitrate: String
        ) : NormalizationResult()
        data class Skipped(val originalUri: Uri) : NormalizationResult()
        data class Failed(val originalUri: Uri) : NormalizationResult()
    }

    /**
     * Result of video processing
     */
    sealed class VideoProcessingResult {
        data class Success(val mimeiFile: MimeiFileType) : VideoProcessingResult()
        data class Error(val message: String) : VideoProcessingResult()
    }
}
