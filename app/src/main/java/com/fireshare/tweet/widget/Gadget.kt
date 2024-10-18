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

object Gadget {

    /**
     * NodeList format:
     * [
     *   [["183.159.17.7:8081", 3080655111],["[240e:391:e00:169:a04c:d387:95d:a689]:8081", 39642842857833],["192.168.0.94:8081", 281478208946270]],    // node 1
     *   [["183.159.17.7:8082", 3080655111],["[240e:391:e00:169:2e0:1dff:feed:3d1]:8082", 39642842857833]]     // node 2
     * ]
     * Get the IP with the smallest response time, from a list of nodes and each node has
     * multiple IP addresses.
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

    /**
     * Return an array of IPs, each from a different server.
     * */
    fun getIpAddresses(nodeList: ArrayList<*>): List<String> {
        val ipAddresses = mutableListOf<String>()
        for (i in 0 until nodeList.size) {
            val nodeIps = nodeList[i] as? ArrayList<*> ?: continue
            val ipAddress = getPreferredIpAddress(nodeIps)
            ipAddresses.add(ipAddress)
        }
        return ipAddresses
    }

    /**
     * Get the IP with the smallest response time. No network call.
     * */
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

    // In Pair<URL, String?>?, where String is JSON of Mimei content
    suspend fun getFirstReachableUser(ipList: List<String>, mid: MimeiId): User? = coroutineScope {
        val ips = ipList.map { ip ->
            Log.d("getFirstReachableUser","trying $ip")
            async {
                HproseInstance.getUserData(mid, ip)
            }
        }
        ips.awaitAll().firstOrNull { it != null }
    }

    suspend fun findFirstReachableAddress(ipList: List<String>): String? = coroutineScope {
        val ips = ipList.map { ip ->
            Log.d("getFirstReachableUser","trying $ip")
            async {
                if (HproseInstance.isReachable(ip) != null)
                    ip
                else null
            }
        }
        ips.awaitAll().firstOrNull { it != null }
    }
}