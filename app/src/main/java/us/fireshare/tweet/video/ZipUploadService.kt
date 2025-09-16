package us.fireshare.tweet.video

import android.content.Context
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.MimeiId
import java.io.File

/**
 * Service for uploading zip files to the /process-zip endpoint
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
     * @param referenceId Reference ID
     * @return Result containing job ID and base URL for polling
     */
    suspend fun uploadZipFile(
        zipFile: File,
        fileName: String,
        fileTimestamp: Long,
        referenceId: MimeiId?
    ): ZipUploadResult = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).d("Starting zip upload for: $fileName")

            // Get the user's cloudDrivePort with fallback to default
            val cloudDrivePort = appUser.cloudDrivePort.takeIf { it > 0 } ?: 8010

            // Ensure writableUrl is available
            var writableUrl = appUser.writableUrl
            if (writableUrl.isNullOrEmpty()) {
                writableUrl = appUser.resolveWritableUrl()
            }

            if (writableUrl.isNullOrEmpty()) {
                return@withContext ZipUploadResult.Error("Writable URL not available")
            }

            // Construct process-zip endpoint URL
            val scheme = if (writableUrl.startsWith("https")) "https" else "http"
            val host = writableUrl.replace(Regex("^https?://"), "").split("/").firstOrNull()?.split(":")
                ?.firstOrNull() ?: writableUrl
            val processZipURL = "$scheme://$host:$cloudDrivePort/process-zip"

            Timber.tag(TAG).d("Process-zip URL: $processZipURL")

            // Read the zip file data
            val zipData = zipFile.readBytes()
            Timber.tag(TAG).d("Zip file size: ${zipData.size} bytes")

            // Upload zip file and get job ID
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

                            // Add the zip file
                            append(
                                "zipFile",
                                zipData,
                                io.ktor.http.Headers.build {
                                    append(
                                        "Content-Disposition",
                                        "filename=\"${fileName}.zip\""
                                    )
                                    append("Content-Type", "application/zip")
                                }
                            )
                        }
                    )
                )
            }

            if (uploadResponse.status != HttpStatusCode.OK) {
                throw Exception("Upload failed with status: ${uploadResponse.status}")
            }

            val uploadResponseText = uploadResponse.body<String>()
            val uploadResponseData = Gson().fromJson(uploadResponseText, Map::class.java)

            val success = uploadResponseData?.get("success") as? Boolean
            if (success == true) {
                val jobId = uploadResponseData["jobId"] as? String
                if (jobId != null) {
                    Timber.tag(TAG).d("Zip upload successful, job ID: $jobId")
                    ZipUploadResult.Success(jobId, processZipURL)
                } else {
                    ZipUploadResult.Error("No job ID in upload response")
                }
            } else {
                val errorMessage = uploadResponseData?.get("message") as? String ?: "Upload failed"
                ZipUploadResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during zip upload")
            ZipUploadResult.Error("Upload error: ${e.message}")
        }
    }

    /**
     * Poll the status of zip processing job
     * @param jobId Job ID from upload response
     * @param baseUrl Base URL for status endpoint
     * @param fileName Original filename
     * @param fileTimestamp File timestamp
     * @return Result containing the processed file information
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
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 10
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
                    consecutiveFailures++
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        return@withContext ZipProcessingResult.Error("Too many consecutive failures: ${statusResponse.status}")
                    }
                    kotlinx.coroutines.delay(5000) // Wait 5 seconds before retry
                    continue
                }

                consecutiveFailures = 0 // Reset failure count on success
                val statusResponseText = statusResponse.body<String>()
                val statusData = Gson().fromJson(statusResponseText, Map::class.java)
                
                // Check if the response indicates job not found
                val success = statusData?.get("success") as? Boolean
                if (success == false) {
                    val errorMessage = statusData["message"] as? String ?: "Unknown error"
                    if (errorMessage.contains("not found", ignoreCase = true) || 
                        errorMessage.contains("job not found", ignoreCase = true)) {
                        Timber.tag(TAG).e("Job ID not found in response: $jobId")
                        return@withContext ZipProcessingResult.Error("Job ID not found: $jobId")
                    }
                }

                val status = statusData["status"] as? String
                val progress = (statusData["progress"] as? Number)?.toInt() ?: 0
                val message = statusData["message"] as? String ?: "Processing..."

                // Update progress if it changed
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
                        return@withContext ZipProcessingResult.Error(errorMessage)
                    }
                    "uploading", "processing" -> {
                        // Continue polling
                        kotlinx.coroutines.delay(3000) // Poll every 3 seconds
                    }
                    else -> {
                        Timber.tag(TAG).w("Unknown status: $status")
                        kotlinx.coroutines.delay(3000)
                    }
                }
            } catch (e: Exception) {
                consecutiveFailures++
                Timber.tag(TAG).w("Error polling status: ${e.message}")
                
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    return@withContext ZipProcessingResult.Error("Too many consecutive failures: ${e.message}")
                }
                
                kotlinx.coroutines.delay(5000) // Wait 5 seconds before retry
            }
        }
        
        // If we exit the loop, it means we timed out
        ZipProcessingResult.Error("Zip processing timeout after ${maxPollingTime / 1000 / 60} minutes")
    }

    /**
     * Result of zip upload
     */
    sealed class ZipUploadResult {
        data class Success(val jobId: String, val baseUrl: String) : ZipUploadResult()
        data class Error(val message: String) : ZipUploadResult()
    }

    /**
     * Result of zip processing
     */
    sealed class ZipProcessingResult {
        data class Success(val cid: String) : ZipProcessingResult()
        data class Error(val message: String) : ZipProcessingResult()
    }
}
