package us.fireshare.tweet.video

import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Utility class for compressing HLS files into a zip archive
 */
class ZipCompressor {

    companion object {
        private const val TAG = "ZipCompressor"
    }

    /**
     * Compress HLS directory into a zip file
     * @param hlsDirectory Directory containing HLS files
     * @param outputZipFile Output zip file path
     * @return Result indicating success or failure
     */
    fun compressHLSDirectory(
        hlsDirectory: File,
        outputZipFile: File
    ): ZipCompressionResult {
        return try {
            Timber.tag(TAG).d("Starting zip compression of: ${hlsDirectory.absolutePath}")
            
            if (!hlsDirectory.exists() || !hlsDirectory.isDirectory) {
                return ZipCompressionResult.Error("HLS directory does not exist or is not a directory")
            }

            // Ensure output directory exists
            outputZipFile.parentFile?.mkdirs()

            FileOutputStream(outputZipFile).use { fileOutputStream ->
                ZipOutputStream(fileOutputStream).use { zipOutputStream ->
                    // Add all files from the HLS directory to the zip root (no parent directory)
                    addDirectoryToZip(hlsDirectory, "", zipOutputStream)
                }
            }

            if (outputZipFile.exists() && outputZipFile.length() > 0) {
                Timber.tag(TAG).d("Zip compression completed successfully: ${outputZipFile.absolutePath}")
                ZipCompressionResult.Success(outputZipFile)
            } else {
                ZipCompressionResult.Error("Zip file was not created or is empty")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during zip compression")
            ZipCompressionResult.Error("Compression error: ${e.message}")
        }
    }

    /**
     * Recursively add directory contents to zip
     */
    private fun addDirectoryToZip(
        directory: File,
        basePath: String,
        zipOutputStream: ZipOutputStream
    ) {
        directory.listFiles()?.forEach { file ->
            val entryPath = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
            
            if (file.isDirectory) {
                // Add directory entry
                val dirEntry = ZipEntry("$entryPath/")
                zipOutputStream.putNextEntry(dirEntry)
                zipOutputStream.closeEntry()
                
                // Recursively add directory contents
                addDirectoryToZip(file, entryPath, zipOutputStream)
            } else {
                // Add file entry
                val fileEntry = ZipEntry(entryPath)
                zipOutputStream.putNextEntry(fileEntry)
                
                FileInputStream(file).use { fileInputStream ->
                    fileInputStream.copyTo(zipOutputStream)
                }
                
                zipOutputStream.closeEntry()
                Timber.tag(TAG).d("Added file to zip: $entryPath")
            }
        }
    }

    /**
     * Result of zip compression
     */
    sealed class ZipCompressionResult {
        data class Success(val zipFile: File) : ZipCompressionResult()
        data class Error(val message: String) : ZipCompressionResult()
    }
}
