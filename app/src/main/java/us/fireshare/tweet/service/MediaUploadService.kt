package us.fireshare.tweet.service

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.video.LocalVideoProcessingService
import us.fireshare.tweet.video.VideoNormalizer
import us.fireshare.tweet.widget.VideoManager
import java.io.File

/**
 * Service for handling media upload operations including:
 * - IPFS upload for various media types
 * - Video processing (HLS conversion or normalization)
 * - Image aspect ratio calculation
 * - File size calculation
 */
class MediaUploadService(
    private val context: Context,
    private val httpClient: HttpClient,
    private val appUser: User,
    private val appId: MimeiId
) {

    companion object {
        private const val TAG = "MediaUploadService"
    }

    /**
     * Upload media file to node and return its IPFS cid with its media type.
     * For videos, first tries to upload to net disk URL, then falls back to IPFS method.
     */
    @OptIn(UnstableApi::class)
    suspend fun uploadToIPFS(
        uri: Uri,
        referenceId: MimeiId? = null,
        noResample: Boolean = false
    ): MimeiFileType? {
        // Get file name
        var fileName: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG)
                .w("Failed to get file name from content resolver: ${e.message}")
            // Fallback: try to get filename from URI
            fileName = uri.lastPathSegment
        }

        // Get file timestamp
        val fileTimestamp: Long = try {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            documentFile?.lastModified()?.let {
                if (it == 0L) System.currentTimeMillis() else it
            } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to get file timestamp: ${e.message}")
            System.currentTimeMillis()
        }

        // Determine MediaType based on MIME type
        val mimeType = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to get MIME type: ${e.message}")
            null
        }

        val mediaType = when {
            mimeType?.startsWith("image/") == true -> MediaType.Image
            mimeType?.startsWith("video/") == true -> MediaType.Video
            mimeType?.startsWith("audio/") == true -> MediaType.Audio
            mimeType == "application/pdf" -> MediaType.PDF
            mimeType == "application/zip" || mimeType == "application/x-zip-compressed" || 
            mimeType == "application/x-rar-compressed" || mimeType == "application/x-7z-compressed" -> MediaType.Zip
            mimeType == "application/msword" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> MediaType.Word
            mimeType == "application/vnd.ms-excel" || mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> MediaType.Excel
            mimeType == "application/vnd.ms-powerpoint" || mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> MediaType.PPT
            mimeType?.startsWith("text/plain") == true -> MediaType.Txt
            mimeType?.startsWith("text/html") == true -> MediaType.Html
            else -> {
                // Fallback: try to determine type from file extension
                val extension = fileName?.substringAfterLast('.', "")?.lowercase()
                val extensionType = when (extension) {
                    "jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "tif" -> MediaType.Image
                    "mp4", "avi", "mov", "mkv", "webm", "m4v", "3gp" -> MediaType.Video
                    "mp3", "wav", "aac", "ogg", "flac", "m4a" -> MediaType.Audio
                    "pdf" -> MediaType.PDF
                    "zip", "rar", "7z" -> MediaType.Zip
                    "doc", "docx" -> MediaType.Word
                    "xls", "xlsx" -> MediaType.Excel
                    "ppt", "pptx" -> MediaType.PPT
                    "txt", "rtf", "csv" -> MediaType.Txt
                    "html", "htm" -> MediaType.Html
                    else -> MediaType.Unknown
                }
                
                // If extension detection failed, use FileTypeDetector for magic bytes detection
                if (extensionType == MediaType.Unknown) {
                    Timber.tag(TAG).d("Extension detection failed, using FileTypeDetector for magic bytes detection")
                    FileTypeDetector.detectFileType(context, uri, fileName)
                } else {
                    extensionType
                }
            }
        }

        // For video files, use local processing only
        if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) {
            Timber.tag(TAG).d("Detected video file, attempting local processing only")
            try {
                val localResult = processVideoLocally(uri, fileName, fileTimestamp, referenceId)
                if (localResult != null) {
                    Timber.tag(TAG).d("Video processed locally successfully: ${localResult.mid}")
                    return localResult
                } else {
                    Timber.tag(TAG).e("Local video processing failed - no fallback available")
                    return null
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e("Local video processing failed with exception: ${e.message}")
                return null
            }
        } else {
            Timber.tag(TAG).d("Non-video file, proceeding with IPFS upload")
        }

        // Fall back to original IPFS method for non-video files
        Timber.tag(TAG).d("Calling uploadToIPFSOriginal with mediaType: $mediaType")
        val result = uploadToIPFSOriginal(uri, fileName, fileTimestamp, referenceId, mediaType)
        if (result != null) {
            Timber.tag(TAG).d("uploadToIPFSOriginal succeeded: ${result.mid}")
        } else {
            Timber.tag(TAG).e("uploadToIPFSOriginal returned null")
        }
        Timber.tag(TAG).d("Returning result: ${result?.mid ?: "null"}")
        return result
    }

    /**
     * Check if conversion server is available at netDiskUrl
     */
    private suspend fun isConversionServerAvailable(): Boolean {
        return try {
            // First check if cloudDrivePort is valid
            if (appUser.cloudDrivePort == null) {
                Timber.tag(TAG).d("cloudDrivePort is not set")
                return false
            }
            
            val netDiskUrl = appUser.netDiskUrl
            if (netDiskUrl.isNullOrEmpty()) {
                Timber.tag(TAG).d("netDiskUrl is not available")
                return false
            }
            
            // Try to ping the /process-zip endpoint
            val healthCheckUrl = "$netDiskUrl/process-zip/health"
            Timber.tag(TAG).d("Checking server availability at: $healthCheckUrl")
            
            val response = withContext(Dispatchers.IO) {
                try {
                    httpClient.get(healthCheckUrl)
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Health check request failed: ${e.message}")
                    return@withContext null
                }
            }
            
            val isAvailable = response?.status == HttpStatusCode.OK
            Timber.tag(TAG).d("Server available: $isAvailable")
            isAvailable
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error checking conversion server: ${e.message}")
            false
        }
    }

    /**
     * Process video locally using FFmpeg Kit
     * Strategy:
     * 1. Check if cloudDrivePort is valid and conversion server is available
     * 2. If available: convert to HLS, compress, and upload to /process-zip
     * 3. If not available: normalize to mp4 (resample to 720p if > 720p) and upload to IPFS
     */
    private suspend fun processVideoLocally(
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?
    ): MimeiFileType? {
        return try {
            Timber.tag(TAG).d("Starting local video processing for: $fileName")
            
            // Check if conversion server is available
            val serverAvailable = isConversionServerAvailable()
            Timber.tag(TAG).d("Conversion server available: $serverAvailable")
            
            if (serverAvailable) {
                // Use HLS conversion and upload to process-zip endpoint
                Timber.tag(TAG).d("Using HLS conversion with process-zip endpoint")
                val localProcessingService = LocalVideoProcessingService(context, httpClient, appUser)
                val result = localProcessingService.processVideo(
                    uri = uri,
                    fileName = fileName ?: "video",
                    fileTimestamp = fileTimestamp,
                    referenceId = referenceId
                )
                
                when (result) {
                    is LocalVideoProcessingService.VideoProcessingResult.Success -> {
                        Timber.tag(TAG).d("Local processing successful: ${result.mimeiFile.mid}")
                        result.mimeiFile
                    }
                    is LocalVideoProcessingService.VideoProcessingResult.Error -> {
                        Timber.tag(TAG).e("Local processing failed: ${result.message}")
                        null
                    }
                }
            } else {
                // Normalize to mp4 and upload via IPFS
                Timber.tag(TAG).d("Conversion server not available, normalizing to mp4 and uploading via IPFS")
                
                // Check if video resolution is > 720p
                val videoResolution = VideoManager.getVideoResolution(context, uri)
                val needsResampling = if (videoResolution != null) {
                    val (width, height) = videoResolution
                    width > 1280 || height > 720
                } else {
                    false
                }
                
                Timber.tag(TAG).d("Video resolution: $videoResolution, needs resampling: $needsResampling")
                
                // Normalize video to mp4
                val normalizer = VideoNormalizer(context)
                val normalizedFile = File(context.cacheDir, "normalized_${System.currentTimeMillis()}.mp4")
                
                try {
                    val normalizationResult = normalizer.normalizeVideo(uri, normalizedFile, needsResampling)
                    
                    when (normalizationResult) {
                        is VideoNormalizer.NormalizationResult.Success -> {
                            Timber.tag(TAG).d("Video normalization successful")
                            
                            // Upload normalized video via IPFS
                            val normalizedUri = Uri.fromFile(normalizedFile)
                            val result = uploadToIPFSOriginal(
                                normalizedUri,
                                fileName,
                                fileTimestamp,
                                referenceId,
                                MediaType.Video
                            )
                            
                            Timber.tag(TAG).d("IPFS upload result: ${result?.mid}")
                            result
                        }
                        is VideoNormalizer.NormalizationResult.Error -> {
                            Timber.tag(TAG).e("Video normalization failed: ${normalizationResult.message}")
                            null
                        }
                    }
                } finally {
                    // Clean up normalized file
                    if (normalizedFile.exists()) {
                        normalizedFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in local video processing")
            null
        }
    }

    /**
     * Poll video conversion status until completion with retry logic for connection issues
     */
    private suspend fun pollVideoConversionStatus(
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        jobId: String,
        baseUrl: String
    ): MimeiFileType? {
        val statusURL = "$baseUrl/convert-video/status/$jobId"
        var lastProgress = 0
        var lastMessage = "Starting video processing..."
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 10 // Allow more failures for long processing
        val maxPollingTime = 2 * 60 * 60 * 1000L // 2 hours max polling time for very long videos
        val startTime = System.currentTimeMillis()
        
        Timber.tag(TAG).d("Starting to poll status for job: $jobId")

        while (true) {
            // Check if we've been polling too long
            if (System.currentTimeMillis() - startTime > maxPollingTime) {
                throw Exception(context.getString(R.string.error_video_processing_timeout, maxPollingTime / 1000 / 60))
            }

            try {
                val statusResponse = httpClient.get(statusURL)
                
                if (statusResponse.status == HttpStatusCode.NotFound) {
                    // Job ID not found - cancel immediately without retry
                    Timber.tag(TAG).e("Job ID not found: $jobId")
                    throw Exception(context.getString(R.string.error_job_id_not_found, jobId))
                }
                
                if (statusResponse.status != HttpStatusCode.OK) {
                    throw Exception(context.getString(R.string.error_status_check_failed, statusResponse.status.toString()))
                }

                val statusResponseText = statusResponse.bodyAsText()
                val statusData = Gson().fromJson(statusResponseText, Map::class.java)
                
                val success = statusData?.get("success") as? Boolean
                if (success != true) {
                    val errorMessage = statusData?.get("message") as? String ?: "Status check failed"
                    // Check if the error message indicates job not found
                    if (errorMessage.contains("not found", ignoreCase = true) || 
                        errorMessage.contains("job not found", ignoreCase = true)) {
                        Timber.tag(TAG).e("Job ID not found in response: $jobId")
                        throw Exception(context.getString(R.string.error_job_id_not_found, jobId))
                    }
                    throw Exception(errorMessage)
                }

                val status = statusData["status"] as? String
                val progress = (statusData["progress"] as? Number)?.toInt() ?: 0
                val message = statusData["message"] as? String ?: "Processing..."

                // Reset failure counter on successful request
                consecutiveFailures = 0

                // Log progress updates
                if (progress != lastProgress || message != lastMessage) {
                    Timber.tag(TAG).d("Progress: $progress% - $message")
                    lastProgress = progress
                    lastMessage = message
                }

                when (status) {
                    "completed" -> {
                        val cid = statusData["cid"] as? String
                            ?: throw Exception(context.getString(R.string.error_no_cid_in_response))
                        
                        @OptIn(UnstableApi::class)
                        val aspectRatio = VideoManager.getVideoAspectRatio(context, uri)
                        
                        // Calculate file size from the original URI
                        val fileSize = getFileSize(uri) ?: 0L
                        Timber.tag(TAG).d("Video file size calculated: $fileSize bytes for URI: $uri")

                        Timber.tag(TAG).d("Video conversion completed successfully: $cid")
                        return MimeiFileType(
                            cid,
                            MediaType.HLS_VIDEO,
                            fileSize,
                            fileName,
                            fileTimestamp,
                            aspectRatio
                        )
                    }
                    "failed" -> {
                        val errorMessage = statusData["message"] as? String ?: context.getString(R.string.error_video_conversion_failed)
                        throw Exception(errorMessage)
                    }
                    "uploading", "processing" -> {
                        // Continue polling
                        kotlinx.coroutines.delay(3000) // Poll every 3 seconds
                    }
                    else -> {
                        // Unknown status, continue polling with delay
                        kotlinx.coroutines.delay(3000)
                    }
                }
            } catch (e: Exception) {
                consecutiveFailures++
                Timber.tag(TAG).w("Error polling status (attempt $consecutiveFailures/$maxConsecutiveFailures): ${e.message}")
                
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    throw Exception("Max retries exceeded ($maxConsecutiveFailures attempts)")
                }
                
                // Exponential backoff: wait longer between retries
                val waitTime = minOf(5000L * consecutiveFailures, 30000L)
                kotlinx.coroutines.delay(waitTime)
            }
        }
    }

    /**
     * Original IPFS upload method for non-video files or fallback
     */
    private suspend fun uploadToIPFSOriginal(
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?,
        mediaType: MediaType
    ): MimeiFileType? {
        Timber.tag(TAG).d("Starting upload for URI: $uri, mediaType: $mediaType")

        // Resolve writableUrl before using uploadService
        val resolvedUrl = appUser.resolveWritableUrl()
        if (resolvedUrl.isNullOrEmpty()) {
            Timber.tag(TAG).e("Failed to resolve writableUrl")
            return null
        }
        Timber.tag(TAG).d("Successfully resolved writableUrl: $resolvedUrl")

        var offset = 0L
        var byteRead: Int
        val buffer = ByteArray(TW_CONST.CHUNK_SIZE)
        val json = """{"aid": $appId, "ver": "last", "offset": 0}"""
        val request = Gson().fromJson(json, Map::class.java).toMutableMap()

        try {
            Timber.tag(TAG).d("Opening input stream for URI: $uri")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.use { stream ->
                    Timber.tag(TAG).d("Starting chunked upload")
                    while (stream.read(buffer).also { byteRead = it } != -1) {
                        Timber.tag(TAG)
                            .d("Uploading chunk: offset=$offset, bytes=$byteRead")
                        request["fsid"] = appUser.uploadService?.runMApp(
                            "upload_ipfs",
                            request.toMap(), listOf(buffer)
                        )

                        offset += byteRead
                        request["offset"] = offset
                    }
                }
            }
            // Do not know the tweet mid yet, cannot add reference as 2nd argument.
            // Do it later when uploading tweet.
            request["finished"] = "true"
            referenceId?.let { request["referenceid"] = it }
            Timber.tag(TAG).d("Finalizing upload with offset: $offset")
            Timber.tag(TAG).d("Final request: $request")

            val cid = appUser.uploadService?.runMApp<String?>("upload_ipfs", request.toMap())
                ?: return null

            Timber.tag(TAG).d("Upload successful, CID: $cid")

            // Calculate file size - use the offset which represents total bytes uploaded
            val fileSize = offset
            Timber.tag(TAG).d("File size: $fileSize bytes for URI: $uri")

            // Calculate aspect ratio for image or video
            val aspectRatio = when (mediaType) {
                MediaType.Image -> {
                    val ratio = getImageAspectRatio(uri)
                    Timber.tag(TAG).d("Image aspect ratio: $ratio for URI: $uri")
                    // Fallback to 4:3 if aspect ratio calculation fails
                    ratio ?: (4f / 3f).also { 
                        Timber.tag(TAG).w("Using fallback aspect ratio 4:3 for image URI: $uri")
                    }
                }
                MediaType.Video -> {
                    val ratio = VideoManager.getVideoAspectRatio(context, uri)
                    Timber.tag(TAG).d("Video aspect ratio: $ratio for URI: $uri")
                    // Fallback to 16:9 if aspect ratio calculation fails
                    ratio
                }
                else -> {
                    Timber.tag(TAG).d("No aspect ratio calculation for media type: $mediaType")
                    null
                }
            }

            Timber.tag(TAG).d("Final MimeiFileType created with file size: $fileSize, aspect ratio: $aspectRatio")
            return MimeiFileType(cid, mediaType, fileSize, fileName, fileTimestamp, aspectRatio)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error: ${e.message}")
        }
        return null
    }

    /**
     * Calculate file size from URI
     * @param uri File URI
     * @return File size in bytes, or null if calculation fails
     */
    suspend fun getFileSize(uri: Uri): Long? =
        withContext(Dispatchers.IO) {
            return@withContext try {
                var fileSize: Long = 0L
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(8192) // 8KB buffer
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fileSize += bytesRead
                    }
                }
                Timber.tag(TAG).d("File size calculated: $fileSize bytes for URI: $uri")
                fileSize
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to calculate file size for URI: $uri")
                null
            }
        }

    /**
     * Get image aspect ratio considering EXIF orientation
     */
    suspend fun getImageAspectRatio(uri: Uri): Float? =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Try multiple methods to get image dimensions with EXIF orientation consideration
                var width = 0
                var height = 0
                var orientation = ExifInterface.ORIENTATION_NORMAL
                
                // Method 1: BitmapFactory with inJustDecodeBounds
                try {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, options)
                    }
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        width = options.outWidth
                        height = options.outHeight
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w("BitmapFactory method failed: ${e.message}")
                }
                
                // Method 2: Get EXIF data including orientation
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val exif = ExifInterface(input)
                        
                        // Get dimensions from EXIF if BitmapFactory failed
                        if (width == 0) {
                            width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                            height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                        }
                        
                        // Get orientation for proper aspect ratio calculation
                        orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w("ExifInterface method failed: ${e.message}")
                }
                
                if (width > 0 && height > 0) {
                    // Calculate aspect ratio considering EXIF orientation
                    val aspectRatio = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90,
                        ExifInterface.ORIENTATION_ROTATE_270,
                        ExifInterface.ORIENTATION_TRANSPOSE,
                        ExifInterface.ORIENTATION_TRANSVERSE -> {
                            // For 90/270 degree rotations, swap width and height
                            height.toFloat() / width.toFloat()
                        }
                        else -> {
                            // For normal orientation, use width/height
                            width.toFloat() / height.toFloat()
                        }
                    }
                    
                    Timber.tag(TAG).d("Image aspect ratio: $aspectRatio (${width}x${height}, orientation: $orientation) for URI: $uri")
                    aspectRatio
                } else {
                    Timber.tag(TAG).w("Could not determine image dimensions for URI: $uri")
                    null
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error calculating image aspect ratio for URI: $uri")
                null
            }
        }
}

