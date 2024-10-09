package com.fireshare.tweet.widget

import android.media.MediaMetadataRetriever
import android.util.Log
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object Gadget {

    /**
     * NodeList format:
     * [
     *   [["183.159.17.7:8081", 3080655111],["[240e:391:e00:169:a04c:d387:95d:a689]:8081", 39642842857833],["192.168.0.94:8081", 281478208946270]],    // node 1
     *   [["183.159.17.7:8082", 3080655111],["[240e:391:e00:169:2e0:1dff:feed:3d1]:8082", 39642842857833]]     // node 2
     * ]
     * */
    fun getBestIPAddress(nodeList: ArrayList<*>) =
         nodeList.map {
            (it as ArrayList<*>).associate { it1 ->
                val pair = it1 as ArrayList<*>;
                pair[0] to pair[1]
            }.minByOrNull { it2 -> it2.value as Double }
        }.associate {
            it?.key to it?.value
        }.minByOrNull { it2 -> it2.value as Double }?.key as String

    fun getIpAddresses(nodeList: ArrayList<*>): List<String> {
        val ipAddresses = mutableListOf<String>()
        for (i in 0 until nodeList.size) {
            val nodeIps = nodeList[i] as? ArrayList<*> ?: continue
            val ipAddress = getPreferredIpAddress(nodeIps)
            ipAddresses.add(ipAddress)
        }
        return ipAddresses
    }

    private fun getPreferredIpAddress(ipList: ArrayList<*>): String {
        // Turn the IP list into a map of {IP: ResponseTime} and get the one with smallest response time.
        // ["183.159.17.7:8081", 3.080655111],["[240e:391:e00:169:1458:aa58:c381:5c85]:8081", 3.9642842857833]
        val ipMap = ipList.associate {
            val pair = it as ArrayList<*>;
            pair[0] to pair[1]
        }
        val ip = ipMap.minByOrNull { it.value as Double }?.key
        return ip.toString()
    }

    suspend fun getVideoDimensions(videoUrl: String): Pair<Int, Int>? {
        return withContext(IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoUrl, HashMap())
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                retriever.release()
                if (width != null && height != null) {
                    Pair(width, height)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun downloadFileHeader(url: String, byteCount: Int = 128): ByteArray? {
        val client = HproseInstance.httpClient
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
            Log.e("downloadFileHeader", "Failed: $url")
            null
        }
    }

    fun detectMimeTypeFromHeader(header: ByteArray?): String? {
        if (header == null) return null
        return when {
            header.startsWith(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)
            ) -> "video/mp4"

            header.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) -> "image/jpeg"
            header.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) -> "image/png"
            header.startsWith(byteArrayOf(0x49, 0x44, 0x33)) -> "audio/mpeg" // MP3
            header.startsWith(byteArrayOf(0x4F, 0x67, 0x67, 0x53)) -> "audio/ogg" // OGG
            header.startsWith(byteArrayOf(0x66, 0x4C, 0x61, 0x43)) -> "audio/flac" // FLAC
            header.startsWith(byteArrayOf(0x52, 0x49, 0x46, 0x46)) && header.sliceArray(8..11)
                .contentEquals(byteArrayOf(0x57, 0x41, 0x56, 0x45)) -> "audio/wav" // WAV
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
    suspend fun getFirstReachableUser(ipList: List<String>, mid: MimeiId): User? = coroutineScope {
        val ips = ipList.map { ip ->
            Log.d("GetFirstURL","trying $ip")
            async {
                HproseInstance.getUserData(mid, ip)
            }
        }
        ips.awaitAll().firstOrNull { it != null }
    }

    suspend fun findFirstReachableAddress(hostIps: List<String>): String? {
        return withContext(IO) {
            hostIps.firstOrNull { hostIp ->
                try {
                    val socket = Socket()
                    val pair = hostIp.split(":")
                    socket.connect(InetSocketAddress(pair[0], pair[1].toInt()), 5000) // Timeout of 5 seconds
                    socket.close()
                    true // Reachable
                } catch (e: Exception) {
                    false // Not reachable
                }
            }
        }
    }
}