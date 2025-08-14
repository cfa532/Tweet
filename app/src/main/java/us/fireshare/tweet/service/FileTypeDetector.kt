package us.fireshare.tweet.service

import android.content.Context
import android.net.Uri
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType

/**
 * Utility class for detecting file types using multiple methods:
 * 1. File extension (fastest)
 * 2. File header/magic bytes (most reliable)
 * 3. Android MIME type detection (fallback)
 */
object FileTypeDetector {
    private const val TAG = "FileTypeDetector"

    /**
     * Detect file type using multiple methods with fallback
     * @param context Android context
     * @param uri File URI
     * @param fileName Optional filename for extension-based detection
     * @return Detected MediaType
     */
    fun detectFileType(context: Context, uri: Uri, fileName: String? = null): MediaType {
        Timber.tag(TAG).d("Starting file type detection for URI: $uri, fileName: $fileName")
        
        // Method 1: Try extension-based detection first (fastest)
        fileName?.let { name ->
            val extensionType = detectByExtension(name)
            if (extensionType != MediaType.Unknown) {
                Timber.tag(TAG).d("Detected type by extension: $extensionType for $name")
                return extensionType
            }
        }

        // Method 2: Try magic bytes detection
        try {
            val magicBytesType = detectByMagicBytes(context, uri)
            if (magicBytesType != MediaType.Unknown) {
                Timber.tag(TAG).d("Detected type by magic bytes: $magicBytesType")
                return magicBytesType
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to detect by magic bytes")
        }

        // Method 3: Use Android MIME type detection as final fallback
        try {
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType != null) {
                val androidType = mimeTypeToMediaType(mimeType)
                if (androidType != MediaType.Unknown) {
                    Timber.tag(TAG).d("Detected type by Android MIME: $androidType ($mimeType)")
                    return androidType
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to detect by Android MIME type")
        }

        Timber.tag(TAG).w("Could not detect file type for $uri")
        return MediaType.Unknown
    }

    /**
     * Detect file type by file extension
     */
    private fun detectByExtension(fileName: String): MediaType {
        val extension = fileName.lowercase().substringAfterLast('.', "")
        return when (extension) {
            // Images
            "jpg", "jpeg" -> MediaType.Image
            "png" -> MediaType.Image
            "gif" -> MediaType.Image
            "webp" -> MediaType.Image
            "bmp" -> MediaType.Image
            "tiff", "tif" -> MediaType.Image
            "svg" -> MediaType.Image
            "ico" -> MediaType.Image
            "heic", "heif" -> MediaType.Image

            // Videos
            "mp4" -> MediaType.Video
            "mov" -> MediaType.Video
            "avi" -> MediaType.Video
            "mkv" -> MediaType.Video
            "webm" -> MediaType.Video
            "m4v" -> MediaType.Video
            "3gp", "3gpp" -> MediaType.Video
            "flv" -> MediaType.Video
            "wmv" -> MediaType.Video
            "m3u8" -> MediaType.Video
            "ts" -> MediaType.Video
            "mts" -> MediaType.Video
            "m2ts" -> MediaType.Video

            // Audio
            "mp3" -> MediaType.Audio
            "wav" -> MediaType.Audio
            "aac" -> MediaType.Audio
            "ogg" -> MediaType.Audio
            "flac" -> MediaType.Audio
            "m4a" -> MediaType.Audio
            "wma" -> MediaType.Audio
            "opus" -> MediaType.Audio
            "amr" -> MediaType.Audio
            "aiff", "aif" -> MediaType.Audio

            // Documents
            "pdf" -> MediaType.PDF
            "doc", "docx" -> MediaType.Word
            "xls", "xlsx" -> MediaType.Excel
            "ppt", "pptx" -> MediaType.PPT
            "txt" -> MediaType.Txt
            "rtf" -> MediaType.Txt
            "html", "htm" -> MediaType.Html
            "xml" -> MediaType.Txt
            "json" -> MediaType.Txt
            "csv" -> MediaType.Txt

            // Archives
            "zip" -> MediaType.Zip
            "rar" -> MediaType.Zip
            "7z" -> MediaType.Zip
            "tar" -> MediaType.Zip
            "gz" -> MediaType.Zip
            "bz2" -> MediaType.Zip

            else -> MediaType.Unknown
        }
    }

    /**
     * Detect file type by reading magic bytes (file headers)
     */
    private fun detectByMagicBytes(context: Context, uri: Uri): MediaType {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val buffer = ByteArray(32) // Read first 32 bytes for magic bytes
            val bytesRead = inputStream.read(buffer)
            
            if (bytesRead < 4) {
                Timber.tag(TAG).w("File too small for magic bytes detection: $bytesRead bytes")
                return MediaType.Unknown
            }

            Timber.tag(TAG).d("Read $bytesRead bytes for magic bytes detection")
            
            // Check for common file signatures
            val detectedType = when {
                // Images
                isJPEG(buffer) -> {
                    Timber.tag(TAG).d("Detected JPEG by magic bytes")
                    MediaType.Image
                }
                isPNG(buffer) -> {
                    Timber.tag(TAG).d("Detected PNG by magic bytes")
                    MediaType.Image
                }
                isGIF(buffer) -> {
                    Timber.tag(TAG).d("Detected GIF by magic bytes")
                    MediaType.Image
                }
                isWebP(buffer) -> {
                    Timber.tag(TAG).d("Detected WebP by magic bytes")
                    MediaType.Image
                }
                isBMP(buffer) -> {
                    Timber.tag(TAG).d("Detected BMP by magic bytes")
                    MediaType.Image
                }
                isTIFF(buffer) -> {
                    Timber.tag(TAG).d("Detected TIFF by magic bytes")
                    MediaType.Image
                }
                isHEIC(buffer) -> {
                    Timber.tag(TAG).d("Detected HEIC by magic bytes")
                    MediaType.Image
                }

                // Videos
                isMP4(buffer) -> {
                    Timber.tag(TAG).d("Detected MP4 by magic bytes")
                    MediaType.Video
                }
                isAVI(buffer) -> {
                    Timber.tag(TAG).d("Detected AVI by magic bytes")
                    MediaType.Video
                }
                isMKV(buffer) -> {
                    Timber.tag(TAG).d("Detected MKV by magic bytes")
                    MediaType.Video
                }
                isWebM(buffer) -> {
                    Timber.tag(TAG).d("Detected WebM by magic bytes")
                    MediaType.Video
                }
                is3GP(buffer) -> {
                    Timber.tag(TAG).d("Detected 3GP by magic bytes")
                    MediaType.Video
                }
                isFLV(buffer) -> {
                    Timber.tag(TAG).d("Detected FLV by magic bytes")
                    MediaType.Video
                }
                isWMV(buffer) -> {
                    Timber.tag(TAG).d("Detected WMV by magic bytes")
                    MediaType.Video
                }
                isMOV(buffer) -> {
                    Timber.tag(TAG).d("Detected MOV by magic bytes")
                    MediaType.Video
                }

                // Audio
                isMP3(buffer) -> {
                    Timber.tag(TAG).d("Detected MP3 by magic bytes")
                    MediaType.Audio
                }
                isWAV(buffer) -> {
                    Timber.tag(TAG).d("Detected WAV by magic bytes")
                    MediaType.Audio
                }
                isAAC(buffer) -> {
                    Timber.tag(TAG).d("Detected AAC by magic bytes")
                    MediaType.Audio
                }
                isOGG(buffer) -> {
                    Timber.tag(TAG).d("Detected OGG by magic bytes")
                    MediaType.Audio
                }
                isFLAC(buffer) -> {
                    Timber.tag(TAG).d("Detected FLAC by magic bytes")
                    MediaType.Audio
                }
                isM4A(buffer) -> {
                    Timber.tag(TAG).d("Detected M4A by magic bytes")
                    MediaType.Audio
                }

                // Documents
                isPDF(buffer) -> {
                    Timber.tag(TAG).d("Detected PDF by magic bytes")
                    MediaType.PDF
                }
                isZIP(buffer) -> {
                    Timber.tag(TAG).d("Detected ZIP by magic bytes")
                    MediaType.Zip
                }
                isRAR(buffer) -> {
                    Timber.tag(TAG).d("Detected RAR by magic bytes")
                    MediaType.Zip
                }
                is7Z(buffer) -> {
                    Timber.tag(TAG).d("Detected 7Z by magic bytes")
                    MediaType.Zip
                }

                else -> {
                    Timber.tag(TAG).w("No magic bytes signature matched")
                    MediaType.Unknown
                }
            }
            
            return detectedType
        }
        return MediaType.Unknown
    }

    /**
     * Convert MIME type to MediaType
     */
    private fun mimeTypeToMediaType(mimeType: String): MediaType {
        return when {
            mimeType.startsWith("image/") -> MediaType.Image
            mimeType.startsWith("video/") -> MediaType.Video
            mimeType.startsWith("audio/") -> MediaType.Audio
            mimeType == "application/pdf" -> MediaType.PDF
            mimeType.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml") ||
            mimeType.startsWith("application/msword") -> MediaType.Word
            mimeType.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml") ||
            mimeType.startsWith("application/vnd.ms-excel") -> MediaType.Excel
            mimeType.startsWith("application/vnd.openxmlformats-officedocument.presentationml") ||
            mimeType.startsWith("application/vnd.ms-powerpoint") -> MediaType.PPT
            mimeType.startsWith("application/zip") ||
            mimeType.startsWith("application/x-rar-compressed") ||
            mimeType.startsWith("application/x-7z-compressed") -> MediaType.Zip
            mimeType.startsWith("text/") -> MediaType.Txt
            else -> MediaType.Unknown
        }
    }

    // Magic bytes detection methods
    private fun isJPEG(buffer: ByteArray): Boolean {
        return buffer.size >= 2 && buffer[0] == 0xFF.toByte() && buffer[1] == 0xD8.toByte()
    }

    private fun isPNG(buffer: ByteArray): Boolean {
        return buffer.size >= 8 && 
               buffer[0] == 0x89.toByte() && buffer[1] == 0x50.toByte() && 
               buffer[2] == 0x4E.toByte() && buffer[3] == 0x47.toByte() &&
               buffer[4] == 0x0D.toByte() && buffer[5] == 0x0A.toByte() && 
               buffer[6] == 0x1A.toByte() && buffer[7] == 0x0A.toByte()
    }

    private fun isGIF(buffer: ByteArray): Boolean {
        return buffer.size >= 6 && 
               buffer[0] == 0x47.toByte() && buffer[1] == 0x49.toByte() && 
               buffer[2] == 0x46.toByte() && buffer[3] == 0x38.toByte() &&
               (buffer[4] == 0x37.toByte() || buffer[4] == 0x39.toByte()) && 
               buffer[5] == 0x61.toByte()
    }

    private fun isWebP(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[0] == 0x52.toByte() && buffer[1] == 0x49.toByte() && 
               buffer[2] == 0x46.toByte() && buffer[3] == 0x46.toByte() &&
               buffer[8] == 0x57.toByte() && buffer[9] == 0x45.toByte() && 
               buffer[10] == 0x42.toByte() && buffer[11] == 0x50.toByte()
    }

    private fun isBMP(buffer: ByteArray): Boolean {
        return buffer.size >= 2 && buffer[0] == 0x42.toByte() && buffer[1] == 0x4D.toByte()
    }

    private fun isTIFF(buffer: ByteArray): Boolean {
        return buffer.size >= 4 && 
               ((buffer[0] == 0x49.toByte() && buffer[1] == 0x49.toByte() && 
                 buffer[2] == 0x2A.toByte() && buffer[3] == 0x00.toByte()) ||
                (buffer[0] == 0x4D.toByte() && buffer[1] == 0x4D.toByte() && 
                 buffer[2] == 0x00.toByte() && buffer[3] == 0x2A.toByte()))
    }

    private fun isHEIC(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[4] == 0x66.toByte() && buffer[5] == 0x74.toByte() && 
               buffer[6] == 0x79.toByte() && buffer[7] == 0x70.toByte() &&
               buffer[8] == 0x68.toByte() && buffer[9] == 0x65.toByte() && 
               buffer[10] == 0x69.toByte() && buffer[11] == 0x63.toByte()
    }

    private fun isMP4(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[4] == 0x66.toByte() && buffer[5] == 0x74.toByte() && 
               buffer[6] == 0x79.toByte() && buffer[7] == 0x70.toByte() &&
               (buffer[8] == 0x69.toByte() && buffer[9] == 0x73.toByte() && 
                buffer[10] == 0x6F.toByte() && buffer[11] == 0x6D.toByte() ||
                buffer[8] == 0x4D.toByte() && buffer[9] == 0x34.toByte() && 
                buffer[10] == 0x56.toByte() && buffer[11] == 0x20.toByte())
    }

    private fun isAVI(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[0] == 0x52.toByte() && buffer[1] == 0x49.toByte() && 
               buffer[2] == 0x46.toByte() && buffer[3] == 0x46.toByte() &&
               buffer[8] == 0x41.toByte() && buffer[9] == 0x56.toByte() && 
               buffer[10] == 0x49.toByte() && buffer[11] == 0x20.toByte()
    }

    private fun isMKV(buffer: ByteArray): Boolean {
        return buffer.size >= 4 && 
               buffer[0] == 0x1A.toByte() && buffer[1] == 0x45.toByte() && 
               buffer[2] == 0xDF.toByte() && buffer[3] == 0xA3.toByte()
    }

    private fun isWebM(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[0] == 0x1A.toByte() && buffer[1] == 0x45.toByte() && 
               buffer[2] == 0xDF.toByte() && buffer[3] == 0xA3.toByte() &&
               buffer[4] == 0x01.toByte() && buffer[5] == 0x00.toByte() && 
               buffer[6] == 0x00.toByte() && buffer[7] == 0x00.toByte() &&
               buffer[8] == 0x00.toByte() && buffer[9] == 0x00.toByte() && 
               buffer[10] == 0x00.toByte() && buffer[11] == 0x00.toByte()
    }

    private fun is3GP(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[4] == 0x66.toByte() && buffer[5] == 0x74.toByte() && 
               buffer[6] == 0x79.toByte() && buffer[7] == 0x70.toByte() &&
               buffer[8] == 0x33.toByte() && buffer[9] == 0x67.toByte() && 
               buffer[10] == 0x70.toByte() && buffer[11] == 0x34.toByte()
    }

    private fun isFLV(buffer: ByteArray): Boolean {
        return buffer.size >= 4 && 
               buffer[0] == 0x46.toByte() && buffer[1] == 0x4C.toByte() && 
               buffer[2] == 0x56.toByte() && buffer[3] == 0x01.toByte()
    }

    private fun isWMV(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[0] == 0x30.toByte() && buffer[1] == 0x26.toByte() && 
               buffer[2] == 0xB2.toByte() && buffer[3] == 0x75.toByte() &&
               buffer[4] == 0x8E.toByte() && buffer[5] == 0x66.toByte() && 
               buffer[6] == 0xCF.toByte() && buffer[7] == 0x11.toByte() &&
               buffer[8] == 0xA6.toByte() && buffer[9] == 0xD9.toByte() && 
               buffer[10] == 0x00.toByte() && buffer[11] == 0xAA.toByte()
    }

    private fun isMOV(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[4] == 0x66.toByte() && buffer[5] == 0x74.toByte() && 
               buffer[6] == 0x79.toByte() && buffer[7] == 0x70.toByte() &&
               buffer[8] == 0x71.toByte() && buffer[9] == 0x74.toByte() && 
               buffer[10] == 0x20.toByte() && buffer[11] == 0x20.toByte()
    }

    private fun isMP3(buffer: ByteArray): Boolean {
        return buffer.size >= 3 && 
               buffer[0] == 0x49.toByte() && buffer[1] == 0x44.toByte() && 
               buffer[2] == 0x33.toByte()
    }

    private fun isWAV(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[0] == 0x52.toByte() && buffer[1] == 0x49.toByte() && 
               buffer[2] == 0x46.toByte() && buffer[3] == 0x46.toByte() &&
               buffer[8] == 0x57.toByte() && buffer[9] == 0x41.toByte() && 
               buffer[10] == 0x56.toByte() && buffer[11] == 0x45.toByte()
    }

    private fun isAAC(buffer: ByteArray): Boolean {
        return buffer.size >= 2 && 
               (buffer[0] == 0xFF.toByte() && (buffer[1] == 0xF1.toByte() || buffer[1] == 0xF9.toByte()))
    }

    private fun isOGG(buffer: ByteArray): Boolean {
        return buffer.size >= 4 && 
               buffer[0] == 0x4F.toByte() && buffer[1] == 0x67.toByte() && 
               buffer[2] == 0x67.toByte() && buffer[3] == 0x53.toByte()
    }

    private fun isFLAC(buffer: ByteArray): Boolean {
        return buffer.size >= 4 && 
               buffer[0] == 0x66.toByte() && buffer[1] == 0x4C.toByte() && 
               buffer[2] == 0x61.toByte() && buffer[3] == 0x43.toByte()
    }

    private fun isM4A(buffer: ByteArray): Boolean {
        return buffer.size >= 12 && 
               buffer[4] == 0x66.toByte() && buffer[5] == 0x74.toByte() && 
               buffer[6] == 0x79.toByte() && buffer[7] == 0x70.toByte() &&
               buffer[8] == 0x4D.toByte() && buffer[9] == 0x34.toByte() && 
               buffer[10] == 0x41.toByte() && buffer[11] == 0x20.toByte()
    }

    private fun isPDF(buffer: ByteArray): Boolean {
        return buffer.size >= 4 && 
               buffer[0] == 0x25.toByte() && buffer[1] == 0x50.toByte() && 
               buffer[2] == 0x44.toByte() && buffer[3] == 0x46.toByte()
    }

    private fun isZIP(buffer: ByteArray): Boolean {
        return buffer.size >= 4 && 
               buffer[0] == 0x50.toByte() && buffer[1] == 0x4B.toByte() && 
               buffer[2] == 0x03.toByte() && buffer[3] == 0x04.toByte()
    }

    private fun isRAR(buffer: ByteArray): Boolean {
        return buffer.size >= 4 && 
               buffer[0] == 0x52.toByte() && buffer[1] == 0x61.toByte() && 
               buffer[2] == 0x72.toByte() && buffer[3] == 0x21.toByte()
    }

    private fun is7Z(buffer: ByteArray): Boolean {
        return buffer.size >= 6 && 
               buffer[0] == 0x37.toByte() && buffer[1] == 0x7A.toByte() && 
               buffer[2] == 0xBC.toByte() && buffer[3] == 0xAF.toByte() &&
               buffer[4] == 0x27.toByte() && buffer[5] == 0x1C.toByte()
    }
}
