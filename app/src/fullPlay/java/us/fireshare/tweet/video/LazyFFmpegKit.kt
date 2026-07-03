package us.fireshare.tweet.video

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Log
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Keeps FFmpegKit class references behind a single lazy boundary.
 *
 * The containing class is only touched when local video processing actually executes an FFmpeg
 * command, so app startup and non-video upload paths do not initialize FFmpegKit.
 */
internal object LazyFFmpegKit {
    private const val MAX_FAILURE_LOG_LINES = 8
    private const val MAX_FAILURE_LOG_LINE_LENGTH = 180
    private val noisyDecoderMessages = listOf(
        "Error while decoding stream #0:0: Try again",
        "Missing Sequence Header",
        "Invalid data found when processing input"
    )

    data class ExecutionResult(
        val success: Boolean,
        val logs: String
    )

    suspend fun executeAsync(
        command: String,
        operation: String,
        logTag: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        Timber.tag(logTag).d("Starting async FFmpeg execution for $operation")

        try {
            val session = FFmpegKit.executeAsync(
                command,
                { completedSession: FFmpegSession ->
                    val rc = completedSession.returnCode
                    if (ReturnCode.isSuccess(rc)) {
                        Timber.tag(logTag).d("FFmpeg $operation succeeded")
                        if (cont.isActive) {
                            cont.resume(true)
                        }
                    } else {
                        val logs = summarizeFailureLogs(completedSession.allLogsAsString)
                        Timber.tag(logTag).e("FFmpeg $operation failed (rc=$rc): $logs")
                        if (cont.isActive) {
                            cont.resume(false)
                        }
                    }
                },
                { log: Log ->
                    val message = log.message.trim()
                    if (message.isNotEmpty() && !isNoisyDecoderMessage(message)) {
                        Timber.tag(logTag).d("FFmpeg $operation log: $message")
                    }
                },
                { stats: Statistics ->
                    Timber.tag(logTag)
                        .d("FFmpeg $operation stats: time=${stats.time}ms, size=${stats.size}B, bitrate=${stats.bitrate}bps, speed=${stats.speed}x")
                }
            )

            cont.invokeOnCancellation {
                Timber.tag(logTag).w("Cancelling FFmpeg execution for $operation")
                session.cancel()
            }
        } catch (e: Throwable) {
            Timber.tag(logTag).e(e, "Failed to start FFmpeg execution for $operation")
            if (cont.isActive) {
                cont.resume(false)
            }
        }
    }

    fun execute(
        command: String,
        operation: String,
        logTag: String
    ): ExecutionResult {
        return try {
            val session = FFmpegKit.execute(command)
            val returnCode = session.returnCode
            val success = ReturnCode.isSuccess(returnCode)
            ExecutionResult(
                success = success,
                logs = if (success) "" else session.allLogsAsString
            )
        } catch (e: Throwable) {
            Timber.tag(logTag).e(e, "Failed to execute FFmpeg command for $operation")
            ExecutionResult(
                success = false,
                logs = e.message.orEmpty()
            )
        }
    }

    private fun summarizeFailureLogs(logs: String): String {
        val lines = logs
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.isEmpty()) {
            return "no FFmpeg logs available"
        }

        val noisyCount = lines.count(::isNoisyDecoderMessage)
        val usefulLines = lines
            .asReversed()
            .filterNot(::isNoisyDecoderMessage)
            .distinct()
            .take(MAX_FAILURE_LOG_LINES)
            .asReversed()
            .map(::truncateFailureLine)

        val summaryParts = mutableListOf<String>()
        if (noisyCount > 0) {
            summaryParts += "suppressed $noisyCount repetitive decoder log lines"
        }
        if (usefulLines.isNotEmpty()) {
            summaryParts += usefulLines.joinToString(" | ")
        }

        return summaryParts.takeIf { it.isNotEmpty() }?.joinToString("; ")
            ?: "suppressed ${lines.size} repetitive decoder log lines"
    }

    private fun isNoisyDecoderMessage(message: String): Boolean {
        return noisyDecoderMessages.any { message.contains(it) }
    }

    private fun truncateFailureLine(line: String): String {
        return if (line.length <= MAX_FAILURE_LOG_LINE_LENGTH) {
            line
        } else {
            "${line.take(MAX_FAILURE_LOG_LINE_LENGTH)}..."
        }
    }
}
