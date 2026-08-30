package us.fireshare.tweet.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.widget.VideoManager

/**
 * Warms the main feed's tweet cache while the user is on it.
 *
 * The visible list only fetches the page the user has scrolled to. This walks ahead of
 * them - one page at a time, and only while the network is otherwise quiet - so the
 * pages they have not reached yet are already cached when they get there.
 *
 * Cache only. It goes through [HproseInstance.cacheMainFeedPage], which decodes with
 * [us.fireshare.tweet.datamodel.Tweet.decode] and writes the cache without registering
 * anything in Tweet's shared instance registry, so a read-ahead page leaves nothing
 * pinned in memory and publishes nothing into the visible feed.
 *
 * Read-ahead runs until the backend returns a short page. Cache size is bounded by the
 * cache's own 30-day expiry, not by anything here.
 */
object BackgroundTweetPrefetcher {

    private const val TAG = "TweetPrefetch"
    private const val PAGE_SIZE = 20
    /** How often to re-check the network while something else is downloading. */
    private const val BUSY_POLL_INTERVAL_MS = 3_000L
    /**
     * Breathing room between two prefetched pages, so a burst of RPCs can never line up
     * with the user starting to scroll.
     */
    private const val PAGE_INTERVAL_MS = 1_000L
    private const val MAX_CONSECUTIVE_FAILURES = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Whose feed the cursor belongs to. Also the run token: a coroutine whose owner no
     * longer matches has been superseded by [reset] or a user change, and its progress
     * must not be written back.
     */
    private var owner: String? = null
    /**
     * How far the read-ahead has reached. Session-scoped: it resumes across visits to
     * the feed and starts over only on [reset].
     */
    private var nextPage = 0
    /** The backend has run out of pages. */
    private var isExhausted = false
    private var job: Job? = null

    /**
     * Warm the app user's following feed. Called when the main feed is shown.
     *
     * The app user is deliberately NOT inspected here. This runs from the screen's
     * LaunchedEffect, right after TweetFeedViewModel.initialize(), which is not a
     * suspend function - it only launches the work that resolves the app user. Reading
     * appUser at this point usually sees the guest placeholder, and bailing on that
     * would kill the read-ahead for the whole session with nothing to re-arm it.
     * Readiness is a gate condition instead, re-checked on every poll.
     */
    @Synchronized
    fun prefetchMainFeed() {
        if (job?.isActive == true) return
        if (isExhausted && owner == appUser.mid) return
        job = scope.launch { run() }
    }

    /**
     * Stop and forget the cursor. Called when the session it belongs to ends (logout or
     * a user change), so the next account does not inherit it.
     */
    @Synchronized
    fun reset() {
        job?.cancel()
        job = null
        owner = null
        nextPage = 0
        isExhausted = false
    }

    /**
     * Publish a run's progress. Returns false once this run has been superseded, which
     * is how a coroutine still in flight when [reset] ran is stopped from writing a
     * cursor the next session would inherit.
     */
    @Synchronized
    private fun commitProgress(runOwner: String, page: Int, exhausted: Boolean): Boolean {
        if (owner != runOwner) return false
        nextPage = page
        isExhausted = exhausted
        return true
    }

    /**
     * Claim the next page to fetch, binding the cursor to whoever the app user is now.
     * Returns null once the backend has run out. Synchronized with [reset] so a run
     * that outlives its session cannot resurrect a stale cursor.
     */
    @Synchronized
    private fun claimNextPage(): Pair<String, Int>? {
        val mid = appUser.mid
        if (owner != mid) {
            owner = mid
            nextPage = 0
            isExhausted = false
        }
        if (isExhausted) return null
        return mid to nextPage
    }

    private suspend fun run() {
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            if (!waitForQuietNetwork()) return
            val (runOwner, page) = claimNextPage() ?: return

            try {
                val rowCount = HproseInstance.cacheMainFeedPage(page, PAGE_SIZE)
                consecutiveFailures = 0
                // A short page is the backend's end-of-feed signal - the same rule the
                // visible feed's pagination uses.
                val exhausted = rowCount < PAGE_SIZE
                if (!commitProgress(runOwner, page + 1, exhausted)) return
                if (exhausted) {
                    Timber.tag(TAG).d("main feed page $page -> $rowCount row(s), feed exhausted")
                    return
                }
                Timber.tag(TAG).d("main feed page $page -> $rowCount row(s) cached")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                Timber.tag(TAG).w(
                    "main feed page $page failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): $e"
                )
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return
            }

            delay(PAGE_INTERVAL_MS)
        }
    }

    /** Suspends until nothing else is using the network. */
    private suspend fun waitForQuietNetwork(): Boolean {
        while (currentCoroutineContext().isActive) {
            if (isTrafficLow()) return true
            delay(BUSY_POLL_INTERVAL_MS)
        }
        return false
    }

    private suspend fun isTrafficLow(): Boolean {
        if (!HproseInstance.isOnline.value) return false
        // Readiness, re-checked every poll rather than once at start-up.
        //
        // A guest's main feed is served from the alpha account, not get_tweet_feed, so
        // there is nothing for this to prefetch until a real user is signed in; waiting
        // (rather than bailing) is what lets a sign-in later in the session start the
        // read-ahead without anything having to re-arm it. The route matters too:
        // getTweetFeedService needs appUser.baseUrl, and calling ahead of it would fail
        // fast and burn the failure budget before the feed is even usable.
        if (appUser.isGuest()) return false
        if (appUser.baseUrl == null) return false

        // Both reads below are main-thread only: ProcessLifecycleOwner's lifecycle, and
        // ExoPlayer state inside hasSpareBandwidth.
        return withContext(Dispatchers.Main) {
            // Backgrounded: the app should not open connections the user did not ask for.
            val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
            // Video is the app's real bandwidth consumer.
            foreground && VideoManager.hasSpareBandwidth()
        }
    }
}
