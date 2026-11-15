package us.fireshare.tweet.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.TweetApplication.Companion.applicationScope
import us.fireshare.tweet.TweetApplication.Companion.foregroundImageRoutes
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import java.net.URI

/** The routes verified for image downloads since the last entry check. */
internal class ForegroundImageRoutes {
    private companion object {
        val routePermits = Semaphore(4)
    }

    private val requests = mutableMapOf<MimeiId, Deferred<String?>>()

    fun invalidate(ownerId: MimeiId) {
        synchronized(requests) {
            if (requests[ownerId]?.isCompleted == true) requests.remove(ownerId)
        }
    }

    suspend fun imageUrl(
        mid: MimeiId,
        savedUrl: String?,
        ownerId: MimeiId?,
        allowLookup: Boolean
    ): String? {
        if (ownerId.isNullOrBlank() || ownerId == TW_CONST.GUEST_ID) return savedUrl?.takeIf { it.isNotBlank() }

        val request = synchronized(requests) {
            requests[ownerId] ?: if (allowLookup) {
                applicationScope.async {
                    val savedBaseUrl = savedUrl?.let { url ->
                        runCatching { URI(url) }.getOrNull()?.let { uri ->
                            uri.takeIf { it.host != null && it.scheme in listOf("http", "https") }
                                ?.resolve("/")?.toString()?.removeSuffix("/")
                        }
                    }
                    resolveRoute(ownerId, savedBaseUrl) ?: run {
                        delay(2_000L)
                        resolveRoute(ownerId, savedBaseUrl)
                    }
                }.also { requests[ownerId] = it }
            } else null
        } ?: return null

        val baseUrl = request.await()
        if (baseUrl == null) {
            // Let a later visible retry resolve the route again after a failed lookup.
            synchronized(requests) {
                if (requests[ownerId] === request) requests.remove(ownerId)
            }
        }
        return HproseInstance.getMediaUrl(mid, baseUrl)
    }

    private suspend fun resolveRoute(ownerId: MimeiId, savedBaseUrl: String?): String? = routePermits.withPermit {
        if (foregroundImageRoutes.value !== this || !HproseInstance.isOnline.value) return@withPermit null
        try {
            HproseInstance.resolveImageOwnerRoute(ownerId, savedBaseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("ForegroundImageRoutes").w(e, "Could not verify the image route for $ownerId")
            null
        }
    }
}
