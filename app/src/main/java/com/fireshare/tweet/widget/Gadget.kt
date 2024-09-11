package com.fireshare.tweet.widget

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileNotFoundException
import java.io.IOException

object Gadget {
    suspend fun uploadAttachments(context: Context, attachments: List<Uri>): List<MimeiId> {
        return attachments.map { uri ->
            withContext(IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        HproseInstance.uploadToIPFS(inputStream)
                    } ?: throw FileNotFoundException("File not found for URI: $uri")
                }.getOrElse { e ->
                    Log.e("uploadAttachments", "Failed to upload file: $uri", e)
                    throw IOException("Upload failed.")
                }
            }
        }
    }

    fun downloadFileHeader(url: String, byteCount: Int = 1024): ByteArray? {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val contentLength = response.header("Content-Length")?.toInt() ?: byteCount
                val requestWithRange = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=0-${minOf(contentLength, byteCount - 1)}")
                    .build()
                val responseWithRange = client.newCall(requestWithRange).execute()
                responseWithRange.body?.byteStream()?.readBytes()
            } else {
                null
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun detectMimeTypeFromHeader(header: ByteArray?): String? {
        if (header == null) return null
        return when {
            header.startsWith(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)) -> "video/mp4"
            header.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) -> "image/jpeg"
            header.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) -> "image/png"
            header.startsWith(byteArrayOf(0x49, 0x44, 0x33)) -> "audio/mpeg" // MP3
            header.startsWith(byteArrayOf(0x4F, 0x67, 0x67, 0x53)) -> "audio/ogg" // OGG
            header.startsWith(byteArrayOf(0x66, 0x4C, 0x61, 0x43)) -> "audio/flac" // FLAC
            header.startsWith(byteArrayOf(0x52, 0x49, 0x46, 0x46)) && header.sliceArray(8..11).contentEquals(byteArrayOf(0x57, 0x41, 0x56, 0x45)) -> "audio/wav" // WAV
            else -> "application/octet-stream"
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    // In Pair<URL, String?>?, where String is JSON of Mimei content
    suspend fun getFirstReachableUri(ipList: List<JsonArray>, mid: MimeiId): User? = coroutineScope {
        val ips = ipList.map { ip ->
            async {
                HproseInstance.isReachable(mid, removeParentheses(ip[0]))
            }
        }
        ips.awaitAll().firstOrNull { it != null }
    }

    private fun removeParentheses(jsonElement: JsonElement): String {
        return when (jsonElement) {
            is JsonPrimitive -> jsonElement.content
            is JsonObject -> jsonElement.toString() // For objects, you might want to handle specific properties
            is JsonArray -> jsonElement.joinToString(", ") { removeParentheses(it) } // Recursively handle array elements
            else -> "" // Handle other types as needed
        }
    }
}