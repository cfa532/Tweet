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
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.google.firebase.crashlytics.buildtools.reloc.org.apache.http.conn.util.InetAddressUtils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Extract host IP from a full IP address.
 * */
fun String.getIP(): String? {
    val ipv4Regex = Regex("^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(?::\\d+)?$")
    val ipv6Regex = Regex("\\[(.*?)]")

    if (ipv4Regex.matches(this)) {
        return this
    }

    val ipv6Match = ipv6Regex.find(this)
    return ipv6Match?.groupValues?.get(1)
}

object Gadget {

    /**
     * Annotate HTTP links and @username in a text. Make both clickable.
     * */
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
                    TextLinkStyles(style = SpanStyle(
                        color = Color.Cyan
                    ))
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
            withStyle(style = SpanStyle(color = Color.Cyan, textDecoration = TextDecoration.None)) {
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

    /**
     * Maybe a bug, but toMimeiIdList() return a one-element array, whose only
     * element is a comma separated string of MimeiIds, but it should have been
     * an array of the MimeiIds
     * */
    fun splitJson(arrJson: List<MimeiId>): List<MimeiId>? {
        if (arrJson.isNotEmpty()) {
            val first = arrJson.first()
            if (first.isNotEmpty()) {
                return first.split(",").map { it.trim() }
            }
        }
        return null
    }

    /**
     * Return an array of valid IPs from different serving nodes.
     * One node one IP. Prefer IPv4 over V6 address.
     * */
    fun filterIpAddresses(nodeList: ArrayList<*>): List<String> {
        val ipAddresses = mutableListOf<String>()
        // iterate over the node list
        for (i in 0 until nodeList.size) {
            val nodeIps = nodeList[i] as? ArrayList<*> ?: continue
            var ipv6: String? = null
            var ipv4: String? = null
            // iterate over the node's IP list. Prefer IPv6 address over IPv4.
            nodeIps.forEach lit@{
                val pair = it as ArrayList<*>;
                val ip = pair[0].toString()
                if (InetAddressUtils.isIPv6Address(ip.getIP())) {
                    ipv6 = ip
                } else {
                    if (ip.getIP()?.let { it1 -> isValidPublicIpAddress(it1) } == true) {
                        ipv4 = ip
                        ipAddresses += ip
                        return@lit
                    }
                }
            }
            if (ipv4 == null && ipv6 != null) {
                ipAddresses += ipv6.toString()
            }
        }
        return ipAddresses
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

    /**
     * @return the first User object from the given IP list.
     * */
    suspend fun getAccessibleUser(ipList: List<String>, userId: MimeiId): User? {
        return withTimeoutOrNull(2000L) {
            channelFlow {
                ipList.filter { isValidPublicIpAddress(it) }.forEach { ip ->
                    launch {
                        try {
                            Timber.tag("getAccessibleUser").d("Trying $ip $userId")
                            HproseInstance.getUserCoreData(userId, ip)?.let {
                                send(it) // Emit the user if found
                                cancel()
                            }
                        } catch (e: Exception) {
                            // Handle exceptions, e.g., log or rethrow
                        }
                    }
                }
            }.firstOrNull()?.also { user ->
                /**
                 * Init writableUrl to null, when user makes any data changes,
                 * enforce its baseUrl to writableUrl, so that in memory tweet
                 * can be consistent.
                 * */
                user.writableUrl = null
                Timber.tag("getAccessibleUser").d("Fastest user: $user")
            }
        }
    }

    /**
     * @return the first Tweet object from the given IP list.
     * */
    suspend fun getAccessibleTweet(ipList: List<String>, tweetId: MimeiId, authorId: MimeiId): Tweet? {
        return withTimeoutOrNull(2000L) {
            channelFlow {
                ipList.filter { isValidPublicIpAddress(it) }.forEach { ip ->
                    launch {
                        try {
                            HproseInstance.getTweet(tweetId, authorId, ip)?.let {
                                send(it) // Emit the user if found
                                cancel()
                            }
                        } catch (e: Exception) {
                            // Handle exceptions, e.g., log or rethrow
                        }
                    }
                }
            }.firstOrNull()?.also {
                Timber.tag("getAccessibleTweet").d("$it")
            }
        }
    }

    /**
     * @return the first accessible node from the given IP list.
     * */
    suspend fun getAccessibleIP(ipList: List<String>): String? {
        return withTimeoutOrNull(2000L) {
            channelFlow {
                for (ip in ipList) {
                    if (isValidPublicIpAddress(ip)) {
                        launch {
                            val accessibleIp = HproseInstance.isAccessible(ip) // Get accessible IP
                            if (accessibleIp != null) {
                                send(accessibleIp) // Send the IP if accessible
                                cancel()
                            }
                        }
                    }
                }
            }.firstOrNull()?.also { ip ->
                Timber.tag("getAccessibleIP").d("Fastest ip: $ip")
            }
        }
    }

    private fun isValidPublicIpAddress(fullIp: String): Boolean {
        val ip = fullIp.substringBeforeLast(":").trim('[').trim(']')
        if (InetAddressUtils.isIPv6Address(ip))
            return true
        else {
            // Check for invalid format using a regular expression
            if (!ip.matches(Regex("^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"))) {
                return false // Invalid IP format
            }
            try {
                val address = InetAddress.getByName(ip) as Inet4Address
                val addressBytes = address.address

                // Check if the IP falls within the local IP ranges
                return when {
                    addressBytes[0] == 10.toByte() -> false // 10.0.0.0/8
                    addressBytes[0] == 172.toByte() && addressBytes[1] in 16..31 -> false // 172.16.0.0/12
                    addressBytes[0] == 192.toByte() && addressBytes[1] == 168.toByte() -> false // 192.168.0.0/16
                    else -> true
                }
            } catch (e: Exception) {
                Timber.tag("isValidIP").e("${e.message}")
            }
            return false
        }
    }
}