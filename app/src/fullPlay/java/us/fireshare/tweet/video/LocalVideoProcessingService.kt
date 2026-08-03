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
     * Process video locally: normalize first, then convert to HLS, compress, and upload.
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
            val originalFileSize = calculateFileSize(uri)
            val sourceBitrateK = VideoManager.getVideoBitrate(context, uri)?.let { it / 1000 }
            val videoResolution = VideoManager.getVideoResolution(context, uri)
            val resolutionValue = VideoManager.getVideoResolutionValue(videoResolution)
            val needsResampling = (resolutionValue ?: 0) > 720
            val tempDir = createTempDirectory()

            try {
                val normalizedFile = File(tempDir, "normalized_${fileName}.mp4")
                val normalizer = VideoNormalizer(context)

                when (val result = normalizer.normalizeVideo(uri, normalizedFile, needsResampling)) {
                    is VideoNormalizer.NormalizationResult.Success -> {
                        val normalizedUri = Uri.fromFile(result.outputFile)
                        val normalizedResolution = VideoManager.getVideoResolutionValue(
                            VideoManager.getVideoResolution(context, normalizedUri)
                        )

                        processNormalizedVideo(
                            uri = normalizedUri,
                            fileName = fileName,
                            fileTimestamp = fileTimestamp,
                            referenceId = referenceId,
                            originalFileSize = originalFileSize,
                            normalizedSize = result.outputFile.length(),
                            normalizedResolution = normalizedResolution,
                            sourceBitrateK = sourceBitrateK
                        )
                    }
                    is VideoNormalizer.NormalizationResult.Error -> {
                        Timber.tag(TAG).w("Video normalization failed before HLS: ${result.message}")
                        VideoProcessingResult.Error("Normalization failed: ${result.message}")
                    }
                }
            } finally {
                cleanupTempDirectory(tempDir)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during video processing")
            VideoProcessingResult.Error("Processing error: ${e.message}")
        }
    }

    private fun calculateFileSize(uri: Uri): Long {
        return try {
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
            Timber.tag(TAG).e(e, "Failed to calculate video file size")
            0L
        }
    }

    /**
     * Convert an already-normalized MP4 to HLS, compress it, and upload it.
     * This mirrors the iOS flow where the normalized MP4 is reused for the HLS pass.
     */
    suspend fun processNormalizedVideo(
        uri: Uri,
        fileName: String,
        fileTimestamp: Long,
        referenceId: MimeiId?,
        originalFileSize: Long,
        normalizedSize: Long,
        normalizedResolution: Int?,
        sourceBitrateK: Int?
    ): VideoProcessingResult = withContext(Dispatchers.IO) {
        try {
            val tempDir = createTempDirectory()

            try {
                Timber.tag(TAG).d("Converting normalized MP4 to HLS; size=${normalizedSize / (1024 * 1024)}MB")

                val hlsResult = hlsConverter.convertToHLS(
                    uri,
                    tempDir,
                    fileName,
                    normalizedSize,
                    true,
                    normalizedResolution,
                    sourceBitrateK
                )

                when (hlsResult) {
                    is LocalHLSConverter.HLSConversionResult.Success -> {
                        val zipFile = File(tempDir.parent, "${fileName}_hls.zip")
                        val zipResult = zipCompressor.compressHLSDirectory(hlsResult.outputDirectory, zipFile)

                        when (zipResult) {
                            is ZipCompressor.ZipCompressionResult.Success -> {
                                try {
                                    Timber.tag(TAG).d("Cleaning up HLS directory after ZIP creation: ${hlsResult.outputDirectory.absolutePath}")
                                    hlsResult.outputDirectory.deleteRecursively()
                                } catch (e: Exception) {
                                    Timber.tag(TAG).w("Failed to clean up HLS directory: ${e.message}")
                                }

                                val processingResult = zipUploadService.uploadZipFile(
                                    zipResult.zipFile,
                                    fileName,
                                    referenceId
                                )

                                when (processingResult) {
                                    is ZipUploadService.ZipProcessingResult.Success -> {
                                        try {
                                            Timber.tag(TAG).d("Cleaning up ZIP file after successful processing: ${zipResult.zipFile.absolutePath}")
                                            zipResult.zipFile.delete()
                                        } catch (e: Exception) {
                                            Timber.tag(TAG).w("Failed to clean up ZIP file: ${e.message}")
                                        }

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
                cleanupTempDirectory(tempDir)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during normalized video HLS processing")
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
