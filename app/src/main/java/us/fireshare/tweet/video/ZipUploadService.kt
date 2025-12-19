package us.fireshare.tweet.video

import android.content.Context
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.User
import java.io.File
import java.io.FileInputStream

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
    }

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
        fileTimestamp: Long,
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
            Timber.tag(TAG).d("Zip file size: $zipFileSize bytes")

            // Upload zip file - read file as bytes to ensure reliable multipart form data
            // For very large files, consider chunked upload in the future
            // Current approach ensures server receives the file correctly
            val zipFileBytes = try {
                zipFile.readBytes()
            } catch (e: OutOfMemoryError) {
                Timber.tag(TAG).e(e, "Out of memory reading zip file, file size: $zipFileSize bytes")
                return@withContext ZipProcessingResult.Error("File too large to upload: ${zipFileSize / (1024 * 1024)}MB")
            }
            
            Timber.tag(TAG).d("Read ${zipFileBytes.size} bytes from zip file for upload")

            val uploadResponse = httpClient.post(processZipURL) {
                setBody(
                    io.ktor.client.request.forms.MultiPartFormDataContent(
                        io.ktor.client.request.forms.formData {
                            // Add filename if provided
                            append("filename", fileName)

                            // Add reference ID if provided
                            referenceId?.let {
                                append("referenceId", it)
                            }

                            // Append the zip file with proper Content-Disposition header
                            // Using ByteArray ensures reliable multipart form data formatting
                            append(
                                "zipFile",
                                zipFileBytes,
                                io.ktor.http.Headers.build {
                                    append(
                                        io.ktor.http.HttpHeaders.ContentDisposition,
                                        "form-data; name=\"zipFile\"; filename=\"${fileName}.zip\""
                                    )
                                    append(io.ktor.http.HttpHeaders.ContentType, "application/zip")
                                }
                            )
                        }
                    )
                )
            }

            if (uploadResponse.status != HttpStatusCode.OK) {
                throw Exception("Upload failed with status: ${uploadResponse.status}")
            }

            val uploadResponseText = uploadResponse.bodyAsText()
            val uploadResponseData = Gson().fromJson(uploadResponseText, Map::class.java)
            
            val success = uploadResponseData?.get("success") as? Boolean
            if (success != true) {
                val errorMessage = uploadResponseData?.get("message") as? String ?: "Upload failed"
                throw Exception(errorMessage)
            }

            val jobId = uploadResponseData["jobId"] as? String
                ?: throw Exception("No job ID in response")
            
            Timber.tag(TAG).d("Upload started, job ID: $jobId")

            // Poll for progress and completion
            return@withContext pollZipProcessingStatus(
                jobId = jobId,
                baseUrl = "$scheme://$host:$cloudDrivePort",
                fileName = fileName,
                fileTimestamp = fileTimestamp
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during zip upload")
            ZipProcessingResult.Error("Upload error: ${e.message}")
        }
    }

    /**
     * Poll the status of zip processing job
     * @param jobId Job ID returned from upload
     * @param baseUrl Base URL for status endpoint
     * @param fileName Original filename
     * @param fileTimestamp File timestamp
     * @return Result containing the final CID or error
     */
    suspend fun pollZipProcessingStatus(
        jobId: String,
        baseUrl: String,
        fileName: String,
        fileTimestamp: Long
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
                    throw Exception("Job ID not found: $jobId")
                }
                
                if (statusResponse.status != HttpStatusCode.OK) {
                    throw Exception("Status check failed with status: ${statusResponse.status}")
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
                        throw Exception("Job ID not found: $jobId")
                    }
                    throw Exception(errorMessage)
                }

                val status = statusData["status"] as? String
                val progress = (statusData["progress"] as? Number)?.toInt() ?: 0
                val message = statusData["message"] as? String ?: "Processing..."

                // Log progress updates
                if (progress != lastProgress || message != lastMessage) {
                    Timber.tag(TAG).d("Progress: $progress% - $message")
                    lastProgress = progress
                    lastMessage = message
                }

                when (status) {
                    "completed" -> {
                        val cid = statusData["cid"] as? String
                            ?: throw Exception("No CID in completion response")
                        
                        Timber.tag(TAG).d("Zip processing completed successfully: $cid")
                        return@withContext ZipProcessingResult.Success(cid)
                    }
                    "failed" -> {
                        val errorMessage = statusData["message"] as? String ?: "Zip processing failed"
                        throw Exception(errorMessage)
                    }
                    "uploading", "processing" -> {
                        // Continue polling
                        kotlinx.coroutines.delay(3000) // Poll every 3 seconds
                    }
                    else -> {
                        // Unknown status, continue polling
                        kotlinx.coroutines.delay(3000)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Error polling status: ${e.message}")
                kotlinx.coroutines.delay(5000) // Wait 5 seconds before retry
            }
        }
        
        // If we exit the loop, it means we timed out
        ZipProcessingResult.Error("Zip processing timeout after ${maxPollingTime / 1000 / 60} minutes")
    }

    /**
     * Result of zip upload and processing
     */
    sealed class ZipProcessingResult {
        data class Success(val cid: String) : ZipProcessingResult()
        data class Error(val message: String) : ZipProcessingResult()
    }
}