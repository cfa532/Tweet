package us.fireshare.tweet.utils

import android.content.Context
import android.net.Uri
import us.fireshare.tweet.R
import java.text.DecimalFormat

object FileSizeUtils {
    private const val BYTES_PER_KB = 1024
    private const val BYTES_PER_MB = BYTES_PER_KB * 1024
    private const val BYTES_PER_GB = BYTES_PER_MB * 1024
    
    private const val MAX_FILE_SIZE = 512 * BYTES_PER_MB
    private const val LARGE_FILE_THRESHOLD = 50 * BYTES_PER_MB
    private const val VERY_LARGE_FILE_THRESHOLD = 100 * BYTES_PER_MB
    
    private val decimalFormat = DecimalFormat("#.#")
    
    /**
     * Formats file size in bytes to human readable format
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= BYTES_PER_GB -> "${decimalFormat.format(bytes.toDouble() / BYTES_PER_GB)}GB"
            bytes >= BYTES_PER_MB -> "${decimalFormat.format(bytes.toDouble() / BYTES_PER_MB)}MB"
            bytes >= BYTES_PER_KB -> "${decimalFormat.format(bytes.toDouble() / BYTES_PER_KB)}KB"
            else -> "${bytes}B"
        }
    }
    
    /**
     * Gets file size from URI
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val size = inputStream?.available()?.toLong() ?: 0L
            inputStream?.close()
            size
        } catch (_: Exception) {
            0L
        }
    }
    
    /**
     * Checks if file size is valid (under limit)
     */
    fun isFileSizeValid(fileSize: Long): Boolean {
        return fileSize <= MAX_FILE_SIZE
    }
    
    /**
     * Gets appropriate file size warning message
     */
    fun getFileSizeWarningMessage(context: Context, fileSize: Long): String {
        val formattedSize = formatFileSize(fileSize)
        
        return when {
            fileSize > MAX_FILE_SIZE -> {
                context.getString(R.string.file_size_warning, formattedSize)
            }
            fileSize > VERY_LARGE_FILE_THRESHOLD -> {
                context.getString(R.string.file_size_very_large_warning, formattedSize)
            }
            fileSize > LARGE_FILE_THRESHOLD -> {
                context.getString(R.string.file_size_large_warning, formattedSize)
            }
            else -> {
                context.getString(R.string.file_size_ok, formattedSize)
            }
        }
    }
}
