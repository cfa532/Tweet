package us.fireshare.tweet.datamodel

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import timber.log.Timber
import java.lang.reflect.Type

/**
 * Custom Gson deserializer for MediaType enum
 * Handles string values from backend that don't directly match enum names
 */
class MediaTypeDeserializer : JsonDeserializer<MediaType> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): MediaType {
        if (json == null || json.isJsonNull) {
            Timber.d("MediaTypeDeserializer: null or null JSON, returning Unknown")
            return MediaType.Unknown
        }

        val typeString = json.asString.lowercase()
        Timber.d("MediaTypeDeserializer: deserializing '$typeString'")

        return when {
            // Handle exact enum matches
            typeString == "image" -> MediaType.Image
            typeString == "video" -> MediaType.Video
            typeString == "hls_video" -> MediaType.HLS_VIDEO
            typeString == "audio" -> MediaType.Audio
            typeString == "pdf" -> MediaType.PDF
            typeString == "word" -> MediaType.Word
            typeString == "excel" -> MediaType.Excel
            typeString == "ppt" -> MediaType.PPT
            typeString == "zip" -> MediaType.Zip
            typeString == "txt" -> MediaType.Txt
            typeString == "html" -> MediaType.Html
            typeString == "unknown" -> MediaType.Unknown
            
            // Handle backend-specific string values
            typeString.contains("hls") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.HLS_VIDEO")
                MediaType.HLS_VIDEO
            }
            typeString.contains("video") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.Video")
                MediaType.Video
            }
            typeString.contains("image") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.Image")
                MediaType.Image
            }
            typeString.contains("audio") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.Audio")
                MediaType.Audio
            }
            typeString.contains("pdf") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.PDF")
                MediaType.PDF
            }
            typeString.contains("word") || typeString.contains("doc") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.Word")
                MediaType.Word
            }
            typeString.contains("excel") || typeString.contains("xls") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.Excel")
                MediaType.Excel
            }
            typeString.contains("ppt") || typeString.contains("powerpoint") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.PPT")
                MediaType.PPT
            }
            typeString.contains("zip") || typeString.contains("rar") || typeString.contains("7z") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.Zip")
                MediaType.Zip
            }
            typeString.contains("txt") || typeString.contains("text") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.Txt")
                MediaType.Txt
            }
            typeString.contains("html") || typeString.contains("htm") -> {
                Timber.d("Converting backend type '$typeString' to MediaType.Html")
                MediaType.Html
            }
            else -> {
                Timber.w("MediaTypeDeserializer: unknown type '$typeString', returning Unknown")
                MediaType.Unknown
            }
        }
    }
}
