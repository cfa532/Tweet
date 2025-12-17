package us.fireshare.tweet.video

import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

/**
 * Utility class for compressing HLS files into a zip archive
 */
class ZipCompressor {

    companion object {
        private const val TAG = "ZipCompressor"
        private const val BUFFER_SIZE = 4096 // 4KB buffer for memory efficiency
        private const val LARGE_FILE_THRESHOLD = 10L * 1024 * 1024 // 10MB - use memory mapping for larger files
        private const val MAX_MEMORY_BUFFER = 2L * 1024 * 1024 // 2MB max buffer size
    }

    /**
     * Compress HLS directory into a zip file with memory-efficient streaming
     * @param hlsDirectory Directory containing HLS files
     * @param outputZipFile Output zip file path
     * @return Result indicating success or failure
     */
    fun compressHLSDirectory(
        hlsDirectory: File,
        outputZipFile: File
    ): ZipCompressionResult {
        return try {
            Timber.tag(TAG).d("Starting memory-efficient zip compression of: ${hlsDirectory.absolutePath}")

            if (!hlsDirectory.exists() || !hlsDirectory.isDirectory) {
                return ZipCompressionResult.Error("HLS directory does not exist or is not a directory")
            }

            // Ensure output directory exists
            outputZipFile.parentFile?.mkdirs()

            // Use buffered output stream for better performance
            FileOutputStream(outputZipFile).buffered().use { bufferedOutputStream ->
                ZipOutputStream(bufferedOutputStream).apply {
                    // Set compression level to balance speed vs size
                    // Level 1 = fastest compression, still good compression ratio
                    setLevel(1)
                }.use { zipOutputStream ->
                    // Add all files from the HLS directory to the zip root (no parent directory)
                    addDirectoryToZipStreaming(hlsDirectory, "", zipOutputStream)
                }
            }

            if (outputZipFile.exists() && outputZipFile.length() > 0) {
                Timber.tag(TAG).d("Zip compression completed successfully: ${outputZipFile.absolutePath} (${outputZipFile.length()} bytes)")
                ZipCompressionResult.Success(outputZipFile)
            } else {
                ZipCompressionResult.Error("Zip file was not created or is empty")
            }
        } catch (e: OutOfMemoryError) {
            Timber.tag(TAG).e(e, "Out of memory during zip compression")
            ZipCompressionResult.Error("Out of memory during compression")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during zip compression")
            ZipCompressionResult.Error("Compression error: ${e.message}")
        }
    }

    /**
     * Recursively add directory contents to zip using memory-efficient streaming
     */
    private fun addDirectoryToZipStreaming(
        directory: File,
        basePath: String,
        zipOutputStream: ZipOutputStream
    ) {
        val files = directory.listFiles() ?: return

        // Sort files to ensure consistent zip structure and prioritize small files first
        val sortedFiles = files.sortedWith(compareBy({ it.isFile }, { it.length() }))

        sortedFiles.forEach { file ->
            val entryPath = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"

            if (file.isDirectory) {
                // Add directory entry
                val dirEntry = ZipEntry("$entryPath/")
                zipOutputStream.putNextEntry(dirEntry)
                zipOutputStream.closeEntry()

                // Recursively add directory contents
                addDirectoryToZipStreaming(file, entryPath, zipOutputStream)
            } else {
                // Add file entry with memory-efficient streaming
                addFileToZipStreaming(file, entryPath, zipOutputStream)

                // Optional: Delete file after adding to zip to free memory
                // file.delete() // Uncomment if you want to delete files as they're compressed
            }
        }
    }

    /**
     * Add a single file to zip using memory-efficient streaming
     */
    private fun addFileToZipStreaming(
        file: File,
        entryPath: String,
        zipOutputStream: ZipOutputStream
    ) {
        val fileEntry = ZipEntry(entryPath)
        zipOutputStream.putNextEntry(fileEntry)

        try {
            when {
                // For very large files, use memory-mapped I/O to avoid loading entire file into heap
                file.length() > LARGE_FILE_THRESHOLD -> {
                    addLargeFileToZip(file, zipOutputStream)
                }
                // For medium files, use buffered streaming
                else -> {
                    addRegularFileToZip(file, zipOutputStream)
                }
            }
        } finally {
            zipOutputStream.closeEntry()
        }

        Timber.tag(TAG).d("Added file to zip: $entryPath (${file.length()} bytes)")
    }

    /**
     * Add large files using memory-mapped I/O to minimize heap usage
     */
    private fun addLargeFileToZip(file: File, zipOutputStream: ZipOutputStream) {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fileChannel ->
            val fileSize = fileChannel.size()
            var position = 0L

            // Process file in chunks to avoid mapping entire file at once
            while (position < fileSize) {
                val remaining = fileSize - position
                val chunkSize = minOf(remaining, MAX_MEMORY_BUFFER)

                val mappedBuffer = fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    position,
                    chunkSize
                )

                // Write chunk to zip
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (mappedBuffer.hasRemaining()) {
                    bytesRead = minOf(mappedBuffer.remaining(), BUFFER_SIZE)
                    mappedBuffer.get(buffer, 0, bytesRead)
                    zipOutputStream.write(buffer, 0, bytesRead)
                }

                mappedBuffer.clear()
                position += chunkSize
            }
        }
    }

    /**
     * Add regular files using buffered streaming
     */
    private fun addRegularFileToZip(file: File, zipOutputStream: ZipOutputStream) {
        FileInputStream(file).buffered(BUFFER_SIZE).use { fileInputStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                zipOutputStream.write(buffer, 0, bytesRead)
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
