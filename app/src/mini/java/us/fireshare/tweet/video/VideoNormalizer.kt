package us.fireshare.tweet.video

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Stub implementation for mini version
 * Mini version doesn't use local video normalization - uses backend instead
 */
class VideoNormalizer(private val context: Context) {
    
    companion object {
        private const val TAG = "VideoNormalizer"
    }
    
    suspend fun normalizeVideo(
        inputUri: Uri,
        outputFile: File,
        resampleTo720p: Boolean = false
    ): NormalizationResult {
        // Mini version should never call this
        return NormalizationResult.Error("FFmpeg not available in mini version")
    }
    
    /**
     * Result of video normalization
     */
    sealed class NormalizationResult {
        data class Success(val outputFile: File) : NormalizationResult()
        data class Error(val message: String) : NormalizationResult()
    }
}

