package us.fireshare.tweet.video

import android.content.Context
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.core.writeFully
import io.ktor.utils.io.streams.asInput
import io.ktor.utils.io.writer
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
        // Use small buffer size to minimize memory footprint during upload
        // This prevents OOM errors with large files (e.g., 292MB+ ZIP files)
        private const val UPLOAD_BUFFER_SIZE = 8192 // 8KB chunks for controlled memory usage
        private const val MAX_UPLOAD_CHUNK = 64 * 1024 // 64KB max chunk size
        
        /**
         * Log current memory usage for monitoring OOM risk
         */
        private fun logMemoryUsage(context: String) {
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMemory = runtime.maxMemory() / (1024 * 1024)
            val freeMemory = runtime.freeMemory() / (1024 * 1024)
            val percentUsed = (usedMemory * 100) / maxMemory
            
            Timber.tag(TAG).d("[$context] Memory: ${usedMemory}MB used / ${maxMemory}MB max ($percentUsed%), ${freeMemory}MB free")
            
            // Warning if memory usage is high
            if (percentUsed > 85) {
                Timber.tag(TAG).w("[$context] HIGH MEMORY USAGE: $percentUsed% - risk of OOM")
            }
        }
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
            
            // Log memory before upload starts
            logMemoryUsage("Before ZIP upload")
            
            Timber.tag(TAG).d("Initiating memory-controlled streaming upload to: $processZipURL")
            Timber.tag(TAG).d("Upload buffer size: ${UPLOAD_BUFFER_SIZE / 1024}KB, max chunk: ${MAX_UPLOAD_CHUNK / 1024}KB")

            // Use controlled streaming with explicit buffer sizes to prevent OOM
            // This is critical for large ZIP files (200MB+) on devices with limited heap
            val uploadResponse = httpClient.submitFormWithBinaryData(
                url = processZipURL,
                formData = io.ktor.client.request.forms.formData {
                    // Add filename if provided
                    append("filename", fileName)

                    // Add reference ID if provided
                    referenceId?.let {
                        append("referenceId", it)
                    }

                    // Stream the zip file with controlled buffer size
                    // Using channelProvider to have fine-grained control over memory allocation
                    appendInput(
                        key = "zipFile",
                        headers = io.ktor.http.Headers.build {
                            append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=\"${zipFile.name}\"")
                            append(io.ktor.http.HttpHeaders.ContentType, "application/zip")
                            append(io.ktor.http.HttpHeaders.ContentLength, zipFileSize.toString())
                        },
                        size = zipFileSize
                    ) {
                        // Custom channel provider with controlled buffer sizes
                        buildPacket {
                            FileInputStream(zipFile).use { fileStream ->
                                val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
                                var totalRead = 0L
                                var bytesRead: Int
                                var lastLoggedPercent = 0

                                while (fileStream.read(buffer).also { bytesRead = it } != -1) {
                                    writeFully(buffer, 0, bytesRead)
                                    totalRead += bytesRead

                                    // Log progress every 10%
                                    val currentPercent = ((totalRead * 100) / zipFileSize).toInt()
                                    if (currentPercent >= lastLoggedPercent + 10) {
                                        Timber.tag(TAG).d("Upload progress: $currentPercent% (${totalRead / (1024 * 1024)}MB / ${zipFileSize / (1024 * 1024)}MB)")
                                        lastLoggedPercent = currentPercent
                                    }
                                }
                                
                                Timber.tag(TAG).d("File streaming completed: $totalRead bytes")
                            }
                        }
                    }
                }
            )
            
            // Log memory after upload completes
            logMemoryUsage("After ZIP upload")
            
            Timber.tag(TAG).d("Upload completed, response status: ${uploadResponse.status}")

            if (uploadResponse.status != HttpStatusCode.OK) {
                Timber.tag(TAG).e("Upload failed with HTTP status: ${uploadResponse.status}")
                throw Exception("Upload failed with status: ${uploadResponse.status}")
            }

            val uploadResponseText = uploadResponse.bodyAsText()
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
            logMemoryUsage("After GC suggestion")

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