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
import androidx.compose.ui.unit.sp
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Check if a string represents an IPv6 address
 */
private fun isIPv6Address(ip: String): Boolean {
    return ip.contains(":") && !ip.matches(Regex("^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"))
}

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
    private fun AnnotatedString.Builder.appendWithBlankLineStyle(segment: String) {
        val blankLineStyle = SpanStyle(fontSize = 2.sp)
        val parts = segment.split("\u200B")
        parts.forEachIndexed { index, part ->
            append(part)
            if (index < parts.size - 1) {
                withStyle(blankLineStyle) { append("\u200B") }
            }
        }
    }

    fun buildAnnotatedText(text: String): AnnotatedString = buildAnnotatedString {
        // Collapse consecutive blank lines: replace \n\n with \n + small-height line
        val processed = text.replace(Regex("\n{2,}")) { "\n\u200B\n" }

        val urlRegex = "(https?://[\\w.-]+(?:/[\\w.-]*)*)".toRegex()
        val mentionRegex = "@([\\w_]+)".toRegex()
        var lastIndex = 0

        urlRegex.findAll(processed).forEach { matchResult ->
            val url = matchResult.value
            val start = matchResult.range.first

            if (start > lastIndex) {
                appendWithBlankLineStyle(processed.substring(lastIndex, start))
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

        while (lastIndex < processed.length) {
            val mentionMatch = mentionRegex.find(processed, lastIndex)
            if (mentionMatch != null) {
                val start = mentionMatch.range.first
                val originalMentionText = mentionMatch.value

                try {
                    val username = mentionMatch.groupValues[1]

                    if (start > lastIndex) {
                        appendWithBlankLineStyle(processed.substring(lastIndex, start))
                    }

                    pushStringAnnotation(tag = "USERNAME_CLICK", annotation = username)
                    withStyle(
                        style = SpanStyle(
                            color = Color.Cyan,
                            textDecoration = TextDecoration.None
                        )
                    ) {
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
                appendWithBlankLineStyle(processed.substring(lastIndex))
                lastIndex = processed.length
            }
        }

    }

    /**
     * Return the IP address with the smallest response time from the available nodes.
     * Only considers public IPs (any port).
     * Treats IPv4 and IPv6 equally.
     * */
    fun filterIpAddresses(nodeList: List<*>): String? {
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

                ip.getIP() ?: continue
                if (ip.substringAfterLast(":", "8080").toIntOrNull() == null) continue

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

    fun isValidPublicIpAddress(fullIp: String): Boolean {
        val ip = fullIp.substringBeforeLast(":").trim('[').trim(']')
        if (isIPv6Address(ip))
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
                    addressBytes[0] == 100.toByte() && (addressBytes[1].toInt() and 0xFF) in 64..127 -> false // RFC 6598 Shared Address Space (Tailscale)
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
    
    /**
     * Calculate visibility ratio (0.0 = completely out of view, 1.0 = fully visible)
     */
    fun calculateVisibilityRatio(layoutCoordinates: LayoutCoordinates): Float {
        val layoutHeight = layoutCoordinates.size.height.toFloat()
        if (layoutHeight <= 0) return 0f
        
        val layoutTop = layoutCoordinates.positionInRoot().y
        val layoutBottom = layoutTop + layoutHeight
        val parent = layoutCoordinates.parentLayoutCoordinates

        parent?.boundsInRoot()?.let { rect: Rect ->
            val parentTop = rect.top
            val parentBottom = rect.bottom
            
            // Calculate intersection
            val visibleTop = kotlin.math.max(layoutTop, parentTop)
            val visibleBottom = kotlin.math.min(layoutBottom, parentBottom)
            val visibleHeight = kotlin.math.max(0f, visibleBottom - visibleTop)
            
            // Return ratio of visible height to total height
            return (visibleHeight / layoutHeight).coerceIn(0f, 1f)
        }
        return 0f
    }
}