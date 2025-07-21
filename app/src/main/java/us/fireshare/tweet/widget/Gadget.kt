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
     * Return the IP address with the smallest response time from the available nodes.
     * Only considers public IPs with ports between 8000 and 9000.
     * Treats IPv4 and IPv6 equally.
     * */
    fun filterIpAddresses(nodeList: List<String>): String? {
        var bestIp: String? = null
        var bestResponseTime = Double.MAX_VALUE

        for (i in 0 until nodeList.size) {
            val nodeIps = nodeList[i] as? ArrayList<*> ?: continue
            
            for (ipData in nodeIps) {
                val pair = ipData as? ArrayList<*> ?: continue
                if (pair.size < 2) continue
                
                val ip = pair[0].toString()
                val responseTimeStr = pair[1].toString()
                
                // Parse response time (scientific format)
                val responseTime = try {
                    responseTimeStr.toDouble()
                } catch (e: NumberFormatException) {
                    continue // Skip invalid response time
                }
                
                // Check if IP is valid and has correct port range
                val ipOnly = ip.getIP() ?: continue
                val port = ip.substringAfterLast(":", "8080").toIntOrNull() ?: continue
                
                if (port !in 8000..9000) continue
                
                // Check if it's a public IP
                if (!isValidPublicIpAddress(ip)) continue
                
                // Determine if this IP is better than the current best
                val isBetter = when {
                    bestIp == null -> true // First valid IP
                    responseTime < bestResponseTime -> true // Faster response time
                    else -> false
                }
                
                if (isBetter) {
                    bestIp = ip
                    bestResponseTime = responseTime
                }
            }
        }

        return bestIp
    }

    /**
     * Filters a list of IP addresses to find the best accessible public IP address.
     * 
     * This function processes a list of IP:port combinations and returns the first valid
     * public IP address that meets the following criteria:
     * - Port number is in the valid range (8000-8999)
     * - IP address is a valid public IP (not local/private network)
     * - IPv4 addresses are preferred over IPv6
     * 
     * @param ipList List of IP:port strings to filter (e.g., ["192.168.1.1:8010", "203.0.113.1:8010"])
     * @return The first valid public IP:port string, or null if no valid IPs found
     */
    fun getAccessibleIP2(ipList: List<String>): String? {
        var ip4: String? = null  // Store the first valid IPv4 address
        var ip6: String? = null  // Store the first valid IPv6 address
        
        ipList.forEach { ipPortString ->
            // Extract IP address and port from "IP:port" format
            val ip = ipPortString.substringBeforeLast(":").trim('[').trim(']')
            val port: Int = ipPortString.substringAfterLast(":").toInt()
            
            // Only accept port numbers in the valid range (8000-8999)
            if (port !in 8000..8999) {
                return@forEach  // Skip this IP if port is invalid
            }
            
            if (InetAddressUtils.isIPv6Address(ip)) {
                // Handle IPv6 addresses
                ip6 = "[$ip]:$port"
            } else {
                // Handle IPv4 addresses
                
                // Validate IPv4 format using regex pattern
                // Pattern matches: xxx.xxx.xxx.xxx where each xxx is 0-255
                if (!ip.matches(Regex("^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"))) {
                    return@forEach  // Skip if IP format is invalid
                }
                
                try {
                    val address = InetAddress.getByName(ip) as Inet4Address
                    val addressBytes = address.address

                    // Filter out private/local network IP addresses
                    // Only accept public IP addresses for external connectivity
                    when {
                        addressBytes[0] == 10.toByte() -> return@forEach  // 10.0.0.0/8 (private network)
                        addressBytes[0] == 172.toByte() && addressBytes[1] in 16..31 -> return@forEach  // 172.16.0.0/12 (private network)
                        addressBytes[0] == 192.toByte() && addressBytes[1] == 168.toByte() -> return@forEach  // 192.168.0.0/16 (private network)
                        else -> ip4 = "$ip:$port"  // Valid public IPv4 address
                    }
                } catch (e: Exception) {
                    Timber.tag("isValidIP").e("${e.message}")
                    return@forEach  // Skip if IP resolution fails
                }
            }
        }
        
        // Return IPv4 if available, otherwise return IPv6, or null if neither found
        return ip4 ?: ip6
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