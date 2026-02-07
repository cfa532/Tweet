package us.fireshare.tweet.video

import android.content.Context
import android.net.Uri
import io.ktor.client.HttpClient
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.User

/**
 * Stub implementation of LocalVideoProcessingService for mini variant.
 * The mini variant doesn't include FFmpeg, so local video processing is not supported.
 * Video processing should use backend services instead.
 */
class LocalVideoProcessingService(
    private val context: Context,
    httpClient: HttpClient,
    appUser: User
) {

    /**
     * Process video - not supported in mini variant
     * @return Error result indicating local processing is not available
     */
    suspend fun processVideo(
        uri: Uri,
        fileName: String,
        fileTimestamp: Long,
        referenceId: MimeiId?,
        useRoute2: Boolean = false
    ): VideoProcessingResult {
        return VideoProcessingResult.Error("Local video processing not available in mini version. Use backend processing instead.")
    }

    /**
     * Result of video processing
     */
    sealed class VideoProcessingResult {
        data class Success(val mimeiFile: MimeiFileType) : VideoProcessingResult()
        data class Error(val message: String) : VideoProcessingResult()
    }
}

