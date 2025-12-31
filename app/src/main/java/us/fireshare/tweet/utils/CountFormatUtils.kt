package us.fireshare.tweet.utils

import java.text.DecimalFormat

object CountFormatUtils {
    private val decimalFormat = DecimalFormat("#.#")
    
    /**
     * Formats count with abbreviations:
     * - 0-999: show as-is (e.g., "1", "10", "999")
     * - 1000-9999: show as "1k", "1.1k", "1.2k", etc. (whole thousands show without decimal)
     * - 10000-999999: show as "11k", "12k", etc. (integer only)
     * - 1000000-9999999: show as "1M", "1.1M", "1.2M", etc. (whole millions show without decimal)
     * - 10000000+: show as "10M", "11M", etc. (integer only)
     */
    fun formatCount(count: Int): String {
        return when {
            count < 1000 -> count.toString()
            count < 10000 -> {
                // 1000-9999: show as 1k, 1.1k, 1.2k, etc. (1 decimal place when needed)
                val thousands = count / 1000.0
                if (thousands % 1.0 == 0.0) {
                    "${thousands.toInt()}k"
                } else {
                    "${decimalFormat.format(thousands)}k"
                }
            }
            count < 1000000 -> {
                // 10000-999999: show as 11k, 12k, etc. (integer only)
                "${count / 1000}k"
            }
            count < 10000000 -> {
                // 1000000-9999999: show as 1M, 1.1M, 1.2M, etc. (1 decimal place when needed)
                val millions = count / 1000000.0
                if (millions % 1.0 == 0.0) {
                    "${millions.toInt()}M"
                } else {
                    "${decimalFormat.format(millions)}M"
                }
            }
            else -> {
                // 10000000+: show as 10M, 11M, etc. (integer only)
                "${count / 1000000}M"
            }
        }
    }
}





