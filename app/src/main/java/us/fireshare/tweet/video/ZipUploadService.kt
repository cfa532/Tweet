package us.fireshare.tweet.video

import android.content.Context
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import timber.log.Timber
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.User
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Service for uploading ZIP files containing HLS content to the /process-zip endpoint
 * and polling for processing status
 */
class ZipUploadService(
    private val context: Context,
    private val httpClient: HttpClient,
    private val appUser: User
) {

    companion object {
        private const val TAG = "ZipUploadService"
        private val ZIP_MEDIA_TYPE = "application/zip".toMediaType()
    }

    private val zipUploadClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.HOURS)
        .readTimeout(6, TimeUnit.HOURS)
        .callTimeout(6, TimeUnit.HOURS)
        .build()

    /**
     * Upload zip file to /process-zip endpoint with progress polling
     * @param zipFile Zip file to upload
     * @param fileName Original filename
     * @param fileTimestamp File timestamp
     * @param referenceId Reference ID for the upload
     * @return Result containing the processed file information
     */
    suspend fun uploadZipFile(
        zipFile: File,
        fileName: String,
        referenceId: MimeiId?
    ): ZipProcessingResult = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).d("Starting zip upload for: $fileName")

            // Get the user's cloudDrivePort (must be set by user, 0 means not set)
            val cloudDrivePort = appUser.cloudDrivePort
            if (cloudDrivePort == 0) {
                return@withContext ZipProcessingResult.Error("cloudDrivePort not set")
            }

            // Ensure writableUrl is available
            var writableUrl = appUser.writableUrl
            if (writableUrl.isNullOrEmpty()) {
                writableUrl = appUser.resolveWritableUrl()
            }

            if (writableUrl.isNullOrEmpty()) {
                return@withContext ZipProcessingResult.Error("Writable URL not available")
            }

            // Construct process-zip endpoint URL
            val scheme = if (writableUrl.startsWith("https")) "https" else "http"
            val host = writableUrl.replace(Regex("^https?://"), "").split("/").firstOrNull()?.split(":")
                ?.firstOrNull() ?: writableUrl
            val processZipURL = "$scheme://$host:$cloudDrivePort/process-zip"

            Timber.tag(TAG).d("Process-zip URL: $processZipURL")

            val zipFileSize = zipFile.length()
            Timber.tag(TAG).d("Zip file size: $zipFileSize bytes (${zipFileSize / (1024 * 1024)}MB)")
            Timber.tag(TAG).d("Initiating streaming ZIP upload to: $processZipURL")
            val safeZipFileName = zipFile.name
                .replace("\"", "_")
                .replace("\r", "_")
                .replace("\n", "_")

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("filename", fileName)

            referenceId?.let {
                multipartBuilder.addFormDataPart("referenceId", it)
            }

            val requestBody = multipartBuilder
                .addFormDataPart(
                    "zipFile",
                    safeZipFileName,
                    zipFile.asStreamingRequestBody()
                )
                .build()

            val request = Request.Builder()
                .url(processZipURL)
                .post(requestBody)
                .build()

            val uploadResponseText = zipUploadClient.newCall(request).execute().use { response ->
                Timber.tag(TAG).d("Upload completed, response status: ${response.code} ${response.message}")

                if (!response.isSuccessful) {
                    Timber.tag(TAG).e("Upload failed with HTTP status: ${response.code} ${response.message}")
                    throw Exception("Upload failed with status: ${response.code} ${response.message}")
                }

                response.body.string()
            }
            Timber.tag(TAG).d("Upload response: $uploadResponseText")
            
            val uploadResponseData = Gson().fromJson(uploadResponseText, Map::class.java)
            
            val success = uploadResponseData?.get("success") as? Boolean
            if (success != true) {
                val errorMessage = uploadResponseData?.get("message") as? String ?: "Upload failed"
                Timber.tag(TAG).e("Upload failed: $errorMessage")
                throw Exception(errorMessage)
            }

            val jobId = uploadResponseData["jobId"] as? String
            if (jobId == null) {
                Timber.tag(TAG).e("No job ID in response: $uploadResponseText")
                throw Exception("No job ID in response")
            }
            
            Timber.tag(TAG).d("Upload started, job ID: $jobId")

            // Suggest garbage collection after large upload to free memory
            // This is just a hint - JVM decides when to actually GC
            System.gc()

            // Poll for progress and completion
            return@withContext pollZipProcessingStatus(
                jobId = jobId,
                baseUrl = "$scheme://$host:$cloudDrivePort"
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during zip upload")
            ZipProcessingResult.Error("Upload error: ${e.message}")
        }
    }

    private fun File.asStreamingRequestBody(): RequestBody {
        return object : RequestBody() {
            override fun contentType() = ZIP_MEDIA_TYPE

            override fun contentLength() = length()

            override fun writeTo(sink: BufferedSink) {
                source().use { fileSource ->
                    sink.writeAll(fileSource)
                }
            }
        }
    }

    /**
     * Poll the status of zip processing job
     * @param jobId Job ID returned from upload
     * @param baseUrl Base URL for status endpoint
     * @return Result containing the final CID or error
     */
    suspend fun pollZipProcessingStatus(
        jobId: String,
        baseUrl: String
    ): ZipProcessingResult = withContext(Dispatchers.IO) {
        val statusURL = "$baseUrl/process-zip/status/$jobId"
        var lastProgress = 0
        var lastMessage = "Starting zip processing..."
        val maxPollingTime = 2 * 60 * 60 * 1000L // 2 hours max polling time
        val startTime = System.currentTimeMillis()

        Timber.tag(TAG).d("Starting to poll status for job: $jobId")

        while (System.currentTimeMillis() - startTime < maxPollingTime) {
            try {
                val statusResponse = httpClient.get(statusURL)
                
                if (statusResponse.status == HttpStatusCode.NotFound) {
                    // Job ID not found - cancel immediately without retry
                    Timber.tag(TAG).e("Job ID not found: $jobId")
                    return@withContext ZipProcessingResult.Error("Job ID not found: $jobId")
                }
                
                if (statusResponse.status != HttpStatusCode.OK) {
                    Timber.tag(TAG).e("Status check failed with HTTP status: ${statusResponse.status}")
                    throw Exception("Status check failed with status: ${statusResponse.status}")
                }

                val statusResponseText = statusResponse.bodyAsText()
                Timber.tag(TAG).d("Status response raw: $statusResponseText")
                
                val statusData = try {
                    Gson().fromJson(statusResponseText, Map::class.java)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to parse status response as JSON")
                    throw Exception("Failed to parse status response: ${e.message}")
                }
                
                Timber.tag(TAG).d("Status response parsed. Keys: ${statusData?.keys?.joinToString()}")
                
                val success = statusData?.get("success") as? Boolean
                if (success != true) {
                    val errorMessage = statusData?.get("message") as? String ?: "Status check failed"
                    // Check if the error message indicates job not found
                    if (errorMessage.contains("not found", ignoreCase = true) || 
                        errorMessage.contains("job not found", ignoreCase = true)) {
                        Timber.tag(TAG).e("Job ID not found in response: $jobId")
                        throw Exception("Job ID not found: $jobId")
                    }
                    throw Exception(errorMessage)
                }

                val status = statusData["status"] as? String
                val progress = (statusData["progress"] as? Number)?.toInt() ?: 0
                val message = statusData["message"] as? String ?: "Processing..."
                
                Timber.tag(TAG).d("Parsed values - status: $status, progress: $progress, message: $message")

                // Log progress updates
                if (progress != lastProgress || message != lastMessage) {
                    Timber.tag(TAG).d("Progress: $progress% - $message")
                    lastProgress = progress
                    lastMessage = message
                }

                when (status) {
                    "completed" -> {
                        Timber.tag(TAG).d("Status is 'completed', checking for CID...")
                        
                        // Log all values in statusData for debugging
                        Timber.tag(TAG).d("All status data: $statusData")
                        
                        // Try multiple possible field names for CID (case variations)
                        val cidFromLowercase = statusData["cid"]
                        val cidFromUppercase = statusData["CID"]
                        val cidFromCapitalized = statusData["Cid"]
                        
                        Timber.tag(TAG).d("CID candidates - cid: $cidFromLowercase, CID: $cidFromUppercase, Cid: $cidFromCapitalized")
                        
                        val cid = cidFromLowercase as? String
                            ?: cidFromUppercase as? String
                            ?: cidFromCapitalized as? String
                            ?: statusData["ipfsCid"] as? String
                            ?: statusData["ipfs_cid"] as? String
                        
                        if (cid == null) {
                            Timber.tag(TAG).e("No CID in completion response. Response: $statusResponseText")
                            Timber.tag(TAG).e("Available keys: ${statusData.keys.joinToString()}")
                            Timber.tag(TAG).e("Status data type: ${statusData.javaClass.name}")
                            return@withContext ZipProcessingResult.Error("No CID in completion response")
                        }
                        
                        Timber.tag(TAG).d("Zip processing completed successfully with CID: $cid")
                        return@withContext ZipProcessingResult.Success(cid)
                    }
                    "failed" -> {
                        val errorMessage = statusData["message"] as? String ?: "Zip processing failed"
                        Timber.tag(TAG).e("Zip processing failed: $errorMessage")
                        return@withContext ZipProcessingResult.Error(errorMessage)
                    }
                    "uploading", "processing" -> {
                        // Continue polling
                        kotlinx.coroutines.delay(3000) // Poll every 3 seconds
                    }
                    else -> {
                        // Unknown status, continue polling
                        Timber.tag(TAG).w("Unknown status: $status, continuing to poll...")
                        kotlinx.coroutines.delay(3000)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error polling status: ${e.message}")
                kotlinx.coroutines.delay(5000) // Wait 5 seconds before retry
            }
        }
        
        // If we exit the loop, it means we timed out
        Timber.tag(TAG).e("Zip processing timeout after ${maxPollingTime / 1000 / 60} minutes for job: $jobId")
        return@withContext ZipProcessingResult.Error("Zip processing timeout after ${maxPollingTime / 1000 / 60} minutes")
    }

    /**
     * Result of zip upload and processing
     */
    sealed class ZipProcessingResult {
        data class Success(val cid: String) : ZipProcessingResult()
        data class Error(val message: String) : ZipProcessingResult()
    }
}
