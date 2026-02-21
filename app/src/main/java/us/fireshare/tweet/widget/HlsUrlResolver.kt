package us.fireshare.tweet.widget

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Properties

/**
 * Resolves the correct HLS playlist URL (master.m3u8 vs playlist.m3u8) for a given base URL.
 *
 * **Why base URL (not host id) as key?**
 * The key is the full normalised base URL (scheme + host + path with trailing slash), e.g.
 * `https://cdn.example.com/videos/stream123/`. We do *not* use only host (e.g. "cdn.example.com")
 * because the same host can serve different paths with different conventions: one path might
 * use `master.m3u8`, another `playlist.m3u8`. Keying by host would incorrectly reuse one
 * result for all paths on that host. So the key must identify the exact base URL (path included).
 *
 * Strategy:
 *  1. Check in-memory cache  — instant, zero network cost.
 *  2. Check disk cache       — fast, zero network cost.
 *  3. Probe both master.m3u8 and playlist.m3u8 in PARALLEL with HTTP HEAD requests;
 *     master is preferred when both succeed.
 *  4. Persist the winner to disk so all future loads skip probing entirely.
 *
 * Disk storage: a Java Properties file in context.cacheDir ("hls_url_cache.properties").
 *   • Lives in the cache directory — the OS can evict it under storage pressure, which is
 *     correct behaviour for a cache (losing it just means one re-probe per URL).
 *   • SharedPreferences was intentionally avoided: it is an XML file loaded entirely into
 *     memory, lives in the non-evictable data directory, and is semantically wrong for
 *     a content cache that can grow to hundreds of entries.
 *
 * Only the short filename suffix ("master.m3u8" / "playlist.m3u8") is stored as the value,
 * not the full URL, keeping the file compact.
 *
 * In-memory cache is size-bounded (LRU) so it does not grow unbounded with many distinct base URLs.
 */
object HlsUrlResolver {

    private const val CACHE_FILE = "hls_url_cache.properties"
    private const val PROBE_TIMEOUT_MS = 5_000
    private const val MAX_MEM_CACHE_ENTRIES = 200

    // In-memory cache: normalised baseUrl -> suffix ("master.m3u8" or "playlist.m3u8"). LRU, size-bounded.
    private val memCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_MEM_CACHE_ENTRIES
    }
    private val memCacheLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the cached playable URL synchronously, or null if the cache is cold.
     * Safe to call from the Compose composition (main thread, no coroutine needed).
     */
    fun getCached(context: Context, baseUrl: String): String? {
        val base = normalise(baseUrl)

        // 1. Memory cache (populated at startup or after first resolve)
        synchronized(memCacheLock) {
            memCache[base]?.let { return "$base$it" }
        }

        // 2. Disk cache
        val suffix = readDiskCache(context)[base] as? String ?: return null

        synchronized(memCacheLock) {
            memCache[base] = suffix
        }
        return "$base$suffix"
    }

    /**
     * Resolves the HLS URL, using cache when available and parallel probing otherwise.
     * Must be called from a coroutine (suspend).
     * Returns the full playable URL (never throws).
     */
    suspend fun resolve(context: Context, baseUrl: String): String {
        val base = normalise(baseUrl)

        // Fast path — already cached
        getCached(context, base)?.let { return it }

        // Slow path — parallel probe both candidates simultaneously
        val masterUrl   = "${base}master.m3u8"
        val playlistUrl = "${base}playlist.m3u8"

        Timber.d("HlsUrlResolver: parallel probe — $masterUrl  |  $playlistUrl")

        val suffix = withContext(Dispatchers.IO) {
            coroutineScope {
                val masterOk   = async { probeUrl(masterUrl) }
                val playlistOk = async { probeUrl(playlistUrl) }

                val mOk = masterOk.await()
                val pOk = playlistOk.await()

                when {
                    mOk  -> "master.m3u8"
                    pOk  -> "playlist.m3u8"
                    else -> {
                        // Both probes failed (offline, DNS error, etc.) — default to master
                        // so ExoPlayer's own error-handling takes over, as before.
                        Timber.w("HlsUrlResolver: both probes failed for $base, defaulting to master")
                        "master.m3u8"
                    }
                }
            }
        }

        Timber.d("HlsUrlResolver: resolved $base -> $suffix")

        // Persist to memory and disk
        synchronized(memCacheLock) {
            memCache[base] = suffix
        }
        withContext(Dispatchers.IO) { writeDiskCache(context, base, suffix) }

        return "$base$suffix"
    }

    // ── Disk helpers ──────────────────────────────────────────────────────────

    private fun cacheFile(context: Context) = File(context.cacheDir, CACHE_FILE)

    /** Reads the Properties file. Returns an empty Properties object if the file doesn't exist. */
    private fun readDiskCache(context: Context): Properties {
        val props = Properties()
        val file = cacheFile(context)
        if (file.exists()) {
            try {
                file.inputStream().use { props.load(it) }
            } catch (e: Exception) {
                Timber.w("HlsUrlResolver: failed to read disk cache — ${e.message}")
            }
        }
        return props
    }

    /** Appends / updates a single entry and rewrites the file atomically via a temp file. */
    private fun writeDiskCache(context: Context, base: String, suffix: String) {
        try {
            val file = cacheFile(context)
            val props = readDiskCache(context)
            props[base] = suffix

            // Write to a temp file first, then rename for atomicity
            val tmp = File(context.cacheDir, "$CACHE_FILE.tmp")
            tmp.outputStream().use { props.store(it, null) }
            tmp.renameTo(file)
        } catch (e: Exception) {
            Timber.w("HlsUrlResolver: failed to write disk cache — ${e.message}")
        }
    }

    // ── URL helpers ───────────────────────────────────────────────────────────

    /** Ensures the base URL always ends with "/" for consistent cache keys. */
    private fun normalise(url: String): String =
        if (url.endsWith("/")) url else "$url/"

    /** Sends an HTTP HEAD request and returns true on a 2xx response. */
    private fun probeUrl(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout    = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Timber.d("HlsUrlResolver: HEAD $url failed — ${e.message}")
            false
        }
    }
}
