package us.fireshare.tweet.video

import android.content.Context
import android.net.Uri
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
    }

    private val hlsConverter = LocalHLSConverter(context)
    private val zipCompressor = ZipCompressor()
    private val zipUploadService = ZipUploadService(context, httpClient, appUser)

    /**
     * Process video locally: convert to HLS, compress, and upload
     * @param uri Input video URI
     * @param fileName Original filename
     * @param fileTimestamp File timestamp
     * @param referenceId Reference ID
     * @param useRoute2 If true, use HLS route 2 (720p + 360p), otherwise use route 1 (720p + 480p)
     * @param isNormalized If true, the input video is already normalized to 720p/1000k
     * @param shouldCreateDualVariant If true, create dual variant (720p + 480p), otherwise single variant (480p only)
     * @return Result containing the processed file information
     */
    suspend fun processVideo(
        uri: Uri,
        fileName: String,
        fileTimestamp: Long,
        referenceId: MimeiId?,
        useRoute2: Boolean = false,
        isNormalized: Boolean = false,
        shouldCreateDualVariant: Boolean = true
    ): VideoProcessingResult = withContext(Dispatchers.IO) {
            try {
                // Create temporary directory for HLS conversion
                val tempDir = createTempDirectory()
                
                // Calculate file size before conversion
                val fileSize = withContext(Dispatchers.IO) {
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
                
                try {
                    // Convert video to HLS format
                    val hlsResult = hlsConverter.convertToHLS(uri, tempDir, fileName, fileSize, useRoute2, isNormalized, shouldCreateDualVariant)
                
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
                                                fileSize,
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
     * Result of video processing
     */
    sealed class VideoProcessingResult {
        data class Success(val mimeiFile: MimeiFileType) : VideoProcessingResult()
        data class Error(val message: String) : VideoProcessingResult()
    }
}
