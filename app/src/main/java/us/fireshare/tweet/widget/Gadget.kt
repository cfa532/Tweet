package us.fireshare.tweet.widget

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

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
     */

    // Default color for links / @mentions when no explicit color is supplied.
    // Calmer than the previous `Color.Cyan` (#00FFFF), which was over-saturated
    // on light backgrounds. Material Blue 700.
    private val DEFAULT_LINK_COLOR = Color(0xFF1976D2)

    // Default line-height applied to the placeholder paragraph that stands in
    // for one or more blank lines in the source text. Roughly half of a
    // typical body lineHeight (~20.sp), giving visible-but-tight paragraph
    // separation. Callers can override per-style via [buildAnnotatedText].
    private val DEFAULT_BLANK_LINE_HEIGHT: TextUnit = 10.sp

    private fun AnnotatedString.Builder.appendWithBlankLineStyle(
        segment: String,
        blankLineHeight: TextUnit,
    ) {
        // The blank-line placeholder is a zero-width space wrapped in its own
        // ParagraphStyle so the paragraph's lineHeight overrides the parent
        // Text's lineHeight for that single line. SpanStyle.fontSize alone
        // does NOT shrink the line — the surrounding TextStyle's lineHeight
        // pins every line to the same height.
        val parts = segment.split("\u200B")
        parts.forEachIndexed { index, part ->
            append(part)
            if (index < parts.size - 1) {
                withStyle(ParagraphStyle(lineHeight = blankLineHeight)) {
                    append("\u200B")
                }
            }
        }
    }

    fun buildAnnotatedText(
        text: String,
        linkColor: Color = DEFAULT_LINK_COLOR,
        blankLineHeight: TextUnit = DEFAULT_BLANK_LINE_HEIGHT,
    ): AnnotatedString = buildAnnotatedString {
        // Collapse runs of blank lines to a single ZWSP marker. No surrounding
        // \n is needed because the ParagraphStyle around the marker already
        // produces its own paragraph break before and after; adding \n on top
        // of that would render as multiple line breaks.
        val processed = text.replace(Regex("\n{2,}"), "\u200B")

        val urlRegex = "(https?://[\\w.-]+(?:/[\\w.-]*)*)".toRegex()
        val mentionRegex = "@([\\w_]+)".toRegex()
        var lastIndex = 0

        urlRegex.findAll(processed).forEach { matchResult ->
            val url = matchResult.value
            val start = matchResult.range.first

            if (start > lastIndex) {
                appendWithBlankLineStyle(processed.substring(lastIndex, start), blankLineHeight)
            }

            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(
                style = SpanStyle(
                    color = linkColor,
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
                        appendWithBlankLineStyle(processed.substring(lastIndex, start), blankLineHeight)
                    }

                    pushStringAnnotation(tag = "USERNAME_CLICK", annotation = username)
                    withStyle(
                        style = SpanStyle(
                            color = linkColor,
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
                appendWithBlankLineStyle(processed.substring(lastIndex), blankLineHeight)
                lastIndex = processed.length
            }
        }

    }

    /**
     * Entry candidates ordered so that consecutive ones belong to different nodes.
     *
     * `nodeList` groups its addresses BY NODE — one node appears several times
     * in its own group because it is reachable on several interfaces — and
     * ranks each node's addresses by how fast that interface answers for it.
     * Keeping only the single fastest address across the whole list, as this
     * did, left the caller nothing to fall back to: when that one node was
     * down, opening a deep link failed outright.
     *
     * Take one address per node per round instead — every node's fastest, then
     * every node's second fastest. Rank decides who is in a round, which keeps
     * consecutive candidates on different machines, and the published response
     * time orders the round so the quickest still leads.
     *
     * Only public IPs are considered, on any port, and IPv4 and IPv6 are
     * treated equally — unchanged from the previous behaviour.
     *
     * Tweet-iOS applies the same ordering in `HproseInstance.entryIPCandidates`,
     * and TweetWeb in `src/utils/entryRoutes.ts`.
     */
    fun entryIpCandidates(nodeList: List<*>): List<String> {
        val nodes = nodeList.mapNotNull { node ->
            (node as? List<*>)
                ?.mapNotNull { rankedAddress(it) }
                ?.sortedBy { it.responseTime }
                ?.takeIf { it.isNotEmpty() }
        }

        val ordered = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val deepest = nodes.maxOfOrNull { it.size } ?: 0

        for (rank in 0 until deepest) {
            nodes.mapNotNull { it.getOrNull(rank) }
                .sortedBy { it.responseTime }
                .forEach { candidate ->
                    // One address can be published by two nodes behind a single
                    // NAT. It is one way in either way, and belongs at its first
                    // position.
                    if (seen.add(candidate.ip)) ordered.add(candidate.ip)
                }
        }
        return ordered
    }

    private data class RankedAddress(val ip: String, val responseTime: Double)

    /** One published address/response-time pair, or null when it is unusable. */
    private fun rankedAddress(ipData: Any?): RankedAddress? {
        val pair = ipData as? List<*> ?: return null
        if (pair.size < 2) return null

        val ip = pair[0].toString()
        // Response time arrives in scientific format for the slower interfaces.
        val responseTime = pair[1].toString().toDoubleOrNull() ?: return null

        ip.getIP() ?: return null
        if (ip.substringAfterLast(":", "8080").toIntOrNull() == null) return null
        if (!isValidPublicIpAddress(ip)) return null

        return RankedAddress(ip, responseTime)
    }

    /** IPv4 octets, or null when [ip] is not a dotted-quad literal. */
    private fun ipv4Octets(ip: String): List<Int>? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return octets
    }

    /**
     * True for any address that is not routable on the public internet: RFC 1918 LANs,
     * RFC 6598 / Tailscale CGNAT space, loopback, link-local, multicast and reserved
     * space, plus their IPv6 equivalents. Expects a bare address with no port — use
     * [isPrivateHostAddress] for `host:port` strings.
     */
    fun isPrivateIP(ip: String): Boolean {
        val address = ip.lowercase().trim('[', ']')

        if (isIPv6Address(address)) {
            // IPv4-mapped IPv6 (::ffff:10.0.0.1) is classified by its IPv4 half.
            if (address.startsWith("::ffff:") && address.substringAfter("::ffff:").contains(".")) {
                return isPrivateIP(address.substringAfter("::ffff:"))
            }
            return when {
                address == "::1" || address == "::" -> true            // loopback / unspecified
                // Unique local fc00::/7 — includes Tailscale's fd7a:115c:a1e0::/48
                address.startsWith("fc") || address.startsWith("fd") -> true
                // Link-local fe80::/10
                address.startsWith("fe8") || address.startsWith("fe9") ||
                    address.startsWith("fea") || address.startsWith("feb") -> true
                address.startsWith("ff") -> true                       // multicast ff00::/8
                else -> false
            }
        }

        val octets = ipv4Octets(address) ?: return false
        return when (octets[0]) {
            0 -> true                              // 0.0.0.0/8 "this network"
            10 -> true                             // 10.0.0.0/8 private
            100 -> octets[1] in 64..127            // 100.64.0.0/10 CGNAT — Tailscale
            127 -> true                            // 127.0.0.0/8 loopback
            169 -> octets[1] == 254                // 169.254.0.0/16 link-local
            172 -> octets[1] in 16..31             // 172.16.0.0/12 private
            192 -> octets[1] == 168                // 192.168.0.0/16 private
            in 224..255 -> true                    // multicast + reserved + broadcast
            else -> false
        }
    }

    /**
     * True when [hostPort] ("1.2.3.4:8002", "[fd7a::1]:8002", or a bare address) resolves
     * to a private/non-routable IP literal. Hostnames return false — they are not IP
     * literals, so this cannot judge them; use it to *reject* private addresses, not to
     * require public ones.
     */
    fun isPrivateHostAddress(hostPort: String): Boolean {
        val host = hostComponent(hostPort) ?: return false
        return isPrivateIP(host)
    }

    /**
     * Strips scheme, brackets and port, yielding the bare host of a `host:port` string.
     * Returns null when the input is malformed.
     */
    fun hostComponent(fullIp: String): String? {
        var value = fullIp.trim()
        val schemeIndex = value.indexOf("://")
        if (schemeIndex >= 0) value = value.substring(schemeIndex + 3)
        value = value.substringBefore("/")
        if (value.isEmpty()) return null

        if (value.startsWith("[")) {
            // Bracketed IPv6, with or without a port: [::1] / [::1]:8002
            val endBracket = value.indexOf(']')
            if (endBracket < 0) return null
            return value.substring(1, endBracket)
        }
        // A bare IPv6 literal has several colons; a host:port pair has exactly one.
        if (value.count { it == ':' } == 1) {
            return value.substringBefore(":").takeIf { it.isNotEmpty() }
        }
        return value
    }

    /**
     * True only for a public, internet-routable IP literal. Accepts `host:port` and
     * bracketed IPv6. Hostnames are rejected — this requires a literal address, so use it
     * where an IP is expected (server-advertised node and provider address lists). IPv6 is
     * allowed as long as it is public; it used to be accepted unconditionally, which let
     * Tailscale (fd7a:115c:a1e0::/48) and other ULA addresses through.
     */
    fun isValidPublicIpAddress(fullIp: String): Boolean {
        val ip = hostComponent(fullIp) ?: return false

        if (isIPv6Address(ip)) {
            return !isPrivateIP(ip)
        }
        if (ipv4Octets(ip) == null) return false
        return !isPrivateIP(ip)
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
