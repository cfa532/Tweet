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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.video.LocalVideoProcessingService
import us.fireshare.tweet.video.VideoNormalizer
import us.fireshare.tweet.widget.VideoManager
import java.io.File
import java.util.Locale

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
        private const val PROGRESSIVE_VIDEO_THRESHOLD_BYTES = 32L * 1024 * 1024  // 32MB
    }

    /**
     * Upload media file to node and return its IPFS cid with its media type.
     * For videos, first tries to upload to net disk URL, then falls back to IPFS method.
     */
    @OptIn(UnstableApi::class)
    suspend fun uploadToIPFS(
        uri: Uri,
        referenceId: MimeiId? = null
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
                    FileTypeDetector.detectFileType(context, uri, fileName)
                } else {
                    extensionType
                }
            }
        }

        // For video files, implement new routing logic:
        // 1. Normalize to 720p/1500k (preserving original if lower)
        // 2. Route based on normalized video size:
        //    - < 32MB: progressive video route
        //    - < 128MB: HLS route 1 (720p + 480p)
        //    - >= 128MB: HLS route 2 (720p + 360p)
        if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) {
            return if (mediaType == MediaType.Video) {
                processVideoWithRouting(uri, fileName, fileTimestamp, referenceId)
            } else {
                // HLS_VIDEO type - already processed, upload directly
                uploadToIPFSOriginal(uri, fileName, fileTimestamp, referenceId, MediaType.HLS_VIDEO)
            }
        }

        // Fall back to original IPFS method for non-video files
        return uploadToIPFSOriginal(uri, fileName, fileTimestamp, referenceId, mediaType)
    }

    /**
     * Check if TUS server is available at tusServerUrl
     */
    private suspend fun isConversionServerAvailable(): Boolean {
        return try {
            Timber.tag(TAG).d("Checking TUS server availability - cloudDrivePort: ${appUser.cloudDrivePort}, writableUrl: ${appUser.writableUrl}")
            
            // First check if cloudDrivePort is valid (0 means not set)
            if (appUser.cloudDrivePort == 0) {
                Timber.tag(TAG).d("cloudDrivePort is not set (value: ${appUser.cloudDrivePort})")
                return false
            }
            
            // Ensure writableUrl is resolved
            if (appUser.writableUrl.isNullOrEmpty()) {
                val resolved = appUser.resolveWritableUrl()
                Timber.tag(TAG).d("Resolved writableUrl: $resolved")
                if (resolved.isNullOrEmpty()) {
                    Timber.tag(TAG).d("Could not resolve writableUrl")
                    return false
                }
            }
            
            val tusServerUrl = appUser.tusServerUrl
            if (tusServerUrl.isNullOrEmpty()) {
                Timber.tag(TAG).d("tusServerUrl is not available (cloudDrivePort=${appUser.cloudDrivePort}, writableUrl=${appUser.writableUrl})")
                return false
            }
            
            // Try to ping the /health endpoint
            val healthCheckUrl = "$tusServerUrl/health"
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
            Timber.tag(TAG).d("TUS server available: $isAvailable")
            isAvailable
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error checking TUS server: ${e.message}")
            false
        }
    }

    /**
     * Process video with new routing logic:
     * 1. Check video resolution:
     *    - If > 720p: Normalize to 720p/1000k first, then use normalized size for routing
     *    - If ≤ 720p: Skip normalization, use original file size for routing
     * 2. Route based on video size:
     *    - ≤ 32MB: progressive video route (normalize if > 720p, or use original if ≤ 720p)
     *    - > 32MB: HLS conversion based on resolution
     *       - Resolution > 480p: Dual variant (720p + 480p)
     *       - Resolution ≤ 480p: Single variant (480p only)
     */
    private suspend fun processVideoWithRouting(
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?
    ): MimeiFileType? {
        return try {
            // Mini version: Skip FFmpeg processing, upload directly to IPFS
            if (BuildConfig.IS_MINI_VERSION) {
                Timber.tag(TAG).d("Mini version detected: Uploading video directly without local processing")
                return uploadToIPFSOriginal(uri, fileName, fileTimestamp, referenceId, MediaType.Video)
            }
            
            Timber.tag(TAG).d("Starting video processing with new normalization and routing algorithm")
            
            // Use LocalVideoProcessingService which implements the complete algorithm:
            // 1. Resolution Detection: Landscape (width ≥ height) = HEIGHT, Portrait (width < height) = WIDTH
            // 2. Video Normalization: >720p → 720p@1500k, ≤720p → original@proportional bitrate
            // 3. Routing After Normalization: ≤32MB → progressive, >32MB → HLS
            // 4. HLS Variant Selection: >480p → dual (720p + 480p), ≤480p → single (480p only)
            // 5. HLS Segment Creation with COPY Encoder: preserves native quality when possible
            val useRoute2 = false // Always use route 1 (720p + 480p) for dual variant
            processVideoLocally(uri, fileName, fileTimestamp, referenceId, useRoute2)
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in video processing with routing")
            // Fall back to direct IPFS upload as progressive video
            Timber.tag(TAG).d("Falling back to direct IPFS upload due to processing error")
            uploadToIPFSOriginal(uri, fileName ?: "video", fileTimestamp, referenceId, MediaType.Video)
        }
    }

    /**
     * Route video based on file size and resolution
     * @param videoUri URI of the video to route (may be normalized or original)
     * @param fileName Original filename
     * @param fileTimestamp File timestamp
     * @param referenceId Reference ID
     * @param fileSize File size in bytes
     * @param videoResolution Video resolution (width, height)
     * @param isNormalized Whether the video has been normalized
     */
    private suspend fun routeVideoBySize(
        videoUri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?,
        fileSize: Long,
        videoResolution: Pair<Int, Int>?,
        isNormalized: Boolean
    ): MimeiFileType? {
        // Safety check: Mini version should never call this method
        if (BuildConfig.IS_MINI_VERSION) {
            Timber.tag(TAG).w("Mini version attempted to call routeVideoBySize - uploading directly")
            return uploadToIPFSOriginal(videoUri, fileName, fileTimestamp, referenceId, MediaType.Video)
        }
        
        val videoResolutionValue = VideoManager.getVideoResolutionValue(videoResolution)
        
        return when {
            fileSize <= PROGRESSIVE_VIDEO_THRESHOLD_BYTES -> {
                // ≤ 32MB: progressive video route
                // If not normalized and > 720p, we need to normalize first
                if (!isNormalized && videoResolutionValue != null && videoResolutionValue > 720) {
                    Timber.tag(TAG).d("Video ≤ 32MB but > 720p, normalizing before progressive upload")
                    val normalizer = VideoNormalizer(context)
                    val normalizedFile = File(context.cacheDir, "normalized_${System.currentTimeMillis()}.mp4")
                    
                    try {
                        val normalizationResult = normalizer.normalizeTo720p1000k(videoUri, normalizedFile)
                        when (normalizationResult) {
                            is VideoNormalizer.NormalizationResult.Success -> {
                                val normalizedUri = Uri.fromFile(normalizedFile)
                                val result = uploadToIPFSOriginal(
                                    normalizedUri,
                                    fileName,
                                    fileTimestamp,
                                    referenceId,
                                    MediaType.Video
                                )
                                if (result != null) {
                                    Timber.tag(TAG).d("Progressive video uploaded: ${result.mid}")
                                }
                                result
                            }
                            else -> {
                                Timber.tag(TAG).e("Normalization failed for progressive upload")
                                uploadToIPFSOriginal(videoUri, fileName, fileTimestamp, referenceId, MediaType.Video)
                            }
                        }
                    } finally {
                        if (normalizedFile.exists()) {
                            normalizedFile.delete()
                        }
                    }
                } else {
                    Timber.tag(TAG).d("Video ≤ 32MB, using progressive video route")
                    val result = uploadToIPFSOriginal(
                        videoUri,
                        fileName,
                        fileTimestamp,
                        referenceId,
                        MediaType.Video
                    )
                    if (result != null) {
                        Timber.tag(TAG).d("Progressive video uploaded: ${result.mid}")
                    }
                    result
                }
            }
            else -> {
                // > 32MB: HLS conversion
                // Normalization and variant selection are now handled automatically in processVideo
                val useRoute2 = false // Always use route 1 (720p + 480p) for dual variant
                
                Timber.tag(TAG).d("Video > 32MB, routing to HLS conversion")
                processVideoLocally(videoUri, fileName, fileTimestamp, referenceId, useRoute2 = useRoute2)
            }
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
        referenceId: MimeiId?,
        useRoute2: Boolean = false
    ): MimeiFileType? {
        return try {
            // Safety check: Mini version should never call this method
            if (BuildConfig.IS_MINI_VERSION) {
                Timber.tag(TAG).w("Mini version attempted to call processVideoLocally - uploading directly")
                return uploadToIPFSOriginal(uri, fileName, fileTimestamp, referenceId, MediaType.Video)
            }
            
            // Check if conversion server is available
            val serverAvailable = isConversionServerAvailable()
            
            if (serverAvailable) {
                // Use HLS conversion and upload to process-zip endpoint
                Timber.tag(TAG).d("Processing video via HLS conversion server (route ${if (useRoute2) "2" else "1"})")
                val localProcessingService = LocalVideoProcessingService(context, httpClient, appUser)
                val result = localProcessingService.processVideo(
                    uri = uri,
                    fileName = fileName ?: "video",
                    fileTimestamp = fileTimestamp,
                    referenceId = referenceId,
                    useRoute2 = useRoute2
                )
                
                when (result) {
                    is LocalVideoProcessingService.VideoProcessingResult.Success -> {
                        result.mimeiFile
                    }
                    is LocalVideoProcessingService.VideoProcessingResult.Error -> {
                        Timber.tag(TAG).e("HLS processing failed: ${result.message}")
                        // Fall back to normalization and IPFS upload
                        Timber.tag(TAG).d("Falling back to MP4 normalization for IPFS upload")
                        normalizeAndUploadVideo(uri, fileName, fileTimestamp, referenceId)
                    }
                }
            } else {
                // Normalize to mp4 and upload via IPFS
                Timber.tag(TAG).d("Normalizing video to MP4 for IPFS upload")
                return normalizeAndUploadVideo(uri, fileName, fileTimestamp, referenceId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in local video processing")
            // Fall back to direct IPFS upload as progressive video
            Timber.tag(TAG).d("Falling back to direct IPFS upload due to processing error")
            uploadToIPFSOriginal(uri, fileName ?: "video", fileTimestamp, referenceId, MediaType.Video)
        }
    }

    /**
     * Normalize video to MP4 format and upload via IPFS
     * - Converts video to MP4 format
     * - If resolution > 720p, reduces to 720p
     * - Otherwise keeps original resolution
     * - Uploads the normalized video
     */
    private suspend fun normalizeAndUploadVideo(
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?
    ): MimeiFileType? {
        return try {
            // Safety check: Mini version should never call this method (no FFmpeg)
            if (BuildConfig.IS_MINI_VERSION) {
                Timber.tag(TAG).w("Mini version attempted to call normalizeAndUploadVideo - uploading directly")
                return uploadToIPFSOriginal(uri, fileName, fileTimestamp, referenceId, MediaType.Video)
            }
            
            Timber.tag(TAG).d("Normalizing video to MP4 for IPFS upload")
            
            // Check if video resolution is > 720p
            val videoResolution = VideoManager.getVideoResolution(context, uri)
            val needsResampling = if (videoResolution != null) {
                val (width, height) = videoResolution
                width > 1280 || height > 720
            } else {
                false
            }
            
            if (needsResampling) {
                Timber.tag(TAG).d("Video ${videoResolution?.first}x${videoResolution?.second} will be resampled to 720p")
            } else {
                Timber.tag(TAG).d("Video ${videoResolution?.first}x${videoResolution?.second} will keep original resolution")
            }
            
            // Normalize video to mp4
            val normalizer = VideoNormalizer(context)
            val normalizedFile = File(context.cacheDir, "normalized_${System.currentTimeMillis()}.mp4")
            
            try {
                val normalizationResult = normalizer.normalizeVideo(uri, normalizedFile, needsResampling)
                
                when (normalizationResult) {
                    is VideoNormalizer.NormalizationResult.Success -> {
                        // Upload normalized video via IPFS
                        val normalizedUri = Uri.fromFile(normalizedFile)
                        val result = uploadToIPFSOriginal(
                            normalizedUri,
                            fileName,
                            fileTimestamp,
                            referenceId,
                            MediaType.Video
                        )
                        
                        if (result != null) {
                            Timber.tag(TAG).d("Video uploaded: ${result.mid}")
                        }
                        result
                    }
                    is VideoNormalizer.NormalizationResult.Error -> {
                        Timber.tag(TAG).e("Video normalization failed: ${normalizationResult.message}")
                        // Fall back to uploading original video as progressive video
                        Timber.tag(TAG).d("Falling back to uploading original video as progressive video")
                        uploadToIPFSOriginal(
                            uri,
                            fileName,
                            fileTimestamp,
                            referenceId,
                            MediaType.Video
                        )
                    }
                }
            } finally {
                // Clean up normalized file
                if (normalizedFile.exists()) {
                    normalizedFile.delete()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in video normalization and upload")
            null
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
        // Resolve writableUrl before using uploadService (with retry)
        var resolvedUrl: String? = null
        var lastError: String?
        for (attempt in 1..3) {
            resolvedUrl = appUser.resolveWritableUrl()
            if (!resolvedUrl.isNullOrEmpty()) {
                Timber.tag(TAG).d("Successfully resolved writableUrl on attempt $attempt: $resolvedUrl")
                break
            } else {
                lastError = "Failed to resolve writableUrl (attempt $attempt/3)"
                Timber.tag(TAG).w(lastError)
                if (attempt < 3) {
                    // Wait a bit before retrying (exponential backoff)
                    delay(1000L * attempt)
                }
            }
        }
        
        if (resolvedUrl.isNullOrEmpty()) {
            Timber.tag(TAG).e("Failed to resolve writableUrl after 3 attempts")
            return null
        }

        var offset = 0L
        var byteRead: Int
        val buffer = ByteArray(TW_CONST.CHUNK_SIZE)
        val json = """{"aid": $appId, "ver": "last", "offset": 0}"""
        val request = Gson().fromJson(json, Map::class.java).toMutableMap()

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.use { stream ->
                    while (stream.read(buffer).also { byteRead = it } != -1) {
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

            val cid = appUser.uploadService?.runMApp<String?>("upload_ipfs", request.toMap())
                ?: return null

            // Calculate file size - use the offset which represents total bytes uploaded
            val fileSize = offset

            // Calculate aspect ratio for image or video
            val aspectRatio = when (mediaType) {
                MediaType.Image -> {
                    val ratio = getImageAspectRatio(uri)
                    // Fallback to 4:3 if aspect ratio calculation fails
                    ratio ?: (4f / 3f).also { 
                        Timber.tag(TAG).w("Using fallback aspect ratio 4:3 for image")
                    }
                }
                MediaType.Video -> {
                    VideoManager.getVideoAspectRatio(context, uri)
                }
                else -> null
            }

            Timber.tag(TAG).d("Upload complete: $cid (${fileSize / 1024}KB)")
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
                var fileSize = 0L
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(8192) // 8KB buffer
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fileSize += bytesRead
                    }
                }
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
                    
                    aspectRatio
                } else {
                    Timber.tag(TAG).w("Could not determine image dimensions")
                    null
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error calculating image aspect ratio for URI: $uri")
                null
            }
        }
}

