package com.fireshare.tweet.widget

import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

object Gadget {

    fun buildAnnotatedText(text: String) = buildAnnotatedString {
        val urlRegex = "(https?://[\\w.-]+(?:/[\\w.-]*)*)".toRegex()
        val mentionRegex = "@([\\w_]+)".toRegex()
        var lastIndex = 0

        urlRegex.findAll(text).forEach { matchResult ->
            val url = matchResult.value
            val start = matchResult.range.first

            // Append text before the URL
            append(text.substring(lastIndex, start))

            // Apply URL span
            pushStringAnnotation(tag = "URL", annotation = url)
            withLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(style = SpanStyle(color = Color.Blue))
                )
            ) {
                append(url)
            }
            pop()

            // Update lastIndex to the end of the current URL
            lastIndex = matchResult.range.last + 1
        }
        // Process mentions (@username)
        mentionRegex.findAll(text.substring(lastIndex)).forEach { matchResult ->
            val username = matchResult.groupValues[1]
            val start = lastIndex + matchResult.range.first
            val originalMentionText = matchResult.value // Get the original mention text

            append(text.substring(lastIndex, start))

            // Apply style and annotation for all mentions
            pushStringAnnotation(tag = "USERNAME_CLICK", annotation = username)
            withStyle(style = SpanStyle(color = Color.Blue, textDecoration = TextDecoration.None)) {
                append("@$username")
            }
            pop()

            // Update lastIndex to the start of the mention plus the length of the original mention text
            lastIndex = start + originalMentionText.length
        }

        // Append any remaining text after the last URL
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }

    fun splitJson(arrJson: List<MimeiId>): List<MimeiId>? {
        /**
         * Maybe a bug, but toMimeiIdList() return a one-element array, whose only
         * element is a comma separated string of MimeiIds, that should have been
         * the elements of the returned array.
         * */
        if (arrJson.isNotEmpty()) {
            val first = arrJson.first()
            if (first.isNotEmpty()) {
                return first.split(",").map { it.trim() }
            }
        }
        return null
    }

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
                Timber.tag("GetVideoDimensions").e(e)
                null
            }
        }
    }

    // In Pair<URL, String?>?, where String is JSON of Mimei content
    suspend fun getFirstReachableUser(ipList: List<String>, mid: MimeiId): User? = coroutineScope {
        val ips = ipList.map { ip ->
            Timber.tag("getFirstReachableUser").d("trying $ip")
            async {
                HproseInstance.getUserData(mid, ip)
            }
        }
        ips.awaitAll().firstOrNull { it != null }
    }

    suspend fun findFirstReachableAddress(ipList: List<String>): String? = coroutineScope {
        val ips = ipList.map { ip ->
            Timber.tag("getFirstReachableUser").d("trying $ip")
            async {
                if (HproseInstance.isReachable(ip) != null)
                    ip
                else null
            }
        }
        ips.awaitAll().firstOrNull { it != null }
    }
}