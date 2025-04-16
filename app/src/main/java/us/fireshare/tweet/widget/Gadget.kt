package us.fireshare.tweet.widget

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.google.firebase.crashlytics.buildtools.reloc.org.apache.http.conn.util.InetAddressUtils
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.User
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
     * Annotate HTTP URL and @username in a text. Make both clickable.
     * */
    fun buildAnnotatedText(text: String): AnnotatedString = buildAnnotatedString {
        val urlRegex = "(https?://[\\w.-]+(?:/[\\w.-]*)*)".toRegex()
        val mentionRegex = "@([\\w_]+)".toRegex()
        var lastIndex = 0

        urlRegex.findAll(text).forEach { matchResult ->
            val url = matchResult.value
            val start = matchResult.range.first

            if (start > lastIndex) {
                append(text.substring(lastIndex, start))
            }

            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(
                style = SpanStyle(
                    color = Color.Cyan,
                    textDecoration = TextDecoration.Underline
                )
            ) {
                append(url)
            }
            pop()

            lastIndex = matchResult.range.last + 1
        }

        while (lastIndex < text.length) {
            val mentionMatch = mentionRegex.find(text, lastIndex)
            if (mentionMatch != null) {
                val start = mentionMatch.range.first
                val originalMentionText = mentionMatch.value

                try {
                    val username = mentionMatch.groupValues[1]

                    if (start > lastIndex) {
                        append(text.substring(lastIndex, start))
                    }

                    pushStringAnnotation(tag = "USERNAME_CLICK", annotation = username)
                    withStyle(style = SpanStyle(color = Color.Cyan, textDecoration = TextDecoration.None)) {
                        append(originalMentionText)
                    }
                    pop()

                    lastIndex = mentionMatch.range.last + 1
                } catch (e: Exception) {
                    // If there's an exception handling the mentioned text, append it as is
                    append(originalMentionText)
                    lastIndex = start + originalMentionText.length // or mentionMatch.range.last + 1
                }
            } else {
                append(text.substring(lastIndex))
                lastIndex = text.length
            }
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

        for (i in 0 until nodeList.size) {
            val nodeIps = nodeList[i] as? ArrayList<*> ?: continue
            var ipv6: String? = null
            var ipv4: String? = null

            // First pass: look for a valid IPv4 address
            for (ipData in nodeIps) {
                val pair = ipData as ArrayList<*>
                val ip = pair[0].toString()

                if (!InetAddressUtils.isIPv6Address(ip.getIP()) &&
                    ip.getIP()?.let { isValidPublicIpAddress(it) } == true) {
                    ipv4 = ip
                    break  // Found a valid IPv4, no need to check more IPs for this node
                }
            }

            // If no IPv4 found, look for IPv6
            if (ipv4 == null) {
                for (ipData in nodeIps) {
                    val pair = ipData as ArrayList<*>
                    val ip = pair[0].toString()

                    if (InetAddressUtils.isIPv6Address(ip.getIP())) {
                        ipv6 = ip
                        break  // Found an IPv6, no need to check more
                    }
                }
            }

            // Add the preferred IP to the result list
            when {
                ipv4 != null -> ipAddresses.add(ipv4)
                ipv6 != null -> ipAddresses.add(ipv6)
            }
        }

        return ipAddresses
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
                            HproseInstance.getTweet(tweetId, authorId, "http://$ip")?.let {
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
    fun getAccessibleIP2(ipList: List<String>): String? {
        var ip4: String? = null;
        var ip6: String? = null
        ipList.forEach {
            val i = it.substringBeforeLast(":").trim('[').trim(']')
            val p: Int = it.substringAfterLast(":").toInt()
            // only accept port number in a range
            if (p !in 8000..8999)
                return@forEach
            if (InetAddressUtils.isIPv6Address(i)) {
                ip6 = "[$i]:$p"
            } else {
                // Check for invalid format using a regular expression
                if (!i.matches(Regex("^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"))) {
                    return@forEach
                }
                try {
                    val address = InetAddress.getByName(i) as Inet4Address
                    val addressBytes = address.address

                    // Check if the IP falls within the local IP ranges
                    when {
                        addressBytes[0] == 10.toByte() -> return@forEach // 10.0.0.0/8
                        addressBytes[0] == 172.toByte() && addressBytes[1] in 16..31 -> return@forEach // 172.16.0.0/12
                        addressBytes[0] == 192.toByte() && addressBytes[1] == 168.toByte() -> return@forEach // 192.168.0.0/16
                        else -> ip4 = "$i:$p"
                    }
                } catch (e: Exception) {
                    Timber.tag("isValidIP").e("${e.message}")
                    return@forEach
                }
            }
        }
        return ip4?: ip6
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

    /**
     * Check if a tweet is 70% visible in the screen.
     * */
    fun isElementVisible(layoutCoordinates: LayoutCoordinates, threshold: Int = 50): Boolean {
        val layoutHeight = layoutCoordinates.size.height
        val thresholdHeight = layoutHeight * threshold / 100
        val layoutTop = layoutCoordinates.positionInRoot().y
        val layoutBottom = layoutTop + layoutHeight
        val parent = layoutCoordinates.parentLayoutCoordinates

        parent?.boundsInRoot()?.let { rect: Rect ->
            val parentTop = rect.top
            val parentBottom = rect.bottom

            return parentBottom - layoutTop > thresholdHeight && (parentTop < layoutBottom - thresholdHeight)
        }
        return false
    }
}