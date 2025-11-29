package us.fireshare.tweet.video

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Stub implementation for mini version
 * Mini version doesn't use local HLS conversion - uses backend instead
 */
class LocalHLSConverter(private val context: Context) {
    
    companion object {
        private const val TAG = "LocalHLSConverter"
    }
    
    suspend fun convertToHLS(
        inputUri: Uri,
        outputDir: File,
        fileName: String,
        fileSizeBytes: Long
    ): HLSConversionResult {
        // Mini version should never call this
        return HLSConversionResult.Error("FFmpeg not available in mini version")
    }
    
    /**
     * Result of HLS conversion
     */
    sealed class HLSConversionResult {
        data class Success(val outputDirectory: File) : HLSConversionResult()
        data class Error(val message: String) : HLSConversionResult()
    }
}

