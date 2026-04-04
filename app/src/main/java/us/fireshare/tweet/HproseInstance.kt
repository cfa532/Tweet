package us.fireshare.tweet

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import hprose.io.HproseClassManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.fetchTweet
import us.fireshare.tweet.HproseInstance.refreshTweet
import us.fireshare.tweet.datamodel.BlackList
import us.fireshare.tweet.datamodel.CachedTweetDao
import us.fireshare.tweet.datamodel.ChatDatabase
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatMessageDeserializer
import us.fireshare.tweet.datamodel.FeedResetReason
import us.fireshare.tweet.datamodel.HproseService
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheDatabase
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.User.Companion.getInstance
import us.fireshare.tweet.datamodel.UserContentType
import us.fireshare.tweet.network.HproseClientPool
import us.fireshare.tweet.service.MediaUploadService
import us.fireshare.tweet.service.UploadTweetWorker
import us.fireshare.tweet.utils.ErrorMessageUtils
import us.fireshare.tweet.widget.Gadget.filterIpAddresses
import us.fireshare.tweet.widget.VideoManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import us.fireshare.tweet.datamodel.User.Companion.getInstance as getUserInstance

/**
 * NodePool: Persistent authoritative source for node IP management
 * 
 * Key Principles:
 * - Tracks access nodes (hostIds[1]) for READ operations only
 * - Each node maintains a list of valid IPs (IPv4 and IPv6)
 * - Nodes persist indefinitely (no pruning)
 * - Prefers IPv4 over IPv6 for better compatibility
 * - Thread-safe operations with mutex protection
 * 
 * Usage Flow:
 * 1. Validate user's IP against pool before access
 * 2. Get IP from user's access node if not in pool
 * 3. On failure: re-resolve and update/replace node's IP list
 */
object NodePool {
    data class NodeInfo(
        val mid: MimeiId,                    // Node MID (hostIds[1])
        var ips: MutableList<String>,        // Array of valid IPs (IPv4 and IPv6)
        var lastUpdate: Long = System.currentTimeMillis()  // When IPs were last updated
    ) {
        fun hasIP(ip: String): Boolean {
            // Extract base IP from URL formats
            val normalizedIP = normalizeIP(ip)
            return ips.any { normalizeIP(it) == normalizedIP }
        }
        
        fun getPreferredIP(): String? {
            // Prefer IPv4 over IPv6 for better compatibility
            // IPv4 addresses don't contain multiple colons
            val ipv4 = ips.firstOrNull { it.count { c -> c == ':' } <= 1 }
            return ipv4 ?: ips.firstOrNull()
        }
        
        private fun normalizeIP(ip: String): String {
            // Remove http:// but keep brackets for IPv6 addresses with ports
            val trimmed = ip.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore("/")
            
            // Keep brackets intact for IPv6 with port: [ipv6]:port
            // IPv6 addresses have multiple colons, need brackets when port is present
            return trimmed
        }
    }
    
    private val nodes = mutableMapOf<MimeiId, NodeInfo>()
    private val nodeMutex = Mutex()
    
    /**
     * Check if user's current IP is in the pool
     */
    suspend fun isUserIPValid(user: User): Boolean = nodeMutex.withLock {
        val accessNodeMid = user.hostIds?.getOrNull(1) ?: return@withLock false
        val nodeInfo = nodes[accessNodeMid] ?: return@withLock false
        val userIP = user.baseUrl ?: return@withLock false
        
        nodeInfo.hasIP(userIP)
    }
    
    /**
     * Get IP from user's access node in pool
     * Uses hostIds[1] (access node for READ operations)
     */
    suspend fun getIPFromNode(user: User): String? = nodeMutex.withLock {
        val accessNodeMid = user.hostIds?.getOrNull(1) ?: return@withLock null
        val nodeInfo = nodes[accessNodeMid] ?: return@withLock null
        
        nodeInfo.getPreferredIP()
    }
    
    /**
     * Get IP directly from nodeId (works for both READ and WRITE nodes)
     */
    suspend fun getIPFromNodeId(nodeId: MimeiId): String? = nodeMutex.withLock {
        val nodeInfo = nodes[nodeId] ?: return@withLock null
        nodeInfo.getPreferredIP()
    }
    
    /**
     * Update node's IP list (replaces existing IPs)
     * Called after re-resolution when fetch fails
     */
    suspend fun updateNodeIP(nodeMid: MimeiId, newIP: String) = nodeMutex.withLock {
        val nodeInfo = nodes[nodeMid]
        if (nodeInfo != null) {
            // Replace IP list with new IP
            nodeInfo.ips = mutableListOf(newIP)
            nodeInfo.lastUpdate = System.currentTimeMillis()
            Timber.tag("NodePool").d("Updated node $nodeMid - replaced IPs with: $newIP")
        } else {
            // Create new node entry
            nodes[nodeMid] = NodeInfo(
                mid = nodeMid,
                ips = mutableListOf(newIP)
            )
            Timber.tag("NodePool").d("Added new node $nodeMid with IP: $newIP")
        }
    }
    
    /**
     * Remove node from pool (called when cached IP becomes unhealthy)
     */
    suspend fun removeNode(nodeId: MimeiId) = nodeMutex.withLock {
        val removed = nodes.remove(nodeId)
        if (removed != null) {
            Timber.tag("NodePool").d("Removed node $nodeId from pool (was: ${removed.ips})")
        }
    }
    
    /**
     * Add IP to node's list (doesn't replace existing IPs)
     * Called when discovering new IPs for a node
     */
    suspend fun addIPToNode(nodeMid: MimeiId, ip: String) = nodeMutex.withLock {
        val nodeInfo = nodes[nodeMid]
        if (nodeInfo != null) {
            // Add IP if not already present
            if (!nodeInfo.hasIP(ip)) {
                nodeInfo.ips.add(ip)
                nodeInfo.lastUpdate = System.currentTimeMillis()
                Timber.tag("NodePool").d("Added IP to node $nodeMid: $ip (total: ${nodeInfo.ips.size})")
            }
        } else {
            // Create new node entry
            nodes[nodeMid] = NodeInfo(
                mid = nodeMid,
                ips = mutableListOf(ip)
            )
            Timber.tag("NodePool").d("Added new node $nodeMid with IP: $ip")
        }
    }
    
    /**
     * Update pool from user object (adds discovered IPs)
     * Called after successful fetch
     */
    suspend fun updateFromUser(user: User) {
        val accessNodeMid = user.hostIds?.getOrNull(1) ?: return
        val userIP = user.baseUrl ?: return
        
        // Extract just the IP:port from baseUrl
        // Normalize IP but keep brackets for IPv6 addresses with ports
        val normalizedIP = userIP.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
        
        addIPToNode(accessNodeMid, normalizedIP)
    }
}

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {
    private var _appId: MimeiId = BuildConfig.APP_ID
    val appId: MimeiId get() = _appId
    
    /**
     * Global flag to enable IPv4-only mode for all backend calls.
     * When true, only IPv4 addresses will be used.
     * When false, both IPv4 and IPv6 addresses can be used.
     */
    var v4Only: Boolean = false
    
    /**
     * Helper function to unwrap v2 API response format
     * v2 responses are wrapped as: {success: true, data: result} or {success: false, message: "..."}
     * @return The unwrapped data if success is true, null otherwise
     */
    private fun <T> unwrapV2Response(response: Any?): T? {
        if (response == null) return null
        
        return when (response) {
            is Map<*, *> -> {
                val responseMap = response as? Map<String, Any>
                val success = responseMap?.get("success") as? Boolean
                if (success == true) {
                    // Extract data field
                    @Suppress("UNCHECKED_CAST")
                    (responseMap["data"] as? T) ?: response as? T
                } else {
                    // Error response
                    val message = responseMap?.get("message") as? String
                    Timber.tag("unwrapV2Response").w("API returned error: $message")
                    null
                }
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                response as? T
            }
        }
    }
    // Use Application context to avoid memory leaks - Application lives for the entire app lifecycle
    private lateinit var applicationContext: Application
    lateinit var preferenceHelper: PreferenceHelper
    
    // Private backing field for appUser StateFlow
    private val _appUserState = MutableStateFlow(getInstance(TW_CONST.GUEST_ID))
    
    /**
     * StateFlow for observing appUser changes
     */
    val appUserState: StateFlow<User> = _appUserState.asStateFlow()
    
    // Separate StateFlow for avatar to force UI updates
    private val _appUserAvatar = MutableStateFlow<String?>(null)
    val appUserAvatar: StateFlow<String?> = _appUserAvatar.asStateFlow()
    
    // Track if appUser has been fully initialized from server (not just entry IP)
    private val _isAppUserInitialized = MutableStateFlow(false)
    val isAppUserInitialized: StateFlow<Boolean> = _isAppUserInitialized.asStateFlow()

    // Network connectivity state
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    
    // Lazy initialization of MediaUploadService (uses dedicated upload client)
    private val mediaUploadService: MediaUploadService by lazy {
        MediaUploadService(applicationContext, uploadHttpClient, appUser, appId)
    }
    
    /**
     * Global app user with automatic expiration checking.
     * When accessed, automatically checks if the user has expired (30 minutes)
     * and refreshes from server if needed, similar to other user objects.
     * Includes deduplication to prevent concurrent refresh requests.
     * 
     * For observing changes, use appUserState.collect {} instead.
     */
    var appUser: User
        get() {
            return _appUserState.value
        }
        set(value) {
            val oldBaseUrl = _appUserState.value.baseUrl
            val oldAvatar = _appUserState.value.avatar

            _isInsideAppUserSetter = true
            try {
                // Ensure the singleton instance is updated with all fields from value,
                // so that User.getInstance(mid) and appUser stay in sync.
                if (value !== User.getInstance(value.mid)) {
                    User.getInstance(value.mid).from(value)
                }
            } finally {
                _isInsideAppUserSetter = false
            }
            val instance = User.getInstance(value.mid)

            _appUserState.value = instance
            _appUserAvatar.value = instance.avatar  // Always emit avatar separately

            // Log if avatar changed (helps debug toolbar avatar issues)
            if (oldAvatar != instance.avatar) {
                Timber.tag("appUser").d("Avatar updated: $oldAvatar -> ${instance.avatar}")
            }

            // If baseUrl changed from null to a valid value, trigger tweet feed refresh
            if (oldBaseUrl == null && instance.baseUrl != null && !instance.isGuest()) {
                Timber.tag("appUser").d("BaseUrl became available for logged-in user, triggering tweet feed refresh")
                TweetApplication.applicationScope.launch {
                    try {
                        // Post notification to trigger feed refresh
                        TweetNotificationCenter.post(
                            TweetEvent.FeedResetRequested(FeedResetReason.BASEURL_AVAILABLE)
                        )
                        Timber.tag("appUser").d("Posted FeedResetRequested notification after baseUrl became available: ${instance.baseUrl}")
                    } catch (e: Exception) {
                        Timber.tag("appUser").e(e, "Error triggering tweet refresh after baseUrl became available")
                    }
                }
            }
        }

    // Reentrance guard: when the appUser setter calls from(User)/updateUserInstance,
    // those methods should NOT fire notifyAppUserChanged because the setter already
    // handles its own StateFlow updates and side effects.
    private var _isInsideAppUserSetter = false

    /**
     * Notify that the appUser singleton's fields have been mutated directly
     * (e.g., via User.updateUserInstance, User.from(Map), User.from(User)).
     * Updates StateFlows to trigger UI recomposition and fires side effects
     * (feed refresh when baseUrl becomes available).
     *
     * @param oldBaseUrl the baseUrl before mutation
     * @param oldAvatar  the avatar before mutation
     */
    internal fun notifyAppUserChanged(oldBaseUrl: String?, oldAvatar: MimeiId?) {
        if (_isInsideAppUserSetter) return  // Setter handles notifications itself
        val current = _appUserState.value

        // Always re-emit avatar so observers of appUserAvatar pick up changes
        _appUserAvatar.value = current.avatar

        if (oldAvatar != current.avatar) {
            Timber.tag("appUser").d("Avatar synced: $oldAvatar -> ${current.avatar}")
        }

        // Trigger feed refresh when baseUrl transitions from null → non-null
        if (oldBaseUrl == null && current.baseUrl != null && !current.isGuest()) {
            Timber.tag("appUser").d("BaseUrl became available via user instance update, triggering feed refresh")
            TweetApplication.applicationScope.launch {
                try {
                    TweetNotificationCenter.post(
                        TweetEvent.FeedResetRequested(FeedResetReason.BASEURL_AVAILABLE)
                    )
                } catch (e: Exception) {
                    Timber.tag("appUser").e(e, "Error triggering feed refresh after baseUrl sync")
                }
            }
        }
    }

    private lateinit var chatDatabase: ChatDatabase
    lateinit var dao: CachedTweetDao
    
    /**
     * Track users that have been resynced this app session to avoid redundant operations
     * Matches iOS ProfileView.resyncedUsersThisSession behavior
     */
    private val resyncedUsersThisSession = mutableSetOf<MimeiId>()
    private val resyncLock = Any()
    
    // Data class for tracking incomplete uploads
    data class IncompleteUpload(
        val workId: String,
        val tweetContent: String,
        val attachmentUris: List<String>,
        val isPrivate: Boolean,
        val timestamp: Long,
        val videoConversionJobId: String? = null,  // For HLS video conversion jobs
        val videoConversionBaseUrl: String? = null,  // Base URL for polling status
        val videoConversionUri: String? = null  // Original video URI for aspect ratio calculation
    )

    suspend fun init(context: Context, onInitialized: (suspend () -> Unit)? = null) {
        // Store Application context to avoid memory leaks
        this.applicationContext = context.applicationContext as Application
        HproseClassManager.register(Tweet::class.java, "Tweet")
        HproseClassManager.register(User::class.java, "User")

        // Register network connectivity callback
        registerNetworkCallback()

        this.preferenceHelper = PreferenceHelper(context)
        chatDatabase = ChatDatabase.getInstance(context)
        val tweetCache = TweetCacheDatabase.getInstance(context)
        dao = tweetCache.tweetDao()

        // Initialize appUser with userId from preferences, or GUEST_ID if not available
        val storedUserId = preferenceHelper.getUserId()
        val initialUserId = if (storedUserId != TW_CONST.GUEST_ID) storedUserId else TW_CONST.GUEST_ID
        
        // STEP 1: Extract cached appUser FIRST, before any network operations
        if (initialUserId != TW_CONST.GUEST_ID) {
            val cachedUser = TweetCacheManager.getCachedUser(initialUserId)
            if (cachedUser != null) {
                // Print detailed cached user data
                Timber.tag("HproseInstance").d("=== CACHED USER DATA ===")
                Timber.tag("HproseInstance").d("  mid: ${cachedUser.mid}")
                Timber.tag("HproseInstance").d("  username: ${cachedUser.username}")
                Timber.tag("HproseInstance").d("  name: ${cachedUser.name}")
                Timber.tag("HproseInstance").d("  avatar: ${cachedUser.avatar}")
                Timber.tag("HproseInstance").d("  baseUrl: ${cachedUser.baseUrl}")
                Timber.tag("HproseInstance").d("  email: ${cachedUser.email}")
                Timber.tag("HproseInstance").d("  profile: ${cachedUser.profile}")
                Timber.tag("HproseInstance").d("  tweetCount: ${cachedUser.tweetCount}")
                Timber.tag("HproseInstance").d("  followingCount: ${cachedUser.followingCount}")
                Timber.tag("HproseInstance").d("  followersCount: ${cachedUser.followersCount}")
                Timber.tag("HproseInstance").d("  timestamp: ${cachedUser.timestamp}")
                Timber.tag("HproseInstance").d("========================")
                
                // Use cached data to populate appUser immediately
                User.updateUserInstance(cachedUser, true)
                appUser = User.getInstance(initialUserId)
                Timber.tag("HproseInstance").d("✅ Loaded cached appUser at startup: userId=${appUser.mid}, username=${appUser.username}, baseUrl=${appUser.baseUrl}")
            } else {
                // No cached data, create skeleton user
                appUser = getInstance(initialUserId)
                Timber.tag("HproseInstance").d("No cached user found, created skeleton appUser with mid: ${appUser.mid}")
            }
        } else {
            // Guest user
            appUser = getInstance(TW_CONST.GUEST_ID)
            appUser.followingList = getAlphaIds()
            Timber.tag("HproseInstance").d("Guest user initialized")
        }
        
        // STEP 2: Try to update appUser with fresh data from server during init
        try {
            // CRITICAL: initAppEntry() now calls onInitialized as soon as baseUrl is set
            // User data fetch continues in the background and updates appUser
            initAppEntry(onInitialized)
        } catch (e: Exception) {
            Timber.tag("HproseInstance").e(e, "Error during network initialization, continuing with cached data")
            // Already have cached data loaded in STEP 1, just continue
            // Don't re-throw - allow app to continue in offline mode
            Timber.tag("HproseInstance").w("App initialized in offline mode with cached data")
            // Call callback even in offline mode so UI can be shown
            onInitialized?.invoke()
        }
    }

    /**
     * App_Url is the network entrance of the App. Use it to initiate appId, and BASE_URL.
     * */
    /**
     * Find the best IP by trying URLs and parsing HTML parameters
     */
    private suspend fun findEntryIP(): String {
        val urls = preferenceHelper.getAppUrls()
        Timber.tag("findEntryIP").d("Attempting to find entry IP with ${urls.size} URL(s): $urls")

        for (url in urls) {
            try {
                Timber.tag("findEntryIP").d("Trying URL: $url")
                /**
                 * retrieve window.Param from page source code of http://base_url
                 * window.setParam({
                 *         CurNode:0,
                 *         log: true,
                 *         ver:"last",
                 *         addrs: [[["183.159.17.7:8081", 3.080655111],["[240e:391:e00:169:1458:aa58:c381:5c85]:8081",
                 *                  3.9642842857833],["192.168.0.94:8081", 281478208946270]]],
                 *         aid: "",
                 *         remote:"::1",
                 *         mid:"d4lRyhABgqOnqY4bURSm_T-4FZ4"
                 * })]
                 * */
                val response: HttpResponse = initHttpClient.get(url)
                val pattern = Pattern.compile("window\\.setParam\\((\\{.*?\\})\\)", Pattern.DOTALL)
                val matcher = pattern.matcher(response.bodyAsText().trimIndent() as CharSequence)
                if (matcher.find()) {
                    matcher.group(1)?.let {
                        val paramMap = Gson().fromJson(it, Map::class.java) as Map<*, *>
                        // For debug builds, always use BuildConfig.APP_ID to ensure correct APP_ID
                        // For release builds, use the server's mid value
                        val serverMid = paramMap["mid"]?.toString()
                        
                        _appId = if (BuildConfig.DEBUG) {
                            // Debug builds: Always use BuildConfig.APP_ID
                            BuildConfig.APP_ID
                        } else {
                            // Release builds: Use server's mid value
                            serverMid ?: BuildConfig.APP_ID
                        }
                        Timber.tag("findEntryIP").d("Build type: ${if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"}, Using APP_ID: $_appId")

                        /**
                         * The code above makes a call to base URL of the app, get a html page
                         * and tries to extract appId and host IP addresses from source code.
                         * For debug builds, APP_ID is always from BuildConfig to ensure debug/release separation.
                         *
                         * addrs is an ArrayList of ArrayList of node's IP address pairs.
                         * Each pair is an ArrayList of two elements. The first is the IP address,
                         * and the second is the time spent to get response from the IP.
                         *
                         * bestIp is the IP with the smallest response time from valid public IPs.
                         * */
                        Timber.tag("findEntryIP").d("Successfully parsed paramMap: $paramMap")
                        val entryIP = filterIpAddresses(paramMap["addrs"] as List<*>)
                        if (entryIP != null) {
                            return entryIP
                        } else {
                            Timber.tag("findEntryIP").w("filterIpAddresses returned null for URL: $url")
                            // Continue to next URL
                        }
                    }
                } else {
                    Timber.tag("findEntryIP").w("No data found within window.setParam() for URL: $url")
                }
            } catch (e: Exception) {
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                if (isNetworkError) {
                    Timber.tag("findEntryIP").w(e, "Network error connecting to URL: $url (will try next URL if available)")
                } else {
                    Timber.tag("findEntryIP").e(e, "Failed to find entry IP from URL: $url")
                }
            }
        }

        // If we reach here, all URLs failed
        val errorMsg = "Failed to find entry IP. Tried ${urls.size} URL(s): ${urls.joinToString(", ")}"
        Timber.tag("findEntryIP").e(errorMsg)
        throw IllegalStateException(errorMsg)
    }

    private suspend fun initAppEntry(onBaseUrlReady: (suspend () -> Unit)? = null) {
        if (!isOnline.value) {
            Timber.tag("initAppEntry").d("Offline: skipping")
            return
        }
        val userId = preferenceHelper.getUserId()
        Timber.tag("initAppEntry").d("Retrieved userId from preferences: $userId")

        if (userId != TW_CONST.GUEST_ID) {
            /**
             * If there is a valid userId in preference, this is a login user.
             * Initiate current account.
             *
             * OPTIMIZATION: Try cached baseUrl first before resolving new IP.
             * Only call findEntryIP() if cached baseUrl fails.
             * */
            Timber.tag("initAppEntry")
                .d("Initializing app for userId: $userId")

            // appUser was already loaded from cache in initialization step
            val hasCachedData = !appUser.username.isNullOrBlank()
            val hasCachedBaseUrl = !appUser.baseUrl.isNullOrBlank()

            if (hasCachedBaseUrl) {
                // Health-check cached baseUrl before showing UI.
                // If stale (e.g. dynamic IP changed), resolve fresh IP first to prevent
                // all subsequent operations (getFans, getTweetFeed, images) from
                // hammering a dead IP for 30+ seconds.
                Timber.tag("initAppEntry").d("Health-checking cached baseUrl: ${appUser.baseUrl}")
                val cachedHealthy = withContext(Dispatchers.IO) {
                    isServerHealthy(appUser.baseUrl!!)
                }
                if (cachedHealthy) {
                    Timber.tag("initAppEntry").d("✅ Cached baseUrl is healthy")
                } else {
                    Timber.tag("initAppEntry").w("Cached baseUrl unhealthy, resolving fresh IP...")
                    invalidateIPCache(appUser.baseUrl)
                    try {
                        val providerIP = withContext(Dispatchers.IO) { getProviderIP(userId) }
                        if (providerIP != null) {
                            appUser.baseUrl = if (providerIP.startsWith("http://")) providerIP else "http://$providerIP"
                        } else {
                            val entryIP = findEntryIP()
                            appUser.baseUrl = "http://$entryIP"
                        }
                    } catch (e: Exception) {
                        val entryIP = findEntryIP()
                        appUser.baseUrl = "http://$entryIP"
                    }
                    appUser.clearHproseService()
                    User.updateUserInstance(appUser, true)
                    Timber.tag("initAppEntry").d("✅ Updated baseUrl to: ${appUser.baseUrl}")
                }
            } else {
                // No cached baseUrl, resolve new IP immediately
                val entryIP = findEntryIP()
                appUser.baseUrl = "http://$entryIP"
                Timber.tag("initAppEntry").d("No cached baseUrl, resolved new IP: ${appUser.baseUrl}")
                User.updateUserInstance(appUser, true)
            }

            // Show UI now that we have a verified baseUrl
            if (hasCachedData) {
                _isAppUserInitialized.value = true
                Timber.tag("initAppEntry").d("🚀 BaseUrl ready with cached data, showing UI now (initialized: true)")
                onBaseUrlReady?.invoke()
            }

            // Fetch fresh user data from network
            Timber.tag("initAppEntry").d("Fetching user data from network...")

            var refreshedUser: User? = null
            val maxFetchAttempts = 3

            try {
                for (attempt in 1..maxFetchAttempts) {
                    Timber.tag("initAppEntry").d("User fetch attempt $attempt/$maxFetchAttempts for userId: $userId")

                    refreshedUser = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(20_000) {
                            val baseUrlParam = if (attempt == 1) {
                                appUser.baseUrl ?: ""
                            } else {
                                ""  // Force IP re-resolution on retry
                            }
                            fetchUser(userId, baseUrl = baseUrlParam, forceRefresh = true)
                        }
                    }

                    if (refreshedUser != null && !refreshedUser.baseUrl.isNullOrBlank()) {
                        Timber.tag("initAppEntry").d("✅ User fetch successful on attempt $attempt")
                        break
                    }

                    // If failed and not last attempt, wait before retrying
                    if (attempt < maxFetchAttempts) {
                        val delayMs = 2000L * attempt  // 2s, 4s, 6s
                        Timber.tag("initAppEntry").w("User fetch failed on attempt $attempt, retrying after ${delayMs}ms...")
                        delay(delayMs)
                    } else {
                        Timber.tag("initAppEntry").e("❌ User fetch failed after $maxFetchAttempts attempts")
                    }
                }
                
                if (refreshedUser != null && !refreshedUser.baseUrl.isNullOrBlank()) {
                    // Update on Main dispatcher to ensure UI recomposition happens immediately
                    withContext(Dispatchers.Main) {
                        // Update singleton first, THEN set appUser to the singleton instance
                        User.updateUserInstance(refreshedUser, true)
                        appUser = User.getInstance(refreshedUser.mid)
                        
                        // Mark appUser as fully initialized (not just entry IP)
                        _isAppUserInitialized.value = true
                        
                        Timber.tag("initAppEntry")
                            .d("✅ User fetch successful - baseUrl: ${appUser.baseUrl}, avatar: ${appUser.avatar}, initialized: true")
                    }
                    
                    // Show UI with fresh data
                    if (!hasCachedData) {
                        Timber.tag("initAppEntry").d("🚀 Fresh user data loaded, showing UI now")
                        onBaseUrlReady?.invoke()
                    }
                } else {
                    // All retry attempts failed - ensure we have a usable baseUrl
                    if (appUser.baseUrl.isNullOrBlank()) {
                        val entryIP = findEntryIP()
                        appUser.baseUrl = "http://$entryIP"
                        Timber.tag("initAppEntry")
                            .w("All user fetch attempts failed, resolved fallback baseUrl: ${appUser.baseUrl}")
                    } else {
                        Timber.tag("initAppEntry")
                            .w("All user fetch attempts failed, continuing with existing baseUrl: ${appUser.baseUrl}")
                    }

                    // Show UI in degraded mode (using resolved IP)
                    if (!hasCachedData) {
                        Timber.tag("initAppEntry").w("⚠️ User fetch failed after all retries, showing UI with resolved IP")
                        onBaseUrlReady?.invoke()
                    }
                }
            } catch (e: Exception) {
                Timber.tag("initAppEntry").e(e, "Error during user fetch retry loop: ${e.message}")
                // Resolve new IP if needed
                if (appUser.baseUrl.isNullOrBlank()) {
                    val entryIP = findEntryIP()
                    appUser.baseUrl = "http://$entryIP"
                    Timber.tag("initAppEntry").w("Exception recovery: resolved new IP ${appUser.baseUrl}")
                }
                // Still show UI even if fetch failed
                if (!hasCachedData) {
                    Timber.tag("initAppEntry").e("❌ Critical error fetching user, showing UI with entry IP only")
                    onBaseUrlReady?.invoke()
                }
            }
            
            Timber.tag("initAppEntry")
                .d("User initialized with cached data. $appId, appUser.baseUrl: ${appUser.baseUrl}")
        } else {
            val alphaIds = getAlphaIds()
            appUser.followingList = alphaIds
            // Ensure baseUrl is set so hproseService is non-null for login (getUserId) calls
            if (appUser.baseUrl.isNullOrBlank()) {
                val entryIP = findEntryIP()
                appUser.baseUrl = "http://$entryIP"
                Timber.tag("initAppEntry").d("🔍 Guest user: resolved entry IP for baseUrl: ${appUser.baseUrl}")
            }
            TweetCacheManager.saveUser(appUser)
            Timber.tag("initAppEntry").d("🔍 Guest user initialized. appId: $appId")
            Timber.tag("initAppEntry").d("🔍 Guest user alphaIds: $alphaIds")
            Timber.tag("initAppEntry").d("🔍 Guest user baseUrl: ${appUser.baseUrl}")
            Timber.tag("initAppEntry").d("🔍 Guest user mid: ${appUser.mid}")
            // For guest users, also call the callback to show UI
            onBaseUrlReady?.invoke()
        }
        // once a workable URL is found, return successfully
        Timber.tag("initAppEntry").d("✅ Successfully initialized app entry (UI ready)")
    }

    /**
     * List of system users to be followed by default
     * */
    fun getAlphaIds(): List<MimeiId> {
        return BuildConfig.ALPHA_ID.split(",").map { it.trim() }
    }

    /**
     * Send a chat message to a recipient.
     * This function performs two steps:
     * 1. Send message_outgoing to sender's own node (with retry and baseUrl refresh)
     * 2. Send message_incoming to recipient's node (with retry and baseUrl refresh)
     */
    suspend fun sendMessage(receiptId: MimeiId, msg: ChatMessage): Pair<Boolean, String?> {
        if (!isOnline.value) {
            Timber.tag("sendMessage").d("Offline: skipping")
            throw Exception("No network connection")
        }
        // Step 1: Send to sender's own node (message_outgoing) with retry
        Timber.tag("sendMessage").d("📤 Step 1: Sending message_outgoing to sender's node")
        val senderSendResult = sendToSenderNodeWithRetry(receiptId, msg, maxRetries = 2)
        
        if (!senderSendResult.first) {
            return senderSendResult
        }
        
        Timber.tag("sendMessage").d("✅ Step 1 completed: Successfully sent to sender's node")
        
        // Step 2: Send to recipient's node (message_incoming) with retry
        Timber.tag("sendMessage").d("📤 Step 2: Sending message_incoming to recipient's node")
        val recipientSendResult = sendToRecipientNodeWithRetry(receiptId, msg, maxRetries = 2)
        
        if (!recipientSendResult.first) {
            return recipientSendResult
        }
        
        Timber.tag("sendMessage").d("✅ Step 2 completed: Successfully sent to recipient's node")
        
        // Both steps succeeded
        return Pair(true, null)
    }
    
    /**
     * Helper function to send message_outgoing to sender's own node with retry and baseUrl refresh
     */
    private suspend fun sendToSenderNodeWithRetry(
        receiptId: MimeiId,
        msg: ChatMessage,
        maxRetries: Int = 2
    ): Pair<Boolean, String?> {
        var lastError: String? = null
        
        for (attempt in 0..maxRetries) {
            val forceRefresh = attempt > 0
            if (forceRefresh) {
                Timber.tag("sendMessage").d("🔄 Retry attempt $attempt: Refreshing sender's baseUrl")
            }
            
            // Refresh appUser's baseUrl if needed
            if (forceRefresh) {
                val refreshedUser = fetchUser(appUser.mid, baseUrl = "", forceRefresh = true)
                if (refreshedUser != null && refreshedUser.baseUrl != appUser.baseUrl) {
                    appUser.baseUrl = refreshedUser.baseUrl
                    Timber.tag("sendMessage").d("✅ Updated sender's baseUrl to: ${refreshedUser.baseUrl}")
                }
            }
            
            val entry = "message_outgoing"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "version" to "v2",
                "entry" to entry,
                "userid" to appUser.mid,
                "receiptid" to receiptId,
                "msg" to Json.encodeToString(msg),
                "hostid" to (appUser.hostIds?.first() ?: "")
            )
            
            if (appUser.hproseService == null) {
                val errorMsg = "Failed to create client for sender node"
                Timber.tag("sendMessage").e("❌ $errorMsg - baseUrl: ${appUser.baseUrl}")
                if (attempt < maxRetries) {
                    delay((attempt + 1) * 1000L)
                    continue
                }
                return Pair(false, errorMsg)
            }
            
            Timber.tag("sendMessage").d("📤 Sending to sender node (attempt ${attempt + 1}/${maxRetries + 1}) - baseUrl: ${appUser.baseUrl}")
            
            try {
                val response = appUser.hproseService?.runMApp<Any>(entry, params)
                
                // Handle different response types
                val isSuccess = when (response) {
                    is Boolean -> response
                    is Map<*, *> -> {
                        val responseMap = response as? Map<String, Any>
                        val success = responseMap?.get("success") as? Boolean
                        if (success == false) {
                            lastError = responseMap["error"] as? String ?: "Unknown error"
                        }
                        success ?: false
                    }
                    else -> false
                }
                
                if (isSuccess) {
                    Timber.tag("sendMessage").d("✅ Successfully sent to sender node (attempt ${attempt + 1})")
                    return Pair(true, null)
                } else {
                    lastError = lastError ?: applicationContext.getString(R.string.error_send_outgoing_message)
                    Timber.tag("sendMessage").e("❌ Failed to send to sender node (attempt ${attempt + 1}/${maxRetries + 1}): ${lastError?.takeIf { it != applicationContext.getString(R.string.error_send_outgoing_message) } ?: "Failed to send outgoing message"}")
                    
                    if (attempt < maxRetries) {
                        val delay = (attempt + 1) * 2000L // 2, 4 seconds
                        Timber.tag("sendMessage").d("⏳ Waiting ${delay / 1000} seconds before retry...")
                        delay(delay)
                        continue
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: applicationContext.getString(R.string.error_network)
                Timber.tag("sendMessage").e("❌ Error sending to sender node (attempt ${attempt + 1}/${maxRetries + 1}): ${e.message ?: "Network error"}")
                
                if (attempt < maxRetries) {
                    delay((attempt + 1) * 2000L)
                    continue
                }
            }
        }
        
        // All retries exhausted
        val finalError = lastError ?: "Failed to send message to sender node after ${maxRetries + 1} attempts"
        Timber.tag("sendMessage").e("❌ All retry attempts exhausted for sender node: $finalError")
        return Pair(false, finalError)
    }
    
    /**
     * Helper function to send message_incoming to recipient's node with retry and baseUrl refresh
     */
    private suspend fun sendToRecipientNodeWithRetry(
        receiptId: MimeiId,
        msg: ChatMessage,
        maxRetries: Int = 2
    ): Pair<Boolean, String?> {
        var receiptUser: User?
        var lastError: String? = null
        
        for (attempt in 0..maxRetries) {
            val forceRefresh = attempt > 0
            if (forceRefresh) {
                Timber.tag("sendMessage").d("🔄 Retry attempt $attempt: Refreshing recipient's baseUrl for userId: $receiptId")
            }
            
            // Fetch recipient user (with forced refresh on retry)
            receiptUser = fetchUser(receiptId, baseUrl = if (forceRefresh) "" else null)
            
            if (receiptUser == null) {
                val errorMsg = "Recipient user not found"
                Timber.tag("sendMessage").e("❌ $errorMsg for userId: $receiptId")
                return Pair(false, errorMsg)
            }
            
            val receiptEntry = "message_incoming"
            val receiptParams = mapOf(
                "aid" to appId,
                "ver" to "last",
                "version" to "v2",
                "entry" to receiptEntry,
                "senderid" to appUser.mid,
                "receiptid" to receiptId,
                "msg" to Json.encodeToString(msg)
            )
            
            if (receiptUser.hproseService == null) {
                val errorMsg = "Failed to create client for recipient node"
                Timber.tag("sendMessage").e("❌ $errorMsg - baseUrl: ${receiptUser.baseUrl}")
                if (attempt < maxRetries) {
                    delay((attempt + 1) * 1000L)
                    continue
                }
                return Pair(false, errorMsg)
            }
            
            Timber.tag("sendMessage").d("📤 Sending to recipient node (attempt ${attempt + 1}/${maxRetries + 1}) - baseUrl: ${receiptUser.baseUrl}")
            
            try {
                val receiptResponse = receiptUser.hproseService?.runMApp<Any>(receiptEntry, receiptParams)
                
                // Handle different response types
                val success = when (receiptResponse) {
                    is Boolean -> receiptResponse
                    is Map<*, *> -> {
                        val responseMap = receiptResponse as? Map<String, Any>
                        val successValue = responseMap?.get("success") as? Boolean
                        if (successValue == false) {
                            lastError = responseMap["error"] as? String ?: "Failed to send to recipient node"
                        }
                        successValue ?: true // Default to true for backward compatibility
                    }
                    else -> true // Default to true for backward compatibility
                }
                
                if (success) {
                    Timber.tag("sendMessage").d("✅ Successfully sent to recipient node (attempt ${attempt + 1})")
                    return Pair(true, null)
                } else {
                    lastError = lastError ?: "Failed to send to recipient node"
                    Timber.tag("sendMessage").e("❌ Failed to send to recipient node (attempt ${attempt + 1}/${maxRetries + 1}): $lastError")
                    
                    if (attempt < maxRetries) {
                        val delay = (attempt + 1) * 2000L // 2, 4 seconds
                        Timber.tag("sendMessage").d("⏳ Waiting ${delay / 1000} seconds before retry...")
                        delay(delay)
                        continue
                    }
                }
            } catch (e: Exception) {
                lastError = ErrorMessageUtils.getNetworkErrorMessage(applicationContext, e)
                Timber.tag("sendMessage").e("❌ Error sending to recipient node (attempt ${attempt + 1}/${maxRetries + 1}): $lastError")
                
                if (attempt < maxRetries) {
                    delay((attempt + 1) * 2000L)
                    continue
                }
            }
        }
        
        // All retries exhausted
        val finalError = lastError ?: "Failed to send message to recipient node after ${maxRetries + 1} attempts"
        Timber.tag("sendMessage").e("❌ All retry attempts exhausted for recipient node: $finalError")
        return Pair(false, finalError)
    }

    // get the recent unread message from a sender.
    // Matches iOS implementation which unwraps v2 response and filters out outgoing messages.
    suspend fun fetchMessages(senderId: MimeiId): List<ChatMessage>? {
        if (!isOnline.value) {
            Timber.tag("fetchMessages").d("Offline: skipping")
            return null
        }
        val entry = "message_fetch"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "entry" to entry,
            "userid" to appUser.mid,
            "senderid" to senderId
        )

        return try {
            // Get raw response and unwrap v2 format (matching iOS implementation)
            val rawResponse = appUser.hproseService?.runMApp<Any>(entry, params)
            Timber.tag("fetchMessages").d("Raw response type: ${rawResponse?.javaClass?.simpleName}, value: $rawResponse")
            
            // Handle v2 response format: {success: true, data: [...]} or {success: false, error: ...} or direct array
            val messageArray = when (rawResponse) {
                is Map<*, *> -> {
                    val responseMap = rawResponse as? Map<String, Any>
                    val success = responseMap?.get("success") as? Boolean
                    if (success == true) {
                        // Extract data field which should be a List
                        when (val data = responseMap["data"]) {
                            is List<*> -> data.filterIsInstance<Map<String, Any>>()
                            else -> {
                                Timber.tag("fetchMessages").w("Unexpected data type: ${data?.javaClass?.simpleName}")
                                emptyList()
                            }
                        }
                    } else {
                        // Error response
                        val errorMessage = responseMap?.get("error") as? String 
                            ?: responseMap?.get("message") as? String 
                            ?: "Unknown error"
                        Timber.tag("fetchMessages").e("Server returned error: $errorMessage")
                        return null
                    }
                }
                is List<*> -> {
                    // Legacy format: direct array
                    rawResponse.filterIsInstance<Map<String, Any>>()
                }
                else -> {
                    Timber.tag("fetchMessages").w("Unexpected response type: ${rawResponse?.javaClass?.simpleName}")
                    emptyList()
                }
            }
            
            Timber.tag("fetchMessages").d("Received ${messageArray.size} messages from server (before filtering)")
            
            val gson = GsonBuilder()
                .registerTypeAdapter(ChatMessage::class.java, ChatMessageDeserializer())
                .create()

            val allMessages = messageArray.mapNotNull { messageData ->
                try {
                    gson.fromJson(gson.toJson(messageData), ChatMessage::class.java)
                } catch (e: Exception) {
                    Timber.tag("fetchMessages").e(e, "Error decoding message")
                    null
                }
            }

            // Filter to only return incoming messages (sent by others to current user)
            // Filter out messages sent by the current user (matching iOS implementation)
            val incomingMessages = allMessages.filter { message ->
                val isIncoming = message.authorId != appUser.mid
                if (!isIncoming) {
                    Timber.tag("fetchMessages").d("Filtered out outgoing message from ${message.authorId}")
                } else {
                    Timber.tag("fetchMessages").d("Incoming message from ${message.authorId} to ${message.receiptId}")
                }
                isIncoming
            }

            Timber.tag("fetchMessages").d("Returning ${incomingMessages.size} incoming messages (after filtering)")
            return incomingMessages
        } catch (e: Exception) {
            Timber.tag("fetchMessages").e(e, "Error in fetchMessages")
            null
        }
    }

    /**
     * Get a list of unread incoming messages. Only check, do not fetch them.
     * Matches iOS implementation which unwraps v2 response and filters out outgoing messages.
     * */
    suspend fun checkNewMessages(): List<ChatMessage>? {
        if (appUser.isGuest()) return null
        if (!isOnline.value) {
            Timber.tag("checkNewMessages").d("Offline: skipping message check")
            return null
        }
        val entry = "message_check"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "entry" to entry,
            "userid" to appUser.mid
        )
        return try {
            // Get raw response and unwrap v2 format (matching iOS implementation)
            val rawResponse = appUser.hproseService?.runMApp<Any>(entry, params)
            Timber.tag("checkNewMessages").d("Raw response type: ${rawResponse?.javaClass?.simpleName}, value: $rawResponse")
            
            // Handle v2 response format: {success: true, data: [...]} or direct array
            val response = when (rawResponse) {
                is Map<*, *> -> {
                    val responseMap = rawResponse as? Map<String, Any>
                    val success = responseMap?.get("success") as? Boolean
                    if (success == true) {
                        // Extract data field which should be a List
                        when (val data = responseMap["data"]) {
                            is List<*> -> data.filterIsInstance<Map<String, Any>>()
                            else -> {
                                Timber.tag("checkNewMessages").w("Unexpected data type: ${data?.javaClass?.simpleName}")
                                emptyList()
                            }
                        }
                    } else {
                        // Error response
                        val message = responseMap?.get("message") as? String
                        Timber.tag("checkNewMessages").w("API returned error: $message")
                        emptyList()
                    }
                }
                is List<*> -> {
                    // Legacy format: direct array
                    rawResponse.filterIsInstance<Map<String, Any>>()
                }
                else -> {
                    Timber.tag("checkNewMessages").w("Unexpected response type: ${rawResponse?.javaClass?.simpleName}")
                    emptyList()
                }
            }
            
            Timber.tag("checkNewMessages").d("Extracted ${response.size} messages from response")
            Timber.tag("checkNewMessages").d("Received ${response.size} messages from server (before filtering)")
            
            val gson = GsonBuilder()
                .registerTypeAdapter(ChatMessage::class.java, ChatMessageDeserializer())
                .create()

            val allMessages = response.mapNotNull { messageData ->
                try {
                    gson.fromJson(gson.toJson(messageData), ChatMessage::class.java)
                } catch (e: Exception) {
                    Timber.tag("checkNewMessages").e(e, "Error decoding message")
                    null
                }
            }

            // Filter to only return incoming messages (sent by others to current user)
            // Filter out messages sent by the current user (matching iOS implementation)
            val incomingMessages = allMessages.filter { message ->
                val isIncoming = message.authorId != appUser.mid
                if (!isIncoming) {
                    Timber.tag("checkNewMessages").d("Filtered out outgoing message from ${message.authorId}")
                } else {
                    Timber.tag("checkNewMessages").d("Incoming message from ${message.authorId} to ${message.receiptId}")
                }
                isIncoming
            }

            Timber.tag("checkNewMessages").d("Returning ${incomingMessages.size} incoming messages (after filtering)")

            return incomingMessages
        } catch (e: Exception) {
            Timber.tag("checkNewMessages").e(e, "Error in checkNewMessages")
            null
        }
    }

    suspend fun checkUpgrade(): Map<String, String>? {
        if (!isOnline.value) {
            Timber.tag("checkUpgrade").d("Offline: skipping")
            return null
        }
        val entry = "check_upgrade"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "entry" to entry
        )
        return try {
            val rawResponse =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)
            response?.mapValues { it.value.toString() }
        } catch (e: Exception) {
            Timber.tag("checkUpgrade").e(e)
            null
        }
    }

    suspend fun getUserId(username: String): MimeiId? {
        if (!isOnline.value) {
            Timber.tag("getUserId").d("Offline: skipping")
            return null
        }
        val entry = "get_userid"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "username" to username,
            "v4only" to v4Only.toString()
        )
        Timber.tag("getUserId").d("🔍 v4Only global = $v4Only, sending v4only = ${v4Only.toString()}")
        return try {
            val rawResponse = appUser.hproseService?.runMApp<Any>(entry, params)
            unwrapV2Response<String>(rawResponse)
        } catch (e: Exception) {
            Timber.tag("GetUserId").e(e)
            null
        }
    }

    /**
     * @return a pair. The first is User object, the second is error message.
     * There are two steps for a guest user to login. First, find UserID given username.
     * Second, find the node which has this user's data, and logon to that node.
     * Finally update the baseUrl of the current user with the new ip of the user's node.
     * Includes retry logic with exponential backoff for network-related failures.
     * */
    suspend fun login(
        username: String,
        password: String,
        context: Context,
        maxRetries: Int = 3
    ): Pair<User?, String?> {
        if (!isOnline.value) {
            Timber.tag("login").d("Offline: skipping")
            return Pair(null, "No network connection")
        }
        var lastError: String? = null
        
        repeat(maxRetries) { attempt ->
            try {
                // Get user ID - retry if this fails
                val userId = getUserId(username)
                if (userId == null) {
                    lastError = context.getString(R.string.login_getuserid_fail)
                    Timber.tag("Login").w("getUserId failed for username: $username (attempt ${attempt + 1}/$maxRetries) - Failed to get user ID")
                    if (attempt == maxRetries - 1) {
                        // Last attempt failed
                        return Pair(null, lastError)
                    }
                    // Wait before retry
                    if (attempt < maxRetries - 1) {
                        val delayMs = minOf(5000L, 1000L * (1 shl attempt))
                        Timber.tag("Login").d("Retrying login after getUserId failure in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                        delay(delayMs)
                    }
                    return@repeat // Continue to next attempt
                }
                
                // Fetch user - retry if this fails  
                val user = fetchUser(userId, "", maxRetries = 1)
                if (user == null) {
                    lastError = context.getString(R.string.login_getuser_fail)
                    Timber.tag("Login").w("fetchUser failed for userId: $userId (attempt ${attempt + 1}/$maxRetries) - Failed to fetch user")
                    if (attempt == maxRetries - 1) {
                        // Last attempt failed
                        return Pair(null, lastError)
                    }
                    // Wait before retry
                    if (attempt < maxRetries - 1) {
                        val delayMs = minOf(5000L, 1000L * (1 shl attempt))
                        Timber.tag("Login").d("Retrying login after fetchUser failure in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                        delay(delayMs)
                    }
                    return@repeat // Continue to next attempt
                }
                
                val entry = "login"
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "version" to "v2",
                    "username" to username,
                    "password" to password
                )
                val rawResponse =
                    user.hproseService?.runMApp<Map<String, Any>>(entry, params)
                val response = unwrapV2Response<Map<String, Any>>(rawResponse)

                // Handle v2 response format: {success: true, data: {user: ..., status: "success"}} or {success: false, message: "..."}
                if (response != null) {
                    val status = response["status"] as? String
                    if (status == "success") {
                        // Update user from response if available
                        val userData = response["user"] as? Map<String, Any>
                        if (userData != null) {
                            user.from(userData)
                        }
                        Timber.tag("Login").d("Login successful for user: ${user.username}")
                        return Pair(user, null)
                    } else {
                        val errorMsg = response["reason"] as? String ?: response["message"] as? String ?: applicationContext.getString(R.string.error_unknown_occurred)
                        lastError = errorMsg
                        // Don't retry for authentication failures (wrong password, etc.)
                        if (errorMsg.contains("password", ignoreCase = true) || 
                            errorMsg.contains("username", ignoreCase = true) ||
                            errorMsg.contains("invalid", ignoreCase = true)) {
                            return Pair(null, errorMsg)
                        }
                        // For other failures, log and continue to retry
                        Timber.tag("Login").w("Login failed with error: $errorMsg (attempt ${attempt + 1}/$maxRetries)")
                    }
                } else {
                    // Response was null or unwrapV2Response returned null (error case)
                    lastError = context.getString(R.string.login_error)
                    Timber.tag("Login").w("Login response was null (attempt ${attempt + 1}/$maxRetries)")
                }
                
                // If this is the last attempt, return failure
                if (attempt == maxRetries - 1) {
                    return Pair(null, lastError)
                }
                
                // Wait before retrying
                val delayMs = minOf(5000L, 1000L * (1 shl attempt)) // Exponential backoff: 1s, 2s, 4s
                Timber.tag("Login").d("Retrying login in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                delay(delayMs)
            } catch (e: Exception) {
                lastError = ErrorMessageUtils.getNetworkErrorMessage(context, e)
                Timber.tag("Login").e(e, "Login attempt ${attempt + 1}/$maxRetries failed with exception")
                
                // Check if it's a network-related error that should be retried
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                
                if (!isNetworkError) {
                    // Don't retry for non-network errors
                    Timber.tag("Login").w("Non-network error detected, not retrying")
                    return Pair(null, lastError)
                }
                
                // If this is the last attempt, return failure
                if (attempt == maxRetries - 1) {
                    return Pair(null, lastError)
                }
                
                // Wait before retrying
                val delayMs = minOf(5000L, 1000L * (1 shl attempt)) // Exponential backoff: 1s, 2s, 4s
                Timber.tag("Login").d("Network error detected, retrying login in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                delay(delayMs)
            }
        }
        
        // All retries failed
        return Pair(null, lastError ?: context.getString(R.string.login_error))
    }

    /**
     * @param nodeId
     * Find IP addresses of given node and return the best one.
     * Uses the same algorithm as get_provider_ips to test and select the best IP.
     * 
     * Fallback Strategy (mirrors getProviderIP):
     * 1. Check NodePool for cached healthy IP
     * 2. Try lookup using appUser's client
     * 3. If no healthy IPs found AND appUser unhealthy -> try via entry IP
     * 4. Update NodePool with successful result
     * */
    suspend fun getHostIP(nodeId: MimeiId, v4Only: String = HproseInstance.v4Only.toString()): String? {
        if (!isOnline.value) {
            Timber.tag("getHostIP").d("Offline: skipping")
            return null
        }
        // Step 1: Check NodePool for known IPs and verify health
        val poolIP = NodePool.getIPFromNodeId(nodeId)
        if (poolIP != null) {
            // Verify the cached IP is still healthy before returning
            val fullUrl = "http://$poolIP"
            
            if (isServerHealthy(fullUrl)) {
                // Still healthy - use it!
                Timber.tag("getHostIP").d("✓ Found healthy node $nodeId in NodePool: $poolIP")
                return poolIP
            } else {
                // No longer healthy - remove from cache and continue to fresh lookup
                Timber.tag("getHostIP").w("NodePool IP $poolIP for node $nodeId is no longer healthy, removing from cache")
                NodePool.removeNode(nodeId)
                // Fall through to Step 2
            }
        }
        
        // Step 2: Try lookup using appUser's client
        try {
            val hostIP = _getHostIP(nodeId, v4Only, appUser.hproseService)
            if (hostIP != null) {
                // Successfully resolved healthy IP
                NodePool.updateNodeIP(nodeId, hostIP)
                Timber.tag("getHostIP").d("✓ Resolved IP for node $nodeId via appUser: $hostIP, updated NodePool")
                return hostIP
            }
            // null means: server responded but no healthy IPs found
            // Continue to fallback logic
        } catch (e: Exception) {
            // Network error occurred
            Timber.tag("getHostIP").w(e, "Network error in _getHostIP for node $nodeId via appUser")
            // Continue to fallback logic instead of giving up
        }
        
        // Step 3: No healthy IPs found - check if it's because appUser is unhealthy
        val appUserBaseUrl = appUser.baseUrl
        if (appUserBaseUrl.isNullOrBlank()) {
            Timber.tag("getHostIP").w("Cannot retry for node $nodeId - appUser.baseUrl is null")
            return null
        }
        
        if (!isServerHealthy(appUserBaseUrl)) {
            // AppUser is UNHEALTHY - this could be temporary network issue
            // Try resolving via entry IP as fallback
            Timber.tag("getHostIP").d("appUser server unhealthy at $appUserBaseUrl, trying via entry IP for node $nodeId")
            
            return try {
                val entryIP = findEntryIP()
                val entryClient = HproseClientPool.getRegularClient("http://$entryIP")
                
                val hostIP = _getHostIP(nodeId, v4Only, entryClient)
                if (hostIP != null) {
                    NodePool.updateNodeIP(nodeId, hostIP)
                    Timber.tag("getHostIP").d("✓ Resolved IP for node $nodeId via entry IP: $hostIP, updated NodePool")
                    hostIP
                } else {
                    Timber.tag("getHostIP").w("No IPs found for node $nodeId even via entry IP")
                    null
                }
            } catch (e: Exception) {
                Timber.tag("getHostIP").e(e, "Entry IP lookup failed for node $nodeId")
                null
            }
        } else {
            // AppUser is HEALTHY but node has no healthy IPs - node is genuinely down
            Timber.tag("getHostIP").d("appUser healthy but node $nodeId has no healthy IPs - node genuinely down")
            return null
        }
    }
    
    /**
     * Internal method to get host IP via specified Hprose client
     * @return healthy IP or null if no healthy IPs found
     */
    private suspend fun _getHostIP(
        nodeId: MimeiId, 
        v4Only: String = HproseInstance.v4Only.toString(),
        hproseService: HproseService? = appUser.hproseService
    ): String? {
        val entry = "get_node_ips"
        val params = mapOf("aid" to appId, "ver" to "last", "version" to "v2", "nodeid" to nodeId, "v4only" to v4Only)
        
        val rawResponse = hproseService?.runMApp<Any>(entry, params)
        val ipArray = unwrapV2Response<List<String>>(rawResponse)
        
        // If ipArray is valid, try each IP and return the best one
        if (ipArray != null && ipArray.isNotEmpty()) {
            val ipCount = ipArray.size
            val ipList = ipArray.joinToString(", ")
            Timber.tag("getHostIP").d("Received $ipCount IP(s) for node $nodeId: [$ipList]")
            
            val bestIP = tryIpAddresses(ipArray, "getHostIP($nodeId)")
            
            if (bestIP != null) {
                Timber.tag("getHostIP").d("Found healthy IP for node $nodeId: $bestIP")
            } else {
                Timber.tag("getHostIP").w("No healthy IPs found for node $nodeId among $ipCount address(es)")
            }
            
            return bestIP
        }
        
        Timber.tag("getHostIP").w("No IPs returned for node $nodeId")
        return null
    }

    /**
     * Register a new user account.
     * @param username Username for the new account
     * @param password Password for the new account
     * @param alias Display name/alias for the user
     * @param profile Profile description/bio
     * @param hostId Optional host ID for the account
     * @param cloudDrivePort Optional cloud drive port number
     * @return Boolean indicating if registration was successful
     */
    suspend fun registerUser(
        username: String,
        password: String,
        alias: String?,
        profile: String,
        hostId: String? = null,
        cloudDrivePort: Int = 0,
        domainToShare: String? = null
    ): Pair<Boolean, String?> {
        if (!isOnline.value) {
            Timber.tag("registerUser").d("Offline: skipping")
            return Pair(false, "No network connection")
        }
        // Validate hostId if provided
        hostId?.let { id ->
            val trimmedId = id.trim()
            if (trimmedId.isNotEmpty() && trimmedId.length < 27) {
                Timber.tag("registerUser").e("Invalid hostId: must be at least 27 characters")
                return Pair(false, "Invalid host ID: must be at least 27 characters")
            }
        }

        val newUser = User(
            mid = appUser.mid,
            name = alias,
            username = username,
            password = password,
            profile = profile,
            cloudDrivePort = cloudDrivePort,
            domainToShare = domainToShare
        ).apply {
            // Set hostIds only if hostId is a valid Mimei ID (at least 27 characters after trimming)
            hostId?.trim()?.takeIf { it.isNotEmpty() && it.length >= 27 }?.let { this.hostIds = listOf(it) }
        }

        val entry = "register"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "user" to Json.encodeToString(newUser)
        )

        try {
            val response = appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)

            if (response == null) {
                Timber.tag("registerUser").e("Registration failed: No response from server")
                return Pair(false, "No response from server")
            }

            // Check if this is a v2 response with success field
            val success = when (val successValue = response["success"]) {
                is Boolean -> successValue
                is Int -> successValue != 0
                else -> null
            }

            if (success == true) {
                Timber.tag("registerUser").d("Registration successful")

                // Extract the newly created user's ID from the response
                val userDict = response["user"] as? Map<String, Any>
                val registeredUserId = userDict?.get("mid") as? String

                if (registeredUserId != null) {
                    // Launch toggleFollowing operations in a separate coroutine to avoid blocking
                    // This allows the user to receive the success response immediately
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            Timber.tag("registerUser").d("Starting async follow operations for new user $registeredUserId")
                            // Make the newly registered user follow each user in getAlphaIds()
                            val alphaIds = getAlphaIds()
                            for (alphaId in alphaIds) {
                                try {
                                    val followResult = toggleFollowing(alphaId, registeredUserId)
                                    Timber.tag("registerUser").d("New user $registeredUserId followed alpha user $alphaId, result: $followResult")
                                } catch (e: Exception) {
                                    Timber.tag("registerUser").e(e, "Failed to follow alphaId $alphaId for new user $registeredUserId")
                                    // Continue with other users even if one fails
                                }
                            }
                            Timber.tag("registerUser").d("Completed async follow operations for new user $registeredUserId")
                        } catch (e: Exception) {
                            Timber.tag("registerUser").e(e, "Error in async follow operations")
                        }
                    }
                } else {
                    Timber.tag("registerUser").w("Warning: User object not found in registration response")
                }

                // Return success immediately without waiting for follow operations
                return Pair(true, null)
            } else if (success == false) {
                // Error response - extract error message (like iOS does)
                val message = response["message"] as? String
                    ?: response["reason"] as? String
                    ?: "Registration failed"
                Timber.tag("registerUser").e("Registration failed: $message")
                return Pair(false, message)
            } else {
                // Not a v2 response, try unwrapV2Response for backward compatibility
                val unwrappedResponse = unwrapV2Response<Map<String, Any>>(response)
                if (unwrappedResponse != null) {
                    Timber.tag("registerUser").d("Registration successful (legacy format)")
                    return Pair(true, null)
                } else {
                    Timber.tag("registerUser").e("Registration failed: Invalid response format")
                    return Pair(false, "Invalid response format")
                }
            }
        } catch (e: Exception) {
            Timber.tag("registerUser").e(e, "Registration error")
            return Pair(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Helper function to update appUser in-memory with new values
     */
    private suspend fun updateAppUserInMemory(
        alias: String?,
        profile: String?,
        hostId: String?,
        cloudDrivePort: Int
    ) {
        withContext(Dispatchers.Main) {
            alias?.let { appUser.name = it }
            profile?.let { appUser.profile = it }
            hostId?.let { appUser.hostIds = listOf(it) }
            appUser.cloudDrivePort = cloudDrivePort
        }
    }

    /**
     * Update user profile/core data.
     * @param password Optional new password
     * @param alias Optional new display name/alias
     * @param profile Optional new profile description/bio
     * @param hostId Optional new host ID
     * @param cloudDrivePort New cloud drive port number (0 to clear)
     * @param domainToShare Optional domain to share
     * @return Boolean indicating if update was successful
     */
    suspend fun updateUserCore(
        password: String? = null,
        alias: String? = null,
        profile: String? = null,
        hostId: String? = null,
        cloudDrivePort: Int = 0,
        domainToShare: String? = null
    ): Pair<Boolean, String?> {
        if (!isOnline.value) {
            Timber.tag("updateUserCore").d("Offline: skipping")
            return Pair(false, "No network connection")
        }
        // Validate hostId if provided
        hostId?.let { id ->
            val trimmedId = id.trim()
            if (trimmedId.isNotEmpty() && trimmedId.length < 27) {
                Timber.tag("updateUserCore").e("Invalid hostId: must be at least 27 characters")
                return Pair(false, "Invalid host ID: must be at least 27 characters")
            }
        }

        val updatedUser = User(
            mid = appUser.mid,
            name = alias,
            password = password,
            profile = profile,
            cloudDrivePort = cloudDrivePort,
            domainToShare = domainToShare
        ).apply {
            // Set hostIds only if hostId is a valid Mimei ID (at least 27 characters after trimming)
            hostId?.trim()?.takeIf { it.isNotEmpty() && it.length >= 27 }?.let { this.hostIds = listOf(it) }
        }

        val entry = "set_author_core_data"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "user" to Json.encodeToString(updatedUser)
        )

        try {
            val response = appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)

            if (response == null) {
                Timber.tag("updateUserCore").e("Profile update failed: No response from server")
                return Pair(false, "No response from server")
            }

            Timber.tag("updateUserCore").d("Server response: $response")

            // Check for success in response
            val isSuccess = when {
                response["success"] == true -> true
                response["status"] == "success" -> true // Legacy format fallback
                else -> false
            }

            if (isSuccess) {
                Timber.tag("updateUserCore").d("Profile update successful")

                // Update in-memory appUser with new values
                updateAppUserInMemory(alias, profile, hostId, cloudDrivePort)

                // Save updated appUser to cache so cached tweets maintain their author reference
                TweetCacheManager.saveUser(appUser)
                Timber.tag("updateUserCore").d("Saved updated user to cache: ${appUser.mid}")

                return Pair(true, null)
            } else {
                val errorMessage = response["message"] as? String
                    ?: response["reason"] as? String
                    ?: "Profile update failed"
                Timber.tag("updateUserCore").e("Profile update failed: $errorMessage")
                return Pair(false, errorMessage)
            }
        } catch (e: Exception) {
            Timber.tag("updateUserCore").e(e, "Profile update error")
            return Pair(false, e.message ?: "Unknown error")
        }
    }

    suspend fun setUserAvatar(user: User, avatar: MimeiId): MimeiId? {
        if (!isOnline.value) {
            Timber.tag("setUserAvatar").d("Offline: skipping")
            return null
        }
        val entry = "set_user_avatar"
        val json = """
            {"aid": $appId, "ver": "last", "userid": ${user.mid}, "avatar": $avatar}
        """.trimIndent()
        val gson = Gson()
        val request = gson.fromJson(json, Map::class.java)
        return try {
            val response = appUser.uploadService?.runMApp<MimeiId>(entry, request)
            response
        } catch (e: Exception) {
            Timber.tag("setUserAvatar").e(e)
            null
        }
    }

    /**
     * Given user object get a list of Field-Value, where Field is user Id,
     * Value is timestamp when the following is added.
     * Includes retry logic with exponential backoff for network-related failures.
     * */
    suspend fun getFollowings(user: User, maxRetries: Int = 5): List<MimeiId> {
        if (!isOnline.value) {
            Timber.tag("getFollowings").d("Offline: skipping")
            return emptyList()
        }
        if (user.isGuest()) return getAlphaIds()
        val entry = "get_followings_sorted"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "userid" to user.mid
        )
        
        // Retry logic
        for (attempt in 0..maxRetries) {
            try {
                val forceRefresh = attempt > 0
                if (forceRefresh) {
                    Timber.tag("getFollowings").d("🔄 Retry attempt $attempt: Refreshing user's baseUrl for userId: ${user.mid}")
                    val refreshedUser = fetchUser(user.mid, baseUrl = "")
                    if (refreshedUser != null) {
                        user.baseUrl = refreshedUser.baseUrl
                        Timber.tag("getFollowings").d("✅ Refreshed baseUrl: ${user.baseUrl}")
                    }
                }
                
                val rawResponse = user.hproseService?.runMApp<Any>(entry, params)
                val response = unwrapV2Response<List<Map<String, Any>>>(rawResponse)
                val result = response?.sortedByDescending { (it["value"] as? Int) ?: 0 }
                    ?.mapNotNull { it["field"] as? String } ?: getAlphaIds()
                
                if (attempt > 0) {
                    Timber.tag("getFollowings").d("✅ Successfully fetched followings after retry (attempt ${attempt + 1}/${maxRetries + 1})")
                }
                return result
                
            } catch (e: Exception) {
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                
                if (isNetworkError && attempt < maxRetries) {
                    val delayMs = minOf(5000L, 1000L * (1 shl attempt))
                    Timber.tag("getFollowings").d("⏳ Network error detected, waiting ${delayMs / 1000}s before retry (attempt ${attempt + 1}/${maxRetries + 1})")
                    delay(delayMs)
                    continue
                }
                
                Timber.tag("Hprose.getFollowings").e(e)
                if (attempt >= maxRetries) {
                    Timber.tag("getFollowings").e("❌ All retry attempts exhausted after ${maxRetries + 1} attempts")
                }
            }
        }
        
        return getAlphaIds()
    }

    /**
     * Given user object get a list of Field-Value, where Field is user Id,
     * Value is timestamp when the follower is added.
     * Includes retry logic with exponential backoff for network-related failures.
     * */
    suspend fun getFans(user: User, maxRetries: Int = 5): List<MimeiId>? {
        if (!isOnline.value) {
            Timber.tag("getFans").d("Offline: skipping")
            return null
        }
        if (user.isGuest()) return null
        val entry = "get_followers_sorted"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "userid" to user.mid
        )
        
        // Retry logic
        for (attempt in 0..maxRetries) {
            try {
                val forceRefresh = attempt > 0
                if (forceRefresh) {
                    Timber.tag("getFans").d("🔄 Retry attempt $attempt: Refreshing user's baseUrl for userId: ${user.mid}")
                    val refreshedUser = fetchUser(user.mid, baseUrl = "")
                    if (refreshedUser != null) {
                        user.baseUrl = refreshedUser.baseUrl
                        Timber.tag("getFans").d("✅ Refreshed baseUrl: ${user.baseUrl}")
                    }
                }
                
                val rawResponse = user.hproseService?.runMApp<Any>(entry, params)
                val response = unwrapV2Response<List<Map<String, Any>>>(rawResponse)
                val result = response?.sortedByDescending { (it["value"] as? Int) ?: 0 }
                    ?.mapNotNull { it["field"] as? String }
                
                if (attempt > 0) {
                    Timber.tag("getFans").d("✅ Successfully fetched fans after retry (attempt ${attempt + 1}/${maxRetries + 1})")
                }
                return result
                
            } catch (e: Exception) {
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                
                if (isNetworkError && attempt < maxRetries) {
                    val delayMs = minOf(5000L, 1000L * (1 shl attempt))
                    Timber.tag("getFans").d("⏳ Network error detected, waiting ${delayMs / 1000}s before retry (attempt ${attempt + 1}/${maxRetries + 1})")
                    delay(delayMs)
                    continue
                }
                
                Timber.tag("Hprose.getFans").e(e)
                if (attempt >= maxRetries) {
                    Timber.tag("getFans").e("❌ All retry attempts exhausted after ${maxRetries + 1} attempts")
                }
            }
        }
        
        return null
    }

    /**
     * Load tweets of appUser and its followings from network.
     * Keep null elements in the response list and preserves their positions.
     * Includes retry logic with exponential backoff for network-related failures.
     * Always uses appUser - caller should wait for isAppUserInitialized before calling.
     * */
    suspend fun getTweetFeed(
        pageNumber: Int = 0,
        pageSize: Int = 5,
        entry: String = "get_tweet_feed",
        maxRetries: Int = 5,
        onRetry: ((attempt: Int, maxRetries: Int) -> Unit)? = null
    ): List<Tweet?> {
        if (!isOnline.value) {
            Timber.tag("getTweetFeed").d("Offline: skipping network call for page $pageNumber")
            return emptyList()
        }

        val alphaIds = getAlphaIds()
        val userIdForGuest = if (alphaIds.isNotEmpty()) alphaIds.first() else ""

        // For guest users, if no alpha IDs are configured, return empty list
        if (appUser.isGuest() && alphaIds.isEmpty()) {
            Timber.tag("getTweetFeed").w("No alpha IDs configured for guest user")
            return emptyList()
        }
        
        val params = mutableMapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "pn" to pageNumber,
            "ps" to pageSize,
            "userid" to if (!appUser.isGuest()) appUser.mid else userIdForGuest,
            "appuserid" to appUser.mid,
            "v4only" to v4Only.toString()
        )
        if (entry == "update_following_tweets") {
            appUser.hostIds?.first()?.let { hostId ->
                params["hostid"] = hostId
            }
        }
        
        // Retry logic
        for (attempt in 0..maxRetries) {
            try {
                val forceRefresh = attempt > 0
                if (forceRefresh) {
                    // Notify UI about retry attempt
                    onRetry?.invoke(attempt, maxRetries)
                    
                    Timber.tag("getTweetFeed").d("🔄 Retry attempt $attempt: Refreshing appUser's baseUrl")
                    // Refresh appUser's baseUrl on retry
                    val refreshedUser = fetchUser(appUser.mid, baseUrl = "")
                    if (refreshedUser != null) {
                        // Update appUser's baseUrl (hproseService is computed from baseUrl)
                        appUser.baseUrl = refreshedUser.baseUrl
                        Timber.tag("getTweetFeed").d("✅ Refreshed appUser baseUrl: ${appUser.baseUrl}")
                    } else {
                        Timber.tag("getTweetFeed").w("⚠️ Failed to refresh appUser baseUrl on retry")
                    }
                }
                
                val response = try {
                    appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
                } catch (e: Exception) {
                    Timber.tag("getTweetFeed").e(e, "Exception calling runMApp for getTweetFeed, entry: $entry, appUser: ${appUser.mid} (attempt ${attempt + 1}/${maxRetries + 1})")
                    throw e
                }

                // Check success status first
                val success = response?.get("success") as? Boolean
                if (success != true) {
                    val serverMessage = response?.get("message") as? String
                    Timber.tag("getTweetFeed").w("Feed failed: ${serverMessage ?: "Unknown error occurred"} (attempt ${attempt + 1}/${maxRetries + 1})")
                    
                    // Throw exception to trigger retry logic for server failures
                    // This allows baseUrl refresh and retry on different nodes
                    throw Exception("Server returned failure: ${serverMessage ?: "Unknown error"}")
                }

                // Extract tweets and originalTweets from the new response format
                val tweetsData = response["tweets"] as? List<Map<String, Any>?>
                val originalTweetsData = response["originalTweets"] as? List<Map<String, Any>?>

                // Cache original tweets by authorId
                originalTweetsData?.forEach { originalTweetJson ->
                    if (originalTweetJson != null) {
                        try {
                            val originalTweet = Tweet.from(originalTweetJson)
                            
                            // IMPORTANT: Set cached author FIRST (immediate, fast)
                            originalTweet.author = TweetCacheManager.getCachedUser(originalTweet.authorId)
                            
                            // Then fetch fresh author from server (slow network call)
                            val fetchedAuthor = fetchUser(originalTweet.authorId)
                            if (fetchedAuthor != null) {
                                originalTweet.author = fetchedAuthor
                            }
                            
                            // Log warning if author is still null
                            if (originalTweet.author == null) {
                                Timber.tag("getTweetFeed").w("⚠️ Failed to get author for original tweet ${originalTweet.mid}, authorId: ${originalTweet.authorId}")
                            }
                            
                            TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId)
                        } catch (e: Exception) {
                            Timber.tag("getTweetFeed").e("Error caching original tweet: $e")
                        }
                    }
                }

                // Process main tweets - cache mainfeed tweets under appUser.mid
                val result = tweetsData?.map { tweetJson ->
                    // If the element is null, keep it as null
                    if (tweetJson == null) {
                        null
                    } else {
                        // Try to decode the tweet
                        try {
                            val tweet = Tweet.from(tweetJson)
                            
                            // IMPORTANT: Set cached author FIRST (immediate, fast)
                            // This ensures tweets render with cached author data right away
                            // For appUser's tweets, use appUser directly since it's always the most up-to-date
                            if (tweet.authorId == appUser.mid) {
                                tweet.author = appUser
                            } else {
                                tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
                            }

                            // Then fetch fresh author from server (slow network call)
                            // Update author if fetch succeeds
                            val fetchedAuthor = fetchUser(tweet.authorId)
                            if (fetchedAuthor != null) {
                                tweet.author = fetchedAuthor
                            }
                            
                            // Log warning if author is still null after both cache and fetch attempts
                            if (tweet.author == null) {
                                Timber.tag("getTweetFeed").w("⚠️ Failed to get author for tweet ${tweet.mid}, authorId: ${tweet.authorId}")
                            }

                            // Skip private tweets in feed
                            if (tweet.isPrivate) {
                                null
                            } else {
                                // Cache mainfeed tweets under appUser.mid
                                updateCachedTweet(tweet, userId = appUser.mid)
                                tweet
                            }
                        } catch (e: Exception) {
                            Timber.tag("getTweetFeed").e("Error decoding tweet: $e")
                            null
                        }
                    }
                } ?: emptyList()
                
                // Success! Return the result
                if (attempt > 0) {
                    Timber.tag("getTweetFeed").d("✅ Successfully fetched tweets after retry (attempt ${attempt + 1}/${maxRetries + 1})")
                }
                return result
                
            } catch (e: Exception) {
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                
                if (isNetworkError && attempt < maxRetries) {
                    val delayMs = minOf(5000L, 1000L * (1 shl attempt)) // Exponential backoff: 1s, 2s, 4s
                    Timber.tag("getTweetFeed").d("⏳ Network error detected, waiting ${delayMs / 1000}s before retry (attempt ${attempt + 1}/${maxRetries + 1})")
                    delay(delayMs)
                    continue
                }
                
                // Log final error
                Timber.tag("getTweetFeed").e("Exception: $e")
                Timber.tag("getTweetFeed").e("❌ STACK TRACE: ${e.stackTraceToString()}")
                
                if (attempt >= maxRetries) {
                    Timber.tag("getTweetFeed").e("❌ All retry attempts exhausted after ${maxRetries + 1} attempts")
                }
            }
        }
        
        // All retries exhausted
        return emptyList()
    }

    /**
     * Load tweets of a specific user by rank.
     * Handles null elements in the response list and preserves their positions.
     * */
    suspend fun getTweetsByUser(
        user: User,
        pageNumber: Int = 0,
        pageSize: Int = 5,
        entry: String = "get_tweets_by_user"
    ): List<Tweet?> {
        if (!isOnline.value) {
            Timber.tag("getTweetsByUser").d("Offline: skipping network call")
            return emptyList()
        }
        try {
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "userid" to user.mid,
                "pn" to pageNumber,
                "ps" to pageSize,
                "appuserid" to appUser.mid,
                "v4only" to v4Only.toString()
            )
            
            if (user.hproseService == null) {
                Timber.tag("getTweetsByUser").e("❌ user.hproseService is NULL! Cannot fetch tweets")
                return emptyList()
            }
            
            val response = try {
                user.hproseService?.runMApp<Map<String, Any>>(entry, params)
            } catch (e: Exception) {
                Timber.tag("getTweetsByUser").e(e, "❌ Exception calling runMApp for getTweetsByUser, userId: ${user.mid}")
                throw e
            }
            
            Timber.tag("getTweetsByUser").d("🔍 Response received: ${response != null}")

            // Check success status first
            val success = response?.get("success") as? Boolean
            if (success != true) {
                val serverMessage = response?.get("message") as? String
                Timber.tag("getTweetsByUser")
                    .e("Tweets loading failed for user ${user.mid}: ${serverMessage ?: "Unknown error occurred"}")

                return emptyList()
            }

            // Extract tweets and originalTweets from the new response format
            val tweetsData = response["tweets"] as? List<Map<String, Any>?>
            val originalTweetsData = response["originalTweets"] as? List<Map<String, Any>?>

            // Cache original tweets by authorId
            originalTweetsData?.forEach { originalTweetJson ->
                if (originalTweetJson != null) {
                    try {
                        val originalTweet = Tweet.from(originalTweetJson)
                        
                        // IMPORTANT: Set cached author FIRST (immediate, fast)
                        // For appUser's tweets, use appUser directly since it's always the most up-to-date
                        if (originalTweet.authorId == appUser.mid) {
                            originalTweet.author = appUser
                        } else {
                            originalTweet.author = TweetCacheManager.getCachedUser(originalTweet.authorId)
                        }

                        // Then fetch fresh author from server (slow network call)
                        val fetchedAuthor = fetchUser(originalTweet.authorId)
                        if (fetchedAuthor != null) {
                            originalTweet.author = fetchedAuthor
                        }
                        
                        // Log warning if author is still null
                        if (originalTweet.author == null) {
                            Timber.tag("getTweetsByUser").w("⚠️ Failed to get author for original tweet ${originalTweet.mid}, authorId: ${originalTweet.authorId}")
                        }
                        
                        TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId)
                        Timber.tag("getTweetsByUser")
                            .d("Cached original tweet: ${originalTweet.mid}")
                    } catch (e: Exception) {
                        Timber.tag("getTweetsByUser").e(e, "Error caching original tweet")
                    }
                }
            }

            val result = tweetsData?.map { tweetJson ->
                // If the element is null, keep it as null
                if (tweetJson == null) {
                    null
                } else {
                    // Try to decode the tweet
                    try {
                        val tweet = Tweet.from(tweetJson)
                        tweet.author = user
                        // Note: originalTweet is no longer loaded here, it will be loaded on-demand in the UI
                        // Cache all tweets by their authorId
                        updateCachedTweet(tweet, userId = tweet.authorId)
                        tweet
                    } catch (e: Exception) {
                        Timber.tag("getTweetsByUser").e(e, "Error decoding tweet")
                        null
                    }
                }
            } ?: emptyList()

            Timber.tag("getTweetsByUser")
                .d("Received ${tweetsData?.size ?: 0} tweets (${result.filterNotNull().size} valid) and ${originalTweetsData?.size ?: 0} original tweets for user: ${user.mid}")

            return result
        } catch (e: Exception) {
            Timber.tag("getTweetsByUser").e("Error fetching tweets for user: ${user.mid}: ${e.message}")
            throw e
        }
    }

    /**
     * Get core data of the tweet. Do Not fetch its original tweet if there is any.
     * Let the caller to decide if go further on the tweet hierarchy.
     * Fetch a tweet by ID
     * */
    /**
     * Get tweet from the current provider of the tweet.
     * 
     * This function retrieves tweet data from the current provider node, which may not be the most
     * up-to-date version. It does NOT sync data from the author's host node. Use this for fetching
     * original tweets in retweets/quoted tweets where you just need the tweet data quickly.
     * 
     * For the latest data, use [refreshTweet] instead, which syncs from the author's host before retrieving.
     *
     * @param tweetId The ID of the tweet to retrieve
     * @param authorId The ID of the tweet's author
     * @return The tweet object, or null if not found
     */
    suspend fun fetchTweet(
        tweetId: MimeiId,
        authorId: MimeiId
    ): Tweet? {
        if (!isOnline.value) {
            Timber.tag("fetchTweet").d("Offline: skipping")
            return null
        }
        // Check if tweet is blacklisted
        if (BlackList.isBlacklisted(tweetId)) {
            Timber.tag("fetchTweet").d("Tweet $tweetId is blacklisted, returning null")
            return null
        }

        return try {
            // Check cache first using TweetCacheManager
            val cachedTweet = TweetCacheManager.getCachedTweet(tweetId)
            if (cachedTweet != null) {
                // Return cached tweet immediately with cached author
                cachedTweet.author = TweetCacheManager.getCachedUser(authorId)

                // Update author asynchronously in background
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val freshAuthor = fetchUser(authorId)
                        if (freshAuthor != null) {
                            cachedTweet.author = freshAuthor
                            Timber.tag("fetchTweet").d("✅ Updated cached tweet $tweetId with fresh author")
                        } else {
                            Timber.tag("fetchTweet").w("⚠️ Failed to fetch fresh author for cached tweet $tweetId, authorId: $authorId")
                        }
                    } catch (e: Exception) {
                        Timber.tag("fetchTweet").e("Error updating author for cached tweet $tweetId: $e")
                    }
                }

                return cachedTweet
            }

            val entry = "get_tweet"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "version" to "v2",
                "tweetid" to tweetId,
                "appuserid" to appUser.mid
            )

            // Get cached author for immediate use
            val cachedAuthor = TweetCacheManager.getCachedUser(authorId)

            val rawResponse = try {
                // Try to get the author for the API call, but don't block on it
                val authorForApi = fetchUser(authorId) ?: cachedAuthor
                authorForApi?.hproseService?.runMApp<Map<String, Any>>(entry, params)
            } catch (e: Exception) {
                Timber.tag("fetchTweet").e(e, "Exception calling runMApp for fetchTweet, tweetId: $tweetId, authorId: $authorId")
                throw e
            }
            val tweetData = unwrapV2Response<Map<String, Any>>(rawResponse)
            tweetData?.let {
                // Record successful access
                BlackList.recordSuccess(tweetId)

                Tweet.from(it).apply {
                    // Set cached author immediately
                    this.author = cachedAuthor

                    // Cache tweet by authorId, not appUser.mid
                    TweetCacheManager.saveTweet(
                        this,
                        userId = authorId
                    )

                    // Update author asynchronously in background
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val freshAuthor = fetchUser(authorId)
                            if (freshAuthor != null) {
                                this@apply.author = freshAuthor
                                Timber.tag("fetchTweet").d("✅ Updated fetched tweet $tweetId with fresh author")
                            } else {
                                Timber.tag("fetchTweet").w("⚠️ Failed to fetch fresh author for tweet $tweetId, authorId: $authorId")
                            }
                        } catch (e: Exception) {
                            Timber.tag("fetchTweet").e("Error updating author for fetched tweet $tweetId: $e")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Record failed access
            BlackList.recordFailure(tweetId)

            Timber.tag("fetchTweet").e("Error fetching tweet: $tweetId, author: $authorId")
            Timber.tag("fetchTweet").e("Exception: $e")

            null
        }
    }

    /**
     * Update cached but keep its timestamp when it was cached.
     * @param userId The user ID to cache under. Defaults to tweet.authorId
     * */
    fun updateCachedTweet(tweet: Tweet, userId: MimeiId? = null) {
        val cacheUserId = userId ?: tweet.authorId
        TweetCacheManager.updateCachedTweet(tweet, cacheUserId)
    }

    /**
     * Refresh tweet by syncing from author's host and retrieving the latest data.
     * 
     * This function not only retrieves the tweet but also updates the current provider's data to match
     * the host of the author (where the tweet is actually written to). This ensures you get the most
     * up-to-date version of the tweet, including any recent changes or updates.
     * 
     * Use this in detail views where you need the latest data. For quick retrieval of original tweets
     * in retweets/quoted tweets, use [fetchTweet] instead.
     * 
     * Called when the given tweet is visible in a detail view.
     *
     * @param tweetId The ID of the tweet to refresh
     * @param authorId The ID of the tweet's author
     * @return The refreshed tweet object, or null if not found
     */
    @Suppress("SENSELESS_COMPARISON")
    suspend fun refreshTweet(
        tweetId: MimeiId?,
        authorId: MimeiId?
    ): Tweet? {
        if (!isOnline.value) {
            Timber.tag("refreshTweet").d("Offline: skipping")
            return null
        }
        if (tweetId == null || authorId == null) {
            Timber.tag("refreshTweet").w("Null parameters: tweetId=$tweetId, authorId=$authorId")
            return null
        }

        // Check if tweet is blacklisted
        if (BlackList.isBlacklisted(tweetId)) {
            Timber.tag("refreshTweet").d("Tweet $tweetId is blacklisted, returning null")
            return null
        }

        return try {
            // Get cached author for immediate use
            val cachedAuthor = TweetCacheManager.getCachedUser(authorId)
            if (cachedAuthor == null) {
                Timber.tag("refreshTweet").w("⚠️ No cached author available for tweet $tweetId, authorId: $authorId")
            }

            // Try to get fresh author for API call, fallback to cached
            val authorForApi = fetchUser(authorId) ?: cachedAuthor
            if (authorForApi == null) {
                Timber.tag("refreshTweet").w("⚠️ Failed to get any author for tweet $tweetId, authorId: $authorId")
                return null
            }
            val entry = "refresh_tweet"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "version" to "v2",
                "tweetid" to tweetId,
                "appuserid" to appUser.mid,
                "userid" to authorId,
                "hostid" to (authorForApi.hostIds?.first() ?: "")
            )
            val rawResponse = try {
                authorForApi.hproseService?.runMApp<Map<String, Any>>(entry, params)
            } catch (e: Exception) {
                Timber.tag("refreshTweet").e(e, "Exception calling runMApp for refresh_tweet, tweetId: $tweetId, authorId: $authorId")
                throw e
            }
            
            // Unwrap v2 response format: {success: true, data: result} or {success: false, message: "..."}
            val tweetData = if (rawResponse != null && rawResponse.containsKey("success")) {
                val success = when (val successValue = rawResponse["success"]) {
                    is Boolean -> successValue
                    is Int -> successValue != 0
                    else -> false
                }
                if (success) {
                    rawResponse["data"] as? Map<String, Any> ?: rawResponse
                } else {
                    null
                }
            } else {
                rawResponse
            }
            
            tweetData?.let {
                // Record successful access
                BlackList.recordSuccess(tweetId)

                val tweet = Tweet.from(it)
                // Set cached author immediately
                tweet.author = cachedAuthor

                // Update author asynchronously in background
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val freshAuthor = fetchUser(authorId)
                        if (freshAuthor != null) {
                            tweet.author = freshAuthor
                            Timber.tag("refreshTweet").d("✅ Updated refreshed tweet $tweetId with fresh author")
                        } else {
                            Timber.tag("refreshTweet").w("⚠️ Failed to fetch fresh author for refreshed tweet $tweetId, authorId: $authorId")
                        }
                    } catch (e: Exception) {
                        Timber.tag("refreshTweet").e("Error updating author for refreshed tweet $tweetId: $e")
                    }
                }

                tweet
            }
        } catch (e: Exception) {
            // Record failed access
            BlackList.recordFailure(tweetId)

            Timber.tag("refreshTweet").e("Error refreshing tweet: $tweetId, author: $authorId")
            Timber.tag("refreshTweet").e("Exception: $e")

            null
        }
    }

    private fun registerNetworkCallback() {
        val cm = applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        // Set initial state
        val network = cm.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null
        _isOnline.value = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        Timber.tag("Network").d("Initial online state: ${_isOnline.value}")

        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                _isOnline.value = true
                Timber.tag("Network").d("Network available")
            }
            override fun onLost(network: android.net.Network) {
                _isOnline.value = false
                Timber.tag("Network").d("Network lost")
                // Stop all video players to prevent futile network retries
                VideoManager.stopAllVideos()
            }
        })
    }

    suspend fun loadCachedTweets(
        startRank: Int,  // earlier in time, therefore smaller timestamp
        count: Int,
    ): List<Tweet> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Load tweets cached for mainfeed (uid = appUser.mid)
            val userId = appUser.mid
            Timber.tag("loadCachedTweets").d("Loading cached tweets for mainfeed user: $userId")
            
            dao.getCachedTweetsByUser(userId, startRank, count).mapNotNull { cachedTweet ->
                val tweet = cachedTweet.originalTweet
                
                // Skip tweets with null authorId (should never happen, but safety check)
                if (tweet.authorId.isEmpty()) {
                    Timber.tag("loadCachedTweets").w("⚠️ Skipping tweet ${tweet.mid} with null/empty authorId")
                    return@mapNotNull null
                }
                
                // Skip private tweets in mainfeed
                if (tweet.isPrivate) {
                    return@mapNotNull null
                }
                
                // Always populate author from user cache (author field is not serialized with tweet)
                // For appUser's tweets, use appUser directly since it's always the most up-to-date singleton
                if (tweet.authorId == appUser.mid) {
                    tweet.author = appUser
                } else {
                    tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
                    
                    // If no cached user found, create a skeleton user object as placeholder for offline loading
                    if (tweet.author == null) {
                        tweet.author = getUserInstance(tweet.authorId)
                        Timber.tag("loadCachedTweets").w("Created skeleton user placeholder for tweet ${tweet.mid}, authorId: ${tweet.authorId}")
                    }
                }
                
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("loadCachedTweets").e("❌ Error loading cached tweets: $e")
            emptyList()
        }
    }

    /**
     * Load cached tweets for a specific user profile (filtered by authorId)
     * Used for offline loading of user profile tweets
     * 
     * For any user profile, we check both cache buckets and filter by authorId:
     * 1. Mainfeed cache (uid = appUser.mid) - contains tweets from all followings
     * 2. User's own cache (uid = authorId) - contains tweets cached from their profile
     * 
     * This ensures tweets cached from the mainfeed can still be loaded for individual user profiles.
     */
    suspend fun loadCachedTweetsByAuthor(
        authorId: MimeiId,
        startRank: Int,
        count: Int,
    ): List<Tweet> = withContext(Dispatchers.IO) {
        return@withContext try {
            Timber.tag("loadCachedTweetsByAuthor").d("Loading cached tweets for author: $authorId")
            
            val allCachedTweets = mutableListOf<Tweet>()
            
            // Load from mainfeed cache (appUser.mid) and filter by authorId
            // This ensures we can find tweets that were cached when viewing the mainfeed
            Timber.tag("loadCachedTweetsByAuthor").d("Checking mainfeed cache (uid = appUser.mid) for author: $authorId")
            dao.getCachedTweetsByUser(appUser.mid, 0, count * 3).forEach { cachedTweet ->
                val tweet = cachedTweet.originalTweet
                if (tweet.authorId.isNotEmpty() && tweet.authorId == authorId) {
                    allCachedTweets.add(tweet)
                }
            }
            
            // Also load from user's own cache bucket (userId = authorId) and filter by authorId
            Timber.tag("loadCachedTweetsByAuthor").d("Checking user's own cache (uid = $authorId) for author: $authorId")
            dao.getCachedTweetsByUser(authorId, 0, count * 3).forEach { cachedTweet ->
                val tweet = cachedTweet.originalTweet
                if (tweet.authorId.isNotEmpty() && tweet.authorId == authorId) {
                    allCachedTweets.add(tweet)
                }
            }
            
            // Populate authors and filter
            allCachedTweets.mapNotNull { tweet ->
                // Filter out private tweets unless viewing appUser's own profile
                if (tweet.isPrivate && authorId != appUser.mid) {
                    return@mapNotNull null
                }
                
                // Always populate author from user cache (author field is not serialized with tweet)
                // For appUser's tweets, use appUser directly since it's always the most up-to-date singleton
                if (tweet.authorId == appUser.mid) {
                    tweet.author = appUser
                    Timber.tag("loadCachedTweetsByAuthor").d("Using appUser singleton for tweet ${tweet.mid} - author: ${appUser.username}")
                } else {
                    tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)

                    // If no cached user found, create a skeleton user object as placeholder for offline loading
                    if (tweet.author == null) {
                        tweet.author = getUserInstance(tweet.authorId)
                        Timber.tag("loadCachedTweetsByAuthor").d("Created skeleton user placeholder for tweet ${tweet.mid} - authorId ${tweet.authorId}")
                    }
                }
                
                Timber.tag("loadCachedTweetsByAuthor").d("Loaded cached tweet ${tweet.mid} with author ${tweet.author?.username ?: tweet.authorId}")
                tweet
            }
            .distinctBy { it.mid }
            .sortedByDescending { it.timestamp }
            .drop(startRank)
            .take(count)
        } catch (e: Exception) {
            Timber.tag("loadCachedTweetsByAuthor").e("Error loading cached tweets by author: $e")
            emptyList()
        }
    }

    /**
     * Load cached bookmarks for the app user
     * Bookmarks are cached with special userId to prevent main feed pollution
     */
    suspend fun loadCachedBookmarks(
        startRank: Int,
        count: Int
    ): List<Tweet> = withContext(Dispatchers.IO) {
        return@withContext try {
            val bookmarksCacheId = TweetCacheManager.getBookmarksCacheId(appUser.mid)
            Timber.tag("loadCachedBookmarks").d("Loading cached bookmarks from cache ID: $bookmarksCacheId")
            
            dao.getCachedTweetsByUser(bookmarksCacheId, startRank, count).mapNotNull { cachedTweet ->
                val tweet = cachedTweet.originalTweet
                
                // Skip tweets with null authorId
                if (tweet.authorId.isEmpty()) {
                    Timber.tag("loadCachedBookmarks").w("⚠️ Skipping tweet ${tweet.mid} with null/empty authorId")
                    return@mapNotNull null
                }
                
                // Populate author from user cache
                if (tweet.authorId == appUser.mid) {
                    tweet.author = appUser
                } else {
                    tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
                    
                    // If no cached user found, create a skeleton user object
                    if (tweet.author == null) {
                        tweet.author = getUserInstance(tweet.authorId)
                        Timber.tag("loadCachedBookmarks").w("Created skeleton user placeholder for bookmarked tweet ${tweet.mid}, authorId: ${tweet.authorId}")
                    }
                }
                
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("loadCachedBookmarks").e("❌ Error loading cached bookmarks: $e")
            emptyList()
        }
    }

    /**
     * Load cached favorites for the app user
     * Favorites are cached with special userId to prevent main feed pollution
     */
    suspend fun loadCachedFavorites(
        startRank: Int,
        count: Int
    ): List<Tweet> = withContext(Dispatchers.IO) {
        return@withContext try {
            val favoritesCacheId = TweetCacheManager.getFavoritesCacheId(appUser.mid)
            Timber.tag("loadCachedFavorites").d("Loading cached favorites from cache ID: $favoritesCacheId")
            
            dao.getCachedTweetsByUser(favoritesCacheId, startRank, count).mapNotNull { cachedTweet ->
                val tweet = cachedTweet.originalTweet
                
                // Skip tweets with null authorId
                if (tweet.authorId.isEmpty()) {
                    Timber.tag("loadCachedFavorites").w("⚠️ Skipping tweet ${tweet.mid} with null/empty authorId")
                    return@mapNotNull null
                }
                
                // Populate author from user cache
                if (tweet.authorId == appUser.mid) {
                    tweet.author = appUser
                } else {
                    tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
                    
                    // If no cached user found, create a skeleton user object
                    if (tweet.author == null) {
                        tweet.author = getUserInstance(tweet.authorId)
                        Timber.tag("loadCachedFavorites").w("Created skeleton user placeholder for favorited tweet ${tweet.mid}, authorId: ${tweet.authorId}")
                    }
                }
                
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("loadCachedFavorites").e("❌ Error loading cached favorites: $e")
            emptyList()
        }
    }

    /**
     * Increase/decrease the retweetCount of the original tweet mimei.
     * @param originalTweet is the original tweet
     * @param retweetId of the retweet.
     * @param direction to indicate increase or decrease retweet count.
     * @return the updated original tweet.
     * */
    suspend fun updateRetweetCount(
        originalTweet: Tweet,
        retweetId: MimeiId,
        direction: Int = 1
    ): Tweet? {
        if (!isOnline.value) {
            Timber.tag("updateRetweetCount").d("Offline: skipping")
            return null
        }
        val entry = if (direction == 1) "retweet_added" else "retweet_removed"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "entry" to entry,
            "tweetid" to originalTweet.mid,
            "appuserid" to appUser.mid,
            "retweetid" to retweetId,
            "authorid" to originalTweet.authorId
        )
        return try {
            val rawResponse = try {
                originalTweet.author?.hproseService?.runMApp<Map<String, Any>>(entry, params)
            } catch (e: Exception) {
                Timber.tag("updateRetweetCount").e(e, "Exception calling runMApp for updateRetweetCount, tweetId: ${originalTweet.mid}")
                throw e
            }
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)
            response?.let {
                Tweet.from(it)
            }
        } catch (e: Exception) {
            Timber.tag("updateRetweetCount()").e(e)
            null
        }
    }

    suspend fun uploadTweet(tweet: Tweet): Tweet? {
        if (!isOnline.value) {
            Timber.tag("uploadTweet").d("Offline: skipping")
            throw Exception("No network connection")
        }
        Timber.tag("HproseInstance").d("uploadTweet called for tweet: ${tweet.mid}, content: '${tweet.content}', attachments: ${tweet.attachments?.size ?: 0}")

        val entry = "add_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "hostid" to (appUser.hostIds?.first() ?: ""),
            "tweet" to Json.encodeToString(tweet)
        )
        return try {
            val rawResponse = try {
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
            } catch (e: Exception) {
                Timber.tag("uploadTweet").e(e, "Exception calling runMApp for uploadTweet")
                throw e
            }
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)
            if (response != null) {
                val newTweetId = response["mid"] as? String
                if (newTweetId != null) {
                    // Create a new tweet with the updated mid
                    val updatedTweet = tweet.copy(mid = newTweetId, author = appUser)

                    Timber.tag("HproseInstance").d("Tweet uploaded successfully with new ID: $newTweetId")

                    // Post notification for successful upload (only for original tweets, not retweets)
                    if (tweet.originalTweetId == null) {
                        Timber.tag("HproseInstance").d("Posting TweetUploaded notification for tweet: ${updatedTweet.mid}, author: ${updatedTweet.authorId}")
                        TweetNotificationCenter.post(TweetEvent.TweetUploaded(updatedTweet))
                    } else {
                        Timber.tag("HproseInstance").d("Skipping TweetUploaded notification for retweet: ${updatedTweet.mid}, original: ${tweet.originalTweetId}")
                    }
                    
                    // Refresh appUser from server to get updated tweetCount and other properties
                    try {
                        // Invalidate cache to force fresh fetch from server
                        TweetCacheManager.removeCachedUser(appUser.mid)
                        val refreshedUser = fetchUser(appUser.mid, appUser.baseUrl, maxRetries = 1)
                        if (refreshedUser != null && !refreshedUser.isGuest()) {
                            // Update singleton and set appUser to it
                            User.updateUserInstance(refreshedUser, true)
                            appUser = User.getInstance(refreshedUser.mid)
                            
                            // Notify other ViewModels that user data has been updated
                            TweetNotificationCenter.post(TweetEvent.UserDataUpdated(appUser))
                        }
                    } catch (e: Exception) {
                        Timber.tag("uploadTweet").w("Failed to refresh appUser after upload: $e")
                    }

                    Timber.tag("uploadTweet").d("Tweet uploaded: $newTweetId")
                    updatedTweet
                } else {
                    Timber.tag("uploadTweet").e("No tweet ID in response")
                    null
                }
            } else {
                val serverMessage = rawResponse?.get("message") as? String
                Timber.tag("uploadTweet").e("Upload failed: ${serverMessage ?: "Unknown upload error"}")
                null
            }
        } catch (e: OutOfMemoryError) {
            Timber.tag("uploadTweet").e(e, "OUT OF MEMORY ERROR during tweet upload")
            null
        } catch (e: Exception) {
            Timber.tag("uploadTweet").e("Upload exception: $e")
            null
        }
    }


    suspend fun delComment(parentTweet: Tweet, commentId: MimeiId, callback: (MimeiId) -> Unit) {
        if (!isOnline.value) {
            Timber.tag("delComment").d("Offline: skipping")
            throw Exception("No network connection")
        }
        val entry = "delete_comment"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "entry" to entry,
            "tweetid" to parentTweet.mid,
            "commentid" to commentId,
            "userid" to appUser.mid,
            "hostid" to (parentTweet.author?.hostIds?.first() ?: "")
        )
        try {
            val rawResponse = try {
                parentTweet.author?.hproseService?.runMApp<Any>(entry, params)
            } catch (e: Exception) {
                Timber.tag("delComment").e(e, "Exception calling runMApp for delComment, tweetId: ${parentTweet.mid}, commentId: $commentId")
                throw e
            }
            val response = unwrapV2Response<Boolean>(rawResponse)

            if (response == true) {
                // Post notification for successful comment deletion
                TweetNotificationCenter.post(TweetEvent.CommentDeleted(commentId, parentTweet.mid))
                callback(commentId)
            }
        } catch (e: Exception) {
            Timber.tag("delComment()").e(e)
        }
    }

    /**
     * Called when appUser clicks the Follow button.
     * @param followedId is the user that appUser is following or unfollowing.
     * */
    suspend fun toggleFollowing(
        followedId: MimeiId,
        followingId: MimeiId = appUser.mid
    ): Boolean? {
        if (!isOnline.value) {
            Timber.tag("toggleFollowing").d("Offline: skipping")
            throw Exception("No network connection")
        }
        val entry = "toggle_following"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "followingid" to followedId,
            "userid" to followingId
        )
        return try {
            Timber.tag("toggleFollowing").d("Calling toggle_following: followedId=$followedId, followingId=$followingId, baseUrl=${appUser.baseUrl}")
            if (appUser.hproseService == null) {
                Timber.tag("toggleFollowing").e("hproseService is null! Cannot call toggle_following")
                return null
            }
            Timber.tag("toggleFollowing").d("About to call runMApp with entry=$entry, params=$params")
            val startTime = System.currentTimeMillis()
            
            // Wrap in try-catch to catch any exceptions from hprose client
            val rawResponse = try {
                appUser.hproseService?.runMApp<Any>(entry, params)
            } catch (e: Throwable) {
                Timber.tag("toggleFollowing").e(e, "Exception thrown by runMApp: ${e.javaClass.simpleName}, message: ${e.message}")
                Timber.tag("toggleFollowing").e(e, "Full exception: $e")
                // Re-throw to be caught by outer catch
                throw e
            }
            
            val duration = System.currentTimeMillis() - startTime
            Timber.tag("toggleFollowing").d("toggle_following completed in ${duration}ms, rawResponse: $rawResponse (type: ${rawResponse?.javaClass?.simpleName})")
            
            when (rawResponse) {
                is Boolean -> {
                    // Legacy format: direct boolean response
                    Timber.tag("toggleFollowing").d("toggle_following returned boolean: $rawResponse")
                    rawResponse
                }
                is Map<*, *> -> {
                    val responseMap = rawResponse as? Map<String, Any>
                    // Check for v2 error response
                    if (responseMap?.get("success") == false) {
                        val error = responseMap["message"] as? String ?: "Unknown error"
                        Timber.tag("toggleFollowing").e("Server returned error: $error")
                        null
                    } else {
                        // For v2 API: server returns {success: true, data: {isFollowing: bool}}
                        // Extract isFollowing from the data dictionary
                        when (val data = responseMap?.get("data")) {
                            is Map<*, *> -> {
                                val dataMap = data as? Map<String, Any>
                                val isFollowing = dataMap?.get("isFollowing") as? Boolean
                                if (isFollowing != null) {
                                    Timber.tag("toggleFollowing").d("toggle_following returned v2 format isFollowing: $isFollowing")
                                    isFollowing
                                } else {
                                    Timber.tag("toggleFollowing").e("toggle_following v2 response missing isFollowing field: $dataMap")
                                    null
                                }
                            }
                            is Boolean -> {
                                // Direct boolean in data field
                                Timber.tag("toggleFollowing").d("toggle_following returned boolean in data field: $data")
                                data
                            }
                            else -> {
                                // Try direct isFollowing field in response
                                val isFollowing = responseMap?.get("isFollowing") as? Boolean
                                if (isFollowing != null) {
                                    Timber.tag("toggleFollowing").d("toggle_following returned isFollowing directly: $isFollowing")
                                    isFollowing
                                } else {
                                    Timber.tag("toggleFollowing").e("toggle_following returned Map but couldn't extract isFollowing: $responseMap")
                                    null
                                }
                            }
                        }
                    }
                }
                null -> {
                    Timber.tag("toggleFollowing").w("toggle_following returned null after ${duration}ms - backend may have failed, timed out, or returned undefined")
                    Timber.tag("toggleFollowing").w("This could indicate: 1) Server error/exception, 2) Timeout, 3) Server returned undefined")
                    null
                }
                else -> {
                    Timber.tag("toggleFollowing").e("toggle_following returned unexpected type: ${rawResponse.javaClass.simpleName}, value: $rawResponse")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.tag("toggleFollowing").e(e, "Error calling toggle_following: ${e.message}")
            Timber.tag("toggleFollowing").e(e, "Exception type: ${e.javaClass.simpleName}")
            Timber.tag("toggleFollowing").e(e, "Full stack trace:")
            e.printStackTrace()
            null
        } catch (e: Throwable) {
            // Catch any other throwables (like Errors)
            Timber.tag("toggleFollowing").e(e, "Throwable caught calling toggle_following: ${e.message}")
            Timber.tag("toggleFollowing").e(e, "Throwable type: ${e.javaClass.simpleName}")
            e.printStackTrace()
            null
        }
    }



    /**
     * Send a retweet request to backend and get a new tweet object back.
     * */
    suspend fun retweet(
        tweet: Tweet     // original tweet to be retweeted
    ) {
        if (!isOnline.value) {
            Timber.tag("retweet").d("Offline: skipping")
            throw Exception("No network connection")
        }
        try {
            // upload the retweet, simply a few dozen bytes.
            val retweet = uploadTweet(
                Tweet(
                    mid = TW_CONST.GUEST_ID,    // placeholder will be replaced in backend.
                    authorId = appUser.mid,
                    originalTweetId = tweet.mid,
                    originalAuthorId = tweet.authorId
                )
            ) ?: return

            updateRetweetCount(tweet, retweet.mid)?.let { updatedTweet ->
                // Cache updated original tweet by authorId (matches iOS)
                updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
            }

            // Cache the retweet by its authorId
            updateCachedTweet(retweet, userId = retweet.authorId)

            // Post notification for retweet
            TweetNotificationCenter.post(TweetEvent.TweetRetweeted(tweet, retweet))
        } catch (e: Exception) {
            Timber.tag("toggleRetweet").e(e.toString())
        }
    }

    /**
     * Load favorite status of the tweet by appUser.
     * */
    suspend fun toggleFavorite(tweet: Tweet): Tweet {
        if (!isOnline.value) {
            Timber.tag("toggleFavorite").d("Offline: skipping")
            throw Exception("No network connection")
        }
        val entry = "toggle_favorite"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "appuserid" to appUser.mid,
            "tweetid" to tweet.mid,
            "authorid" to tweet.authorId,
            "userhostid" to (appUser.hostIds?.first() ?: "")
        )
        return try {
            val authorClient = tweet.author?.hproseService
                ?: throw Exception("Author client not available for toggleFavorite")
            val rawResponse = try {
                authorClient.runMApp<Map<String, Any>>(entry, params)
            } catch (e: Exception) {
                Timber.tag("toggleFavorite").e(e, "Exception calling runMApp for toggleFavorite, tweetId: ${tweet.mid}")
                throw e
            }
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)

            if (response != null) {
                // Handle successful response with updated user and tweet data
                val updatedUserData = response["user"] as? Map<String, Any>
                val updatedTweetData = response["tweet"] as? Map<String, Any>
                
                if (updatedUserData != null) {
                    // Update appUser directly from the response data
                    appUser.from(updatedUserData)
                    TweetCacheManager.saveUser(appUser)
                }
                
                if (updatedTweetData != null) {
                    // Create updated tweet from server response
                    val updatedTweet = Tweet.from(updatedTweetData)
                    
                    // Preserve author from original tweet, or fetch if not available
                    // Don't overwrite with null from fetchUser
                    val fetchedAuthor = fetchUser(updatedTweet.authorId)
                    updatedTweet.author = fetchedAuthor ?: tweet.author ?: TweetCacheManager.getCachedUser(updatedTweet.authorId)
                    
                    // Cache by authorId
                    updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
                    return updatedTweet
                }
            } else {
                // Handle error response
                val error = rawResponse?.get("message") as? String
                Timber.tag("toggleFavorite").e("Favorite toggle failed: $error")
            }
            
            // Fallback to original tweet if parsing fails
            tweet
        } catch (e: Exception) {
            Timber.tag("toggleFavorite").e(e)
            tweet
        }
    }

    /**
     * Load bookmark status of the tweet by appUser.
     * */
    suspend fun toggleBookmark(tweet: Tweet): Tweet {
        if (!isOnline.value) {
            Timber.tag("toggleBookmark").d("Offline: skipping")
            throw Exception("No network connection")
        }
        val entry = "toggle_bookmark"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "userid" to appUser.mid,
            "tweetid" to tweet.mid,
            "authorid" to tweet.authorId,
            "userhostid" to (appUser.hostIds?.first() ?: "")
        )
        return try {
            val authorClient = tweet.author?.hproseService
                ?: throw Exception("Author client not available for toggleBookmark")
            val rawResponse = try {
                authorClient.runMApp<Map<String, Any>>(entry, params)
            } catch (e: Exception) {
                Timber.tag("toggleBookmark").e(e, "Exception calling runMApp for toggleBookmark, tweetId: ${tweet.mid}")
                throw e
            }
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)

            if (response != null) {
                // Handle successful response with updated user and tweet data
                val updatedUserData = response["user"] as? Map<String, Any>
                val updatedTweetData = response["tweet"] as? Map<String, Any>
                
                if (updatedUserData != null) {
                    // Update appUser directly from the response data
                    appUser.from(updatedUserData)
                    TweetCacheManager.saveUser(appUser)
                }
                
                if (updatedTweetData != null) {
                    // Create updated tweet from server response
                    val updatedTweet = Tweet.from(updatedTweetData)
                    
                    // Preserve author from original tweet, or fetch if not available
                    // Don't overwrite with null from fetchUser
                    val fetchedAuthor = fetchUser(updatedTweet.authorId)
                    updatedTweet.author = fetchedAuthor ?: tweet.author ?: TweetCacheManager.getCachedUser(updatedTweet.authorId)
                    
                    // Cache by authorId
                    updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
                    return updatedTweet
                }
            } else {
                // Handle error response
                val error = rawResponse?.get("message") as? String
                Timber.tag("toggleBookmark").e("Bookmark toggle failed: $error")
            }
            
            // Fallback to original tweet if parsing fails
            tweet
        } catch (e: Exception) {
            Timber.tag("toggleBookmark").e(e)
            tweet
        }
    }

    /**
     * Load favorite tweets, bookmarks or comments of an user.
     * Handles null elements in the response list and preserves their positions.
     * Caches bookmarks and favorites separately from main feed to prevent pollution.
     * */
    suspend fun getUserTweetsByType(
        user: User,
        type: UserContentType,
        pageNumber: Int = 0,
        pageSize: Int = TW_CONST.PAGE_SIZE
    ): List<Tweet?> {
        if (!isOnline.value) {
            Timber.tag("getUserTweetsByType").d("Offline: skipping")
            return emptyList()
        }
        val entry = "get_user_meta"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "entry" to entry,
            "userid" to user.mid,
            "type" to type.value,
            "pn" to pageNumber,
            "ps" to pageSize,
            "appuserid" to appUser.mid
        )
        return try {
            val rawResponse = try {
                user.hproseService?.runMApp<Any>(entry, params)
            } catch (e: Exception) {
                Timber.tag("getUserTweetsByType").e(e, "Exception calling runMApp for getUserTweetsByType, userId: ${user.mid}, type: $type")
                throw e
            }
            val response = unwrapV2Response<List<Map<String, Any>?>>(rawResponse)

            // Determine cache ID based on content type
            val cacheUserId = when (type) {
                UserContentType.BOOKMARKS -> TweetCacheManager.getBookmarksCacheId(user.mid)
                UserContentType.FAVORITES -> TweetCacheManager.getFavoritesCacheId(user.mid)
                else -> user.mid // Regular tweets cached by authorId
            }

            response?.map { tweetJson ->
                // If the element is null, keep it as null
                if (tweetJson == null) {
                    null
                } else {
                    // Try to decode the tweet
                    try {
                        val tweet = Tweet.from(tweetJson)

                        // IMPORTANT: Set cached author FIRST (immediate, fast)
                        tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)

                        // Then fetch fresh author from server (slow network call)
                        val fetchedAuthor = fetchUser(tweet.authorId)
                        if (fetchedAuthor != null) {
                            tweet.author = fetchedAuthor
                        }

                        // Log warning if author is still null
                        if (tweet.author == null) {
                            Timber.tag("getUserTweetsByType").w("⚠️ Failed to get author for tweet ${tweet.mid}, authorId: ${tweet.authorId}")
                        }

                        // Cache the tweet with appropriate cache ID
                        // Bookmarks and favorites use special cache IDs to prevent main feed pollution
                        TweetCacheManager.saveTweet(tweet, cacheUserId)
                        Timber.tag("getUserTweetsByType").d("Cached tweet ${tweet.mid} under userId: $cacheUserId (type: ${type.value})")

                        tweet
                    } catch (e: Exception) {
                        Timber.tag("getUserTweetsByType").e("Error decoding tweet: $e")
                        null
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Timber.tag("getUserTweetsByType").e(e)
            emptyList()
        }
    }

    /**
     * Delete a tweet and return the deleted tweetId. Only appUser can delete its own tweet.
     */
    suspend fun deleteTweet(tweetId: MimeiId): MimeiId? {
        if (!isOnline.value) {
            Timber.tag("deleteTweet").d("Offline: skipping")
            throw Exception("No network connection")
        }
        // Check if hproseService is available
        if (appUser.hproseService == null) {
            val errorMsg = "Cannot delete tweet: hproseService is null. User may not be properly initialized."
            Timber.tag("deleteTweet").e(errorMsg)
            throw Exception(errorMsg)
        }

        val entry = "delete_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "userid" to appUser.mid,
            "tweetid" to tweetId
        )
        return try {
            val rawResponse = try {
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
            } catch (e: Exception) {
                Timber.tag("deleteTweet").e(e, "Exception calling runMApp for deleteTweet, tweetId: $tweetId")
                throw e
            }
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)

            if (response == null) {
                val errorMsg = "Delete tweet failed: server returned null response"
                Timber.tag("deleteTweet").e(errorMsg)
                throw Exception(errorMsg)
            }

            val deletedTweetId = response["tweetid"] as? MimeiId

            if (deletedTweetId == null) {
                val errorMsg = "Delete tweet failed: server returned success but no tweetid"
                Timber.tag("deleteTweet").e(errorMsg)
                throw Exception(errorMsg)
            }

            // Refresh appUser from server to get updated tweetCount and other properties
            try {
                // Invalidate cache to force fresh fetch from server
                TweetCacheManager.removeCachedUser(appUser.mid)
                val refreshedUser = fetchUser(appUser.mid, appUser.baseUrl, maxRetries = 1)
                if (refreshedUser != null && !refreshedUser.isGuest()) {
                    // Update singleton and set appUser to it
                    User.updateUserInstance(refreshedUser, true)
                    appUser = User.getInstance(refreshedUser.mid)

                    // Notify other ViewModels that user data has been updated
                    TweetNotificationCenter.post(TweetEvent.UserDataUpdated(appUser))
                }
            } catch (e: Exception) {
                Timber.tag("deleteTweet").w("Failed to refresh appUser after deletion: $e")
            }

            deletedTweetId
        } catch (e: Exception) {
            Timber.tag("deleteTweet").e(e, "Error deleting tweet: ${e.message}")
            Timber.tag("deleteTweet").e("Stack trace: ${e.stackTraceToString()}")
            // Re-throw with original message or provide default
            throw Exception(e.message ?: "Unknown error occurred while deleting tweet")
        }
    }

    suspend fun deleteAccount(): Map<String, Any> {
        val entry = "delete_account"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "userid" to appUser.mid
        )
        val rawResponse = appUser.hproseService?.runMApp<Any>(entry, params)
        return unwrapV2Response<Map<String, Any>>(rawResponse) ?: emptyMap()
    }

    /**
     * Load all comments of a tweet.
     * @param pageNumber
     * */
    suspend fun getComments(tweet: Tweet, pageNumber: Int = 0, pageSize: Int = 5): List<Tweet>? {
        if (!isOnline.value) {
            Timber.tag("getComments").d("Offline: skipping")
            return null
        }
        return try {
            // CRITICAL: Use the tweet's author's baseUrl to fetch comments
            // Comments are stored on the tweet author's node, not the appUser's node
            // Fetch author if not already loaded
            if (tweet.author == null) {
                // For appUser's tweets, use appUser directly
                if (tweet.authorId == appUser.mid) {
                    tweet.author = appUser
                } else {
                    // Check cache first before fetching from server
                    tweet.author = TweetCacheManager.getCachedUser(tweet.authorId) ?: fetchUser(tweet.authorId)
                }
            }
            
            // Ensure author has a baseUrl (hproseService requires baseUrl)
            val author = tweet.author
            if (author == null || author.baseUrl.isNullOrEmpty()) {
                // Fetch author to ensure we have their baseUrl
                val fetchedAuthor = fetchUser(tweet.authorId)
                if (fetchedAuthor == null || fetchedAuthor.baseUrl.isNullOrEmpty()) {
                    Timber.tag("getComments()").e("Cannot fetch author or author has no baseUrl for tweet ${tweet.mid}")
                    return null
                }
                tweet.author = fetchedAuthor
            }
            
            val entry = "get_comments"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "version" to "v2",
                "tweetid" to tweet.mid,
                "appuserid" to appUser.mid,
                "pn" to pageNumber,
                "ps" to pageSize
            )
            
            // Use author's hproseService - comments are on author's node
            val authorService = tweet.author?.hproseService
            if (authorService == null) {
                Timber.tag("getComments()").e("Author's hproseService is null. baseUrl: ${tweet.author?.baseUrl} for tweet ${tweet.mid}")
                return null
            }
            
            Timber.tag("getComments()").d("Using author's baseUrl (${tweet.author?.baseUrl}) for tweet ${tweet.mid}")
            val rawResponse = try {
                authorService.runMApp<Any>(entry, params)
            } catch (e: Exception) {
                Timber.tag("getComments").e(e, "Exception calling runMApp for getComments, tweetId: ${tweet.mid}")
                throw e
            }
            val response = unwrapV2Response<List<Map<String, Any>?>>(rawResponse)

            response?.mapNotNull { tweetJson -> tweetJson?.let { Tweet.from(it) } }
        } catch (e: Exception) {
            Timber.tag("getComments()").e(e)
            null
        }
    }

    /**
     * The mid of "comment" is updated here, used to be null.
     * @Return the updated parent tweet.
     * */
    suspend fun uploadComment(tweet: Tweet, comment: Tweet): Tweet? {
        if (!isOnline.value) {
            Timber.tag("uploadComment").d("Offline: skipping")
            throw Exception("No network connection")
        }
        val entry = "add_comment"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "entry" to entry,
            "tweetid" to tweet.mid,
            "comment" to Json.encodeToString(comment),
            "userid" to appUser.mid,
            "hostid" to (tweet.author?.hostIds?.first() ?: "")
        )
        return try {
            val rawResponse = try {
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
            } catch (e: Exception) {
                Timber.tag("uploadComment").e(e, "Exception calling runMApp for uploadComment, tweetId: ${tweet.mid}")
                throw e
            }
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)

            if (response != null) {
                // update mid of comment, which was null when passed as argument
                val newCommentId = response["mid"] as? MimeiId ?: comment.mid
                val updatedComment = comment.copy(mid = newCommentId, author = appUser)

                val updatedTweet = tweet.copy(
                    commentCount = (response["count"] as? Number)?.toInt() ?: tweet.commentCount
                )
                // Cache by authorId
                updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)

                // Post notification for successful comment upload
                TweetNotificationCenter.post(
                    TweetEvent.CommentUploaded(
                        updatedComment,
                        updatedTweet
                    )
                )
                updatedTweet
            } else {
                val serverMessage = rawResponse?.get("message") as? String
                Timber.tag("uploadComment").e("Failed to upload comment: ${serverMessage ?: "Unknown error"}")
                null
            }
        } catch (e: Exception) {
            Timber.tag("uploadComment()").e(e)
            null
        }
    }

    fun getMediaUrl(mid: MimeiId?, baseUrl: String?): String? {
        if (mid != null && baseUrl != null) {
            return if (mid.length > 27) {
                "$baseUrl/ipfs/$mid"
            } else {
                "$baseUrl/mm/$mid"
            }
        }
        return null
    }

    // Track ongoing user updates to prevent concurrent calls for the same user
    private val ongoingUserUpdates = mutableSetOf<String>()
    private val userUpdateMutex = Mutex()

    /**
     * Given userId, get baseUrl where user data can be accessed.
     * An user mimei may be stored on many nodes.
     *
     * Algorithm based on iOS implementation:
     * 1. Check user cache first (if baseUrl matches appUser.baseUrl)
     * 2. If no baseUrl provided, get provider IP for the user
     * 3. Fetch user data from server using "get_user" entry
     * 4. Handle both user data and provider IP responses
     * 5. Update cache with fetched user data
     *
     * Cache expiration: Users are cached for 30 minutes. Expired users are refreshed from backend.
     * Includes retry logic with exponential backoff for network-related failures.
     */
    @Suppress("SENSELESS_COMPARISON")
    /**
     * Performs user update and cache save, handling success and error cases
     * @param userId User ID being fetched
     * @param user User instance to update
     * @param maxRetries Maximum retry attempts
     * @param skipRetryAndBlacklist Skip retry and blacklist logic
     * @param logContext Logging context
     * @param baseUrlHint Hint for cache logic ("" forces refresh, null uses defaults)
     * @return User if successful, null otherwise
     */
    private suspend fun performUserUpdate(
        userId: MimeiId,
        user: User,
        maxRetries: Int,
        skipRetryAndBlacklist: Boolean,
        logContext: String = "fetchUser",
        baseUrlHint: String? = null
    ): User? {
        return try {
            val effectiveMaxRetries = if (skipRetryAndBlacklist) 1 else maxRetries
            val fetchSuccess = updateUserFromServerWithRetry(user, effectiveMaxRetries, skipRetryAndBlacklist, baseUrlHint)
            
            if (fetchSuccess && isValidUserData(user)) {
                TweetCacheManager.saveUser(user)
                user
            } else {
                Timber.tag(logContext).w("Failed to fetch valid user data: userId: $userId")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Propagate cancellation without logging as error
            Timber.tag(logContext).d("Fetch cancelled for userId: $userId")
            throw e
        } catch (e: Exception) {
            Timber.tag(logContext).e(e, "Exception in user update: userId: $userId")
            null
        }
    }

    /**
     * Starts background refresh for expired cached user
     */
    private fun startBackgroundRefresh(
        userId: MimeiId, 
        cachedUser: User, 
        maxRetries: Int, 
        skipRetryAndBlacklist: Boolean,
        baseUrlHint: String?
    ) {
        TweetApplication.applicationScope.launch {
            try {
                val userInstance = getUserInstance(userId)
                // Don't overwrite user's baseUrl - keep their cached value
                // baseUrlHint will control whether to force IP resolution
                
                performUserUpdate(userId, userInstance, maxRetries, skipRetryAndBlacklist, "getUser.backgroundRefresh", baseUrlHint)
            } catch (e: Exception) {
                Timber.tag("getUser").e(e, "Background refresh error for userId: $userId")
            } finally {
                userUpdateMutex.withLock {
                    ongoingUserUpdates.remove(userId)
                }
            }
        }
    }

    /**
     * Waits for concurrent update to complete with timeout
     * @return Cached user if found, or retries fetchUser if not
     */
    private suspend fun waitForConcurrentUpdate(userId: MimeiId, baseUrl: String?, maxRetries: Int, forceRefresh: Boolean): User? {
        val maxWaitTime = 10000L // 10 seconds
        val startTime = System.currentTimeMillis()
        
        while (true) {
            delay(50)
            
            if (System.currentTimeMillis() - startTime > maxWaitTime) {
                Timber.tag("getUser").w("Timeout waiting for concurrent update to complete for userId: $userId")
                return TweetCacheManager.getCachedUser(userId)
            }
            
            val isStillUpdating = userUpdateMutex.withLock {
                ongoingUserUpdates.contains(userId)
            }
            
            if (!isStillUpdating) {
                return TweetCacheManager.getCachedUser(userId) ?: 
                    fetchUser(userId, baseUrl, maxRetries, forceRefresh)
            }
        }
    }

    /**
     * Fetch user data with intelligent retry and caching
     * 
     * @param userId User ID to fetch
     * @param baseUrl Base URL hint (empty string forces fresh IP resolution)
     * @param maxRetries Maximum retry attempts (default 2)
     * @param forceRefresh Skip cache and force fresh fetch
     * @param skipRetryAndBlacklist Skip retry logic and blacklist checks (for internal use)
     */
    suspend fun fetchUser(
        userId: MimeiId?, 
        baseUrl: String? = appUser.baseUrl, 
        maxRetries: Int = 2, 
        forceRefresh: Boolean = false, 
        skipRetryAndBlacklist: Boolean = false
    ): User? {
        if (userId == null) {
            Timber.tag("getUser").w("Null userId, returning null")
            return null
        }

        if (!skipRetryAndBlacklist && BlackList.isBlacklisted(userId)) {
            Timber.tag("getUser").d("User $userId is blacklisted, returning null")
            return null
        }

        // When offline, return cached user only
        if (!isOnline.value) {
            val cachedUser = TweetCacheManager.getCachedUser(userId)
            Timber.tag("getUser").d("Offline: returning cached user for $userId (found=${cachedUser != null})")
            return cachedUser
        }

        // Check cache first (if not forcing refresh)
        if (!forceRefresh) {
            val cachedUser = TweetCacheManager.getCachedUser(userId)
            if (cachedUser != null && cachedUser.username != null) {
                if (cachedUser.hasExpired) {
                    // Start background refresh if not already running
                    val shouldStartBackgroundRefresh = userUpdateMutex.withLock {
                        if (!ongoingUserUpdates.contains(userId)) {
                            ongoingUserUpdates.add(userId)
                            true
                        } else {
                            false
                        }
                    }

                    if (shouldStartBackgroundRefresh) {
                        startBackgroundRefresh(
                            userId,
                            cachedUser,
                            maxRetries,
                            skipRetryAndBlacklist,
                            baseUrl
                        )
                    }
                }
                // Always return cached user immediately so UI can render avatar
                return cachedUser
            }
        } else {
            Timber.tag("fetchUser").d("📡 forceRefresh=true, skipping cache and fetching from server for userId: $userId")
        }

        // Check if update already in progress
        val shouldProceed = userUpdateMutex.withLock {
            if (ongoingUserUpdates.contains(userId)) {
                false
            } else {
                ongoingUserUpdates.add(userId)
                true
            }
        }

        if (!shouldProceed) {
            Timber.tag("fetchUser").d("⏳ Concurrent update in progress, waiting... userId: $userId")
            return waitForConcurrentUpdate(userId, baseUrl, maxRetries, forceRefresh)
        }

        Timber.tag("fetchUser").d("🚀 Starting fresh server fetch for userId: $userId, baseUrl param: ${baseUrl ?: "null"}")
        try {
            val user = getUserInstance(userId)

            // CRITICAL: Load cached baseUrl into singleton if not already present
            // This ensures the first fetch attempt uses the cached baseUrl before trying getProviderIP
            if (user.baseUrl.isNullOrEmpty()) {
                val cachedUser = TweetCacheManager.getCachedUser(userId)
                if (cachedUser != null && !cachedUser.baseUrl.isNullOrEmpty()) {
                    user.baseUrl = cachedUser.baseUrl
                    Timber.tag("fetchUser").d("📥 Loaded cached baseUrl into singleton: ${user.baseUrl} for userId: $userId")
                }
            }

            // CRITICAL: The baseUrl parameter is a HINT for cache logic, NOT the actual baseUrl to use!
            // Each user has their own baseUrl (their own server/node).
            // - If baseUrl param is "" (empty): forces IP resolution (bypasses cache even if valid)
            // - If baseUrl param is null/default: use normal cache logic
            // - User's actual baseUrl comes from their singleton (cached from previous fetch OR loaded above from cache)
            //
            // DO NOT modify user.baseUrl here! Let resolveAndUpdateBaseUrl handle it.
            // This prevents clearing appUser.baseUrl if fetch fails.
            
            // performUserUpdate handles retry logic with NodePool integration
            // Pass baseUrl parameter as hint for IP resolution logic
            return performUserUpdate(userId, user, maxRetries, skipRetryAndBlacklist, "getUser", baseUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Propagate cancellation without logging as error
            Timber.tag("getUser").d("Fetch cancelled for userId: $userId")
            throw e
        } catch (e: Exception) {
            Timber.tag("getUser").e(e, "Exception in getUser: userId: $userId")
            return null
        } finally {
            userUpdateMutex.withLock {
                ongoingUserUpdates.remove(userId)
            }
        }
    }

    /**
     * Validates user data is complete and valid
     */
    private fun isValidUserData(user: User): Boolean = user.mid.isNotEmpty() && user.username != null

    /**
     * Processes user data response from server
     * @return true if successful, throws exception otherwise
     */
    private suspend fun processUserDataResponse(user: User, response: Map<*, *>, skipRetryAndBlacklist: Boolean): Boolean {
        if (!skipRetryAndBlacklist) {
            BlackList.recordSuccess(user.mid)
        }
        user.from(response as Map<String, Any>)
        
        if (isValidUserData(user)) {
            return true
        } else {
            Timber.tag("updateUserFromServer").w("❌ INVALID USER DATA: userId: ${user.mid}, mid: ${user.mid}, username: ${user.username}")
            throw Exception("Invalid user data received")
        }
    }


    /**
     * Resolves and updates user's baseUrl (for first attempt or retries)
     * 
     * NodePool Integration:
     * - First attempt: validate user IP against pool, get IP from pool if not in pool
     * - Retry attempts: always resolve fresh IP via getProviderIP
     * - After resolution: update NodePool with new IP
     */
    private suspend fun resolveAndUpdateBaseUrl(
        user: User, 
        attempt: Int, 
        maxRetries: Int, 
        forceFreshIP: Boolean, 
        userHasBaseUrl: Boolean,
        hasExpired: Boolean,
        originalBaseUrl: String?
    ) {
        // First attempt logic with NodePool integration
        if (attempt == 1 && !forceFreshIP && userHasBaseUrl && !user.baseUrl.isNullOrEmpty()) {
            // Try to get IP from user's node in pool (indexed by nodeId) - trust it
            val poolIP = NodePool.getIPFromNode(user)
            if (poolIP != null) {
                user.baseUrl = "http://$poolIP"
                user.clearHproseService()
                Timber.tag("updateUserFromServer").d("📡 ATTEMPT $attempt/$maxRetries - Using IP from NodePool: $poolIP for userId: ${user.mid} (trusted)")
                return
            }
            
            // User's node not in pool - use user's cached baseUrl
            if (!user.baseUrl.isNullOrEmpty()) {
                Timber.tag("updateUserFromServer").d("📡 ATTEMPT $attempt/$maxRetries - Using cached baseUrl: ${user.baseUrl} for userId: ${user.mid}")
                return
            }
        }
        
        // Resolve fresh IP (retry attempts or forced refresh)
        val reason = if (attempt > 1) {
            "retry attempt - resolving fresh IP"
        } else {
            when {
                originalBaseUrl.isNullOrEmpty() -> "forcing fresh IP resolution (baseUrl param empty)"
                hasExpired -> "forcing fresh IP resolution (user cache expired, baseUrl also considered expired)"
                else -> "no baseUrl"
            }
        }
        
        Timber.tag("updateUserFromServer").d("📡 ATTEMPT $attempt/$maxRetries - Resolving provider IP for userId: ${user.mid}, old baseUrl: ${user.baseUrl ?: ""}, reason: $reason")
        
        try {
            val providerIP = getProviderIP(user.mid)
            if (providerIP != null) {
                val newBaseUrl = if (providerIP.startsWith("http://")) {
                    providerIP
                } else {
                    "http://$providerIP"
                }
                
                user.baseUrl = newBaseUrl
                user.clearHproseService()
                
                // Update NodePool with newly resolved IP (replaces old IPs for this node)
                val accessNodeMid = user.hostIds?.getOrNull(1)
                if (accessNodeMid != null) {
                    // Extract IP:port from baseUrl for pool
                    val ipWithPort = newBaseUrl.removePrefix("http://").removePrefix("https://")
                    NodePool.updateNodeIP(accessNodeMid, ipWithPort)
                }
                
                if (user.hproseService == null) {
                    Timber.tag("updateUserFromServer").e("hproseService is null after setting baseUrl: ${user.baseUrl} for userId: ${user.mid}")
                }
            } else {
                // getProviderIP returned null (not exception) - user not found or servers genuinely down
                // This is NOT a retryable error - just log and continue with null baseUrl
                Timber.tag("updateUserFromServer").w("⚠️ getProviderIP returned null for userId: ${user.mid} - user not found or no IPs available")
                // Continue - the calling code will handle null baseUrl appropriately
            }
        } catch (e: Exception) {
            // getProviderIP threw exception - network error, should trigger retry
            Timber.tag("updateUserFromServer").w(e, "🔴 Network error calling getProviderIP for userId: ${user.mid}, attempt: $attempt/$maxRetries")
            throw e  // Re-throw to trigger retry logic
        }
    }

    /**
     * Update user data from server using "get_user" entry with retry logic
     * Matches iOS implementation: forceFreshIP is determined by user's baseUrl, not the parameter
     * @param user User instance to update
     * @param maxRetries Maximum retry attempts
     * @param skipRetryAndBlacklist Skip retry and blacklist logic
     * @param baseUrlHint Hint from fetchUser parameter (for future use, not currently used)
     * @return true if user data was successfully fetched and updated, false otherwise
     */
    private suspend fun updateUserFromServerWithRetry(
        user: User, 
        maxRetries: Int = 2, 
        skipRetryAndBlacklist: Boolean = false,
        baseUrlHint: String? = null
    ): Boolean {
        val originalBaseUrl = user.baseUrl
        val hasExpired = user.hasExpired
        val userHasBaseUrl = !user.baseUrl.isNullOrEmpty()
        // MATCH iOS: Only force fresh IP if we don't have a baseUrl at all
        // Don't force fresh IP just because user data expired - that's why we're fetching it!
        val forceFreshIP = originalBaseUrl.isNullOrEmpty()
        
        var lastError: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                // Resolve and update baseUrl
                resolveAndUpdateBaseUrl(user, attempt, maxRetries, forceFreshIP, userHasBaseUrl, hasExpired, originalBaseUrl)
                
                // Prepare server request
                val entry = "get_user"
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "version" to "v3",
                    "userid" to user.mid,
                    "v4only" to v4Only.toString()
                )
                
                if (user.hproseService == null) {
                    Timber.tag("updateUserFromServer").e("Cannot call get_user: hproseService is null for userId: ${user.mid}, baseUrl: ${user.baseUrl}")
                    throw Exception("hproseService is null - cannot fetch user data")
                }
                
                // Make server call
                val rawResponse = try {
                    user.hproseService?.runMApp<Any>(entry, params)
                } catch (e: Exception) {
                    Timber.tag("updateUserFromServer").e(e, "Exception calling runMApp for get_user, userId: ${user.mid}")
                    throw e
                }
                
                Timber.tag("updateUserFromServer").d("get_user rawResponse received: ${rawResponse?.javaClass?.simpleName}, isNull: ${rawResponse == null}")

                // v3 API returns JSON response with success field: {success: true, data: userObject} or {success: false, message: "..."}
                val userData = unwrapV2Response<Map<String, Any>>(rawResponse)
                val success = if (userData != null) {
                    processUserDataResponse(user, userData, skipRetryAndBlacklist)
                } else {
                    // unwrapV2Response returned null (either error response or null response)
                    // MATCH iOS: Clear baseUrl and let retry loop handle it
                    // This ensures next attempt will resolve fresh IP
                    Timber.tag("updateUserFromServer").w("❌ NULL RESPONSE (user not found): userId: ${user.mid}, attempt: $attempt/$maxRetries")

                    // Remove unhealthy node from pool (null response indicates node issue)
                    val accessNodeMid = user.hostIds?.getOrNull(1)
                    if (accessNodeMid != null) {
                        Timber.tag("updateUserFromServer").d("Removing node $accessNodeMid from pool after null response")
                        NodePool.removeNode(accessNodeMid)
                    }

                    user.baseUrl = null

                    // If this was the last attempt, fail
                    if (attempt >= maxRetries) {
                        Timber.tag("updateUserFromServer").e("❌ NULL RESPONSE on final attempt for userId: ${user.mid}")
                        if (!skipRetryAndBlacklist) {
                            BlackList.recordFailure(user.mid)
                        }
                    } else {
                        Timber.tag("updateUserFromServer").d("Will retry with fresh providerIP on next attempt")
                    }
                    false
                }
                
                // On success, update NodePool with discovered IPs
                if (success) {
                    NodePool.updateFromUser(user)
                }
                
                return success
            } catch (e: Exception) {
                // Handle cancellation specially - don't log as failure, don't retry
                if (e is kotlinx.coroutines.CancellationException) {
                    Timber.tag("updateUserFromServer").d("🔄 Fetch cancelled for userId: ${user.mid}, attempt: $attempt/$maxRetries")
                    throw e  // Propagate cancellation immediately
                }
                
                lastError = e
                Timber.tag("updateUserFromServer").e("❌ USER UPDATE FAILED: userId: ${user.mid}, attempt: $attempt/$maxRetries, error: ${e.message}")

                // Invalidate IP cache so retry's getProviderIP() health check won't
                // return stale "healthy" for the failed IP
                invalidateIPCache(user.baseUrl)

                // Remove unhealthy node from pool only for genuine connection failures.
                // Timeouts and cancellations can be caused by backgrounding/task teardown,
                // not necessarily an unhealthy node.
                val isTransient = e is java.net.SocketTimeoutException ||
                        e.message?.contains("timeout", ignoreCase = true) == true
                if (!isTransient) {
                    val accessNodeMid = user.hostIds?.getOrNull(1)
                    if (accessNodeMid != null) {
                        Timber.tag("updateUserFromServer").d("Removing unhealthy node $accessNodeMid from pool after failure")
                        NodePool.removeNode(accessNodeMid)
                    }
                }

                if (skipRetryAndBlacklist) {
                    return false
                }

                if (attempt < maxRetries) {
                    val delayMs = attempt * 1000L
                    delay(delayMs)
                }
            }
        }
        
        Timber.tag("updateUserFromServer").e("❌ ALL RETRIES FAILED: userId: ${user.mid}, maxRetries: $maxRetries")

        // MATCH iOS: Remove node from pool and clear stale baseUrl
        // so the NEXT fetchUser call forces fresh IP resolution via getProviderIP()
        val accessNodeMid = user.hostIds?.getOrNull(1)
        if (accessNodeMid != null && !user.baseUrl.isNullOrEmpty()) {
            Timber.tag("updateUserFromServer").d("Removing failed node $accessNodeMid from pool after all retries failed")
            NodePool.removeNode(accessNodeMid)
        }
        user.baseUrl = null

        if (!skipRetryAndBlacklist && lastError != null) {
            BlackList.recordFailure(user.mid)
        }
        return false
    }

    /**
     * Cache for tested IP addresses with timestamps
     * Key: IP address, Value: Pair<isHealthy, timestamp>
     */
    private val ipHealthCache = mutableMapOf<String, Pair<Boolean, Long>>()
    private val ipCacheMutex = Mutex()
    private const val IP_CACHE_DURATION_MS = 30_000 // 30 seconds in milliseconds

    /**
     * Check if an IP is in cache and still valid
     * @return true if healthy, false if unhealthy or not in cache, null if cache expired
     */
    private suspend fun getCachedHealth(ipAddress: String): Boolean? = ipCacheMutex.withLock {
        val cached = ipHealthCache[ipAddress] ?: return@withLock null
        val (isHealthy, timestamp) = cached
        val now = System.currentTimeMillis()
        
        if (now - timestamp > IP_CACHE_DURATION_MS) {
            // Cache expired, remove it
            ipHealthCache.remove(ipAddress)
            return@withLock null
        }
        
        return@withLock isHealthy
    }

    /**
     * Store IP health check result in cache
     */
    private suspend fun cacheIPHealth(ipAddress: String, isHealthy: Boolean) = ipCacheMutex.withLock {
        ipHealthCache[ipAddress] = Pair(isHealthy, System.currentTimeMillis())
    }

    /**
     * Invalidate IP cache entry so retry's getProviderIP() health check
     * won't return stale "healthy" for a failed IP
     */
    private suspend fun invalidateIPCache(baseUrl: String?) {
        if (baseUrl.isNullOrEmpty()) return
        val cacheKey = baseUrl
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
        ipCacheMutex.withLock {
            ipHealthCache.remove(cacheKey)
        }
    }

    /**
     * Try each IP address in the list in pairs until a healthy one is found
     * Returns the first healthy IP immediately without waiting for others
     * Checks IPs in pairs (2 at a time) to avoid overwhelming the network
     * Uses a 30-minute cache to avoid repeated checks
     * @param ipAddresses List of IP addresses to test
     * @param logPrefix Prefix for logging messages
     * @return First healthy IP address, or null if none found
     */
    private suspend fun tryIpAddresses(ipAddresses: List<String>, logPrefix: String = ""): String? = coroutineScope {
        if (ipAddresses.isEmpty()) {
            return@coroutineScope null
        }

        if (logPrefix.isNotEmpty()) {
            Timber.tag("getProviderIP").d("$logPrefix - Testing ${ipAddresses.size} IPs in pairs with cache")
        }

        // First check cache for any healthy IPs
        for (ipAddress in ipAddresses) {
            val cachedHealth = getCachedHealth(ipAddress)
            if (cachedHealth == true) {
                if (logPrefix.isNotEmpty()) {
                    Timber.tag("getProviderIP").d("$logPrefix - IP $ipAddress found healthy in cache")
                }
                return@coroutineScope ipAddress
            }
        }

        // Process IPs in pairs (2 at a time)
        val pairSize = 2
        var firstHealthy: String? = null
        val activeJobs = mutableListOf<kotlinx.coroutines.Deferred<String?>>()

        for (i in ipAddresses.indices step pairSize) {
            // Check if we already found a healthy IP
            if (firstHealthy != null) {
                break
            }

            // Get the current pair of IPs
            val endIndex = minOf(i + pairSize, ipAddresses.size)
            val currentPair = ipAddresses.subList(i, endIndex)

            // Launch jobs for this pair
            activeJobs.clear()
            for ((pairIndex, ipAddress) in currentPair.withIndex()) {
                val globalIndex = i + pairIndex
                val job = async(Dispatchers.IO) {
                    try {
                        // Check cache first (double-check in case another coroutine updated it)
                        val cachedHealth = getCachedHealth(ipAddress)
                        if (cachedHealth != null) {
                            return@async if (cachedHealth) ipAddress else null
                        }

                        // Construct URL with proper IPv6 bracket wrapping
                        val testURL = if (ipAddress.startsWith("http")) {
                            ipAddress.trim()
                        } else {
                            val trimmedIP = ipAddress.trim()
                            // Check if this is an IPv6 address that needs bracket wrapping
                            // IPv6 addresses have MULTIPLE colons, IPv4 with port has only ONE colon
                            val colonCount = trimmedIP.count { it == ':' }
                            if (colonCount > 1 && !trimmedIP.startsWith("[")) {
                                // This is IPv6 - wrap in brackets
                                val lastColonIndex = trimmedIP.lastIndexOf(":")
                                val potentialPort = trimmedIP.substring(lastColonIndex + 1)
                                
                                // Check if the part after last colon looks like a port (8000-8999)
                                val portNumber = potentialPort.toIntOrNull()
                                if (portNumber != null && portNumber in 8000..8999) {
                                    // Has port - wrap IP part in brackets
                                    val ipPart = trimmedIP.take(lastColonIndex)
                                    "http://[$ipPart]:$portNumber"
                                } else {
                                    // No port or doesn't look like port - wrap entire address
                                    "http://[$trimmedIP]"
                                }
                            } else {
                                // IPv4 (with or without port) or already formatted
                                "http://$trimmedIP"
                            }
                        }
                        
                        // Perform health check on this IP using HTTP HEAD (uses cache)
                        val isHealthy = isServerHealthy(testURL)
                        
                        // Cache the result
                        cacheIPHealth(ipAddress, isHealthy)
                        
                        if (isHealthy) {
                            if (logPrefix.isNotEmpty()) {
                                Timber.tag("getProviderIP").d("$logPrefix - IP ${globalIndex + 1}/${ipAddresses.size} ($ipAddress) is healthy")
                            }
                            ipAddress
                        } else {
                            null
                        }
                    } catch (_: Exception) {
                        // Health check failed for this IP, cache as unhealthy
                        cacheIPHealth(ipAddress, false)
                        null
                    }
                }
                activeJobs.add(job)
            }

            // Wait for the first healthy IP in this pair
            for ((pairIndex, job) in activeJobs.withIndex()) {
                try {
                    val result = job.await()
                    if (result != null) {
                        firstHealthy = result
                        // Cancel all remaining jobs in this pair
                        for (j in (pairIndex + 1) until activeJobs.size) {
                            activeJobs[j].cancel()
                        }
                        break
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Job was cancelled, continue to next
                    continue
                } catch (_: Exception) {
                    // Job failed, continue to next
                    continue
                }
            }
        }

        if (logPrefix.isNotEmpty() && firstHealthy == null) {
            Timber.tag("getProviderIP").d("$logPrefix - No healthy IPs found among ${ipAddresses.size} addresses")
        }

        firstHealthy
    }

    /**
     * Check if user should be resynced this session and mark as resynced if so.
     * Thread-safe check using synchronized block.
     * 
     * @param userId The user ID to check
     * @return true if user should be resynced, false if already resynced this session
     */
    fun shouldResyncUser(userId: MimeiId): Boolean {
        return synchronized(resyncLock) {
            if (resyncedUsersThisSession.contains(userId)) {
                false
            } else {
                resyncedUsersThisSession.add(userId)
                true
            }
        }
    }

    /**
     * Resync user data on the server - matches iOS ProfileView behavior.
     * This triggers a backend operation to refresh the user's data on the server side.
     * Should be called once per app session when a user profile is opened.
     * 
     * @param userId The user ID to resync
     * @return The updated User object from server, or null if failed
     */
    suspend fun resyncUser(userId: MimeiId): User? {
        if (!isOnline.value) {
            Timber.tag("resyncUser").d("Offline: skipping")
            return null
        }
        return try {
            Timber.tag("resyncUser").d("Starting resync for user: $userId")
            
            // Get the user instance to access their baseUrl
            val user = getUserInstance(userId)
            
            // If user doesn't have a baseUrl, fetch it first
            if (user.baseUrl.isNullOrBlank()) {
                Timber.tag("resyncUser").d("User $userId has no baseUrl, fetching user first")
                fetchUser(userId, baseUrl = "")
            }
            
            val entry = "resync_user"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "version" to "v2",
                "userid" to userId
            )
            
            // Use the target user's hproseService (with their baseUrl)
            val service = user.hproseService ?: run {
                Timber.tag("resyncUser").w("User $userId has no hproseService, using appUser's client")
                appUser.hproseService
            }
            
            if (service == null) {
                Timber.tag("resyncUser").e("No hproseService available for resync")
                return null
            }
            
            Timber.tag("resyncUser").d("Calling resync_user API for user: $userId with baseUrl: ${user.baseUrl}")
            
            // Make the API call
            val rawResponse = withContext(Dispatchers.IO) {
                service.runMApp<Any>(entry, params)
            }
            
            Timber.tag("resyncUser").d("Got response for user $userId: ${rawResponse?.javaClass?.simpleName}")
            
            // Parse response (v2 API returns unwrapped user data)
            val userData = when (rawResponse) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    rawResponse as? Map<String, Any>
                }
                else -> {
                    Timber.tag("resyncUser").w("Unexpected response type: ${rawResponse?.javaClass}")
                    null
                }
            }
            
            // Validate response: must have userData and username (required field)
            if (userData == null || userData.isEmpty()) {
                Timber.tag("resyncUser").e("Null or empty user data, ignoring result")
                return null
            }
            
            val username = userData["username"] as? String
            if (username == null) {
                Timber.tag("resyncUser").e("Invalid user data (no username), ignoring result")
                return null
            }
            
            // Valid response - update user fields
            (userData["name"] as? String)?.let { user.name = it }
            user.username = username  // Username is validated above
            (userData["email"] as? String)?.let { user.email = it }
            (userData["profile"] as? String)?.let { user.profile = it }
            (userData["avatar"] as? String)?.let { user.avatar = it }
            
            // Update counts only if provided (non-nullable Int properties)
            (userData["tweetCount"] as? Number)?.let { user.tweetCount = it.toInt() }
            (userData["followingCount"] as? Number)?.let { user.followingCount = it.toInt() }
            (userData["followersCount"] as? Number)?.let { user.followersCount = it.toInt() }
            (userData["bookmarksCount"] as? Number)?.let { user.bookmarksCount = it.toInt() }
            (userData["favoritesCount"] as? Number)?.let { user.favoritesCount = it.toInt() }
            (userData["commentsCount"] as? Number)?.let { user.commentsCount = it.toInt() }
            
            // Update cloudDrivePort if provided
            (userData["cloudDrivePort"] as? Number)?.let { port ->
                user.cloudDrivePort = port.toInt()
            }
            
            // Save updated user to cache
            TweetCacheManager.saveUser(user)
            Timber.tag("resyncUser").d("✅ Successfully resynced user $userId")
            
            user
        } catch (e: Exception) {
            Timber.tag("resyncUser").e(e, "Failed to resync user $userId")
            null
        }
    }

    /**
     * Get provider IP for a user using "get_provider_ips" entry
     * 
     * Fallback Strategy:
     * 1. Try lookup using appUser's client
     * 2. If user IS appUser -> use entry IP to lookup
     * 3. If user is NOT appUser and appUser UNHEALTHY -> refresh appUser via entry IP, retry lookup
     * 4. If user is NOT appUser and appUser HEALTHY -> return null (user's servers are genuinely down)
     * 
     * Key Insight: When appUser is healthy, don't try entry IP fallback for other users.
     * This is because appUser successfully responded with the user's IP list, but all those IPs
     * failed health checks (they are genuinely unhealthy). Entry IP would return the same
     * unhealthy list - no point in redundant lookup.
     */
    suspend fun getProviderIP(mid: MimeiId): String? {
        if (!isOnline.value) {
            Timber.tag("getProviderIP").d("Offline: skipping")
            return null
        }
        // Safety check: never try to get provider IP for GUEST_ID
        if (mid == TW_CONST.GUEST_ID) {
            Timber.tag("getProviderIP").e("❌ Refusing to get provider IP for GUEST_ID")
            return findEntryIP()
        }

        // MATCH iOS: get_provider_ips is a discovery operation — always use the entry node,
        // not appUser.hproseService which may point to a stale provider IP.
        return try {
            val entryIP = findEntryIP()
            val baseUrl = "http://$entryIP"
            val entryClient = HproseClientPool.getRegularClient(baseUrl)
            _getProviderIP(mid, entryClient)
        } catch (e: Exception) {
            Timber.tag("getProviderIP").w(e, "Network error in getProviderIP for $mid")
            throw e
        }
    }

    private suspend fun _getProviderIP(mid: MimeiId, hproseService: HproseService? = appUser.hproseService): String? {
        val entry = "get_provider_ips"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "mid" to mid,
            "v4only" to v4Only.toString()
        )
        Timber.tag("_getProviderIP").d("🔍 v4Only global = $v4Only, sending v4only = ${v4Only.toString()}")

        // Let exceptions propagate - caller will decide whether to retry
        val rawResponse = hproseService?.runMApp<Any>(entry, params)
        val ipArray = unwrapV2Response<List<String>>(rawResponse)
        Timber.tag("_getProviderIP").d("🔍 Received IPs from server: $ipArray")

        // If ipArray is valid, try each IP
        if (ipArray != null && ipArray.isNotEmpty()) {
            return tryIpAddresses(ipArray)
        }
        
        // Return null means: server responded successfully but no IPs found (user not found or no IPs)
        return null
    }

    /**
     * Dedicated HTTP client for health checks with shorter timeouts
     * Uses OkHttp engine for better Android performance and reliability
     */
    private val healthCheckHttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(6, TimeUnit.SECONDS)
                readTimeout(5, TimeUnit.SECONDS)
                writeTimeout(5, TimeUnit.SECONDS)
                // HTTP/2 and HTTP/1.1 support for better performance
                protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            }
        }
    }

    /**
     * Check if a server is healthy by making an HTTP HEAD request
     * This is a lightweight check that only verifies the server is alive
     * Uses the IP health cache to avoid repeated checks
     * @param url The server URL to check
     * @return true if server responds to HEAD request, false otherwise
     */
    private suspend fun isServerHealthy(url: String): Boolean {
        // Check cache first to avoid repeated health checks
        val cachedHealth = getCachedHealth(url)
        if (cachedHealth != null) {
            return cachedHealth
        }
        
        // Not in cache, perform actual health check
        return try {
            val response = healthCheckHttpClient.head(url)
            // Consider 2xx and 3xx responses as healthy
            val isHealthy = response.status.value in 200..399
            // Cache the result
            cacheIPHealth(url, isHealthy)
            isHealthy
        } catch (e: Exception) {
            // Only log at debug level for expected network failures (timeout, unreachable)
            val message = e.message ?: e.toString()
            if (message.contains("timeout", ignoreCase = true) || 
                message.contains("unreachable", ignoreCase = true)) {
                Timber.tag("isServerHealthy").d("Health check failed for $url: ${e.message}")
            } else {
                Timber.tag("isServerHealthy").w("Health check (HEAD) exception for $url: ${e.message}")
            }
            // Cache the failure
            cacheIPHealth(url, false)
            false
        }
    }

    /**
     * Return the current tweet list that is pinned to top.
     * */
    suspend fun togglePinnedTweet(tweetId: MimeiId): Boolean? {
        if (!isOnline.value) {
            Timber.tag("togglePinnedTweet").d("Offline: skipping")
            return null
        }
        val entry = "toggle_pinned_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "appuserid" to appUser.mid,
            "tweetid" to tweetId
        )
        return try {
            // For v2 API: server returns {success: true, data: {isPinned: bool}}
            // After unwrapping, we need to extract isPinned from the data dictionary
            when (val rawResponse = appUser.hproseService?.runMApp<Any>(entry, params)) {
                is Boolean -> {
                    // Legacy format: direct boolean response
                    rawResponse
                }
                is Map<*, *> -> {
                    val responseMap = rawResponse as? Map<String, Any>
                    // Check for v2 error response
                    if (responseMap?.get("success") == false) {
                        val error = responseMap["message"] as? String ?: "Unknown error"
                        Timber.tag("togglePinnedTweet").e("Server returned error: $error")
                        null
                    } else {
                        // Try to extract from v2 format: {success: true, data: {isPinned: bool}}
                        when (val data = responseMap?.get("data")) {
                            is Map<*, *> -> {
                                val dataMap = data as? Map<String, Any>
                                dataMap?.get("isPinned") as? Boolean
                            }
                            is Boolean -> {
                                // Direct boolean in data field
                                data
                            }
                            else -> {
                                // Try direct isPinned field in response
                                responseMap?.get("isPinned") as? Boolean
                            }
                        }
                    }
                }
                null -> {
                    Timber.tag("togglePinnedTweet").w("Server returned null response")
                    null
                }
                else -> {
                    Timber.tag("togglePinnedTweet").e("Unexpected response type: ${rawResponse.javaClass.simpleName}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.tag("togglePinnedTweet").e("Error toggling pinned tweet: $tweetId $e")
            null
        }
    }

    /**
     * Return a list of {tweet: Tweet, timestamp: Long} for each pinned Tweet. The timestamp is when
     * the tweet is pinned.
     * */
    suspend fun getPinnedTweetsWithTimestamp(user: User): List<Map<String, Any>>? {
        if (!isOnline.value) {
            Timber.tag("getPinnedTweetsWithTimestamp").d("Offline: skipping")
            return null
        }
        val entry = "get_pinned_tweets"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "userid" to user.mid,
            "appuserid" to appUser.mid
        )
        return try {
            val rawResponse = user.hproseService?.runMApp<Any>(entry, params)
            unwrapV2Response<List<Map<String, Any>>>(rawResponse)
        } catch (e: Exception) {
            Timber.tag("getPinnedList").e("Error getting pinned tweets for user: ${user.mid}")
            Timber.tag("getPinnedList").e("Exception: $e")
            null
        }
    }

    /**
     * Upload media file to node and return its IPFS cid with its media type.
     * Delegates to MediaUploadService for all upload operations.
     * */
    @OptIn(UnstableApi::class)
    suspend fun uploadToIPFS(
        uri: Uri,
        referenceId: MimeiId? = null
    ): MimeiFileType? {
        if (!isOnline.value) {
            Timber.tag("uploadToIPFS").d("Offline: skipping")
            return null
        }
        return mediaUploadService.uploadToIPFS(uri, referenceId)
    }

    /**
     * Poll video conversion status until completion with retry logic for connection issues
     */
    private suspend fun pollVideoConversionStatus(
        context: Context,
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        jobId: String,
        baseUrl: String
    ): MimeiFileType {
        val statusURL = "$baseUrl/convert-video/status/$jobId"
        var lastProgress = 0
        var lastMessage = "Starting video processing..."
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 3 // Maximum 3 retry attempts for polling failures
        val maxPollingTime = 2 * 60 * 60 * 1000L // 2 hours max polling time for very long videos
        val startTime = System.currentTimeMillis()
        
        Timber.tag("pollVideoConversionStatus").d("Starting to poll status for job: $jobId")

        while (true) {
            // Check if we've been polling too long
            if (System.currentTimeMillis() - startTime > maxPollingTime) {
                Timber.tag("pollVideoConversionStatus").e("Video processing timeout after ${maxPollingTime / 1000 / 60} minutes")
                throw Exception(applicationContext.getString(R.string.error_video_processing_timeout, maxPollingTime / 1000 / 60))
            }

            try {
                val statusResponse = httpClient.get(statusURL)
                
                if (statusResponse.status == HttpStatusCode.NotFound) {
                    // Job ID not found - cancel immediately without retry
                    Timber.tag("pollVideoConversionStatus").e("Job ID not found: $jobId")
                    throw Exception(applicationContext.getString(R.string.error_job_id_not_found, jobId))
                }
                
                if (statusResponse.status != HttpStatusCode.OK) {
                    Timber.tag("pollVideoConversionStatus").e("Status check failed with HTTP status: ${statusResponse.status}")
                    throw Exception(applicationContext.getString(R.string.error_status_check_failed, statusResponse.status.toString()))
                }

                val statusResponseText = statusResponse.bodyAsText()
                val statusData = Gson().fromJson(statusResponseText, Map::class.java)
                
                val success = statusData?.get("success") as? Boolean
                if (success != true) {
                    val errorMessage = statusData?.get("message") as? String ?: "Status check failed"
                    // Check if the error message indicates job not found
                    if (errorMessage.contains("not found", ignoreCase = true) || 
                        errorMessage.contains("job not found", ignoreCase = true)) {
                        Timber.tag("pollVideoConversionStatus").e("Job ID not found in response: $jobId")
                        throw Exception(applicationContext.getString(R.string.error_job_id_not_found, jobId))
                    }
                    throw Exception(errorMessage)
                }

                val status = statusData["status"] as? String
                val progress = (statusData["progress"] as? Number)?.toInt() ?: 0
                val message = statusData["message"] as? String ?: "Processing..."

                // Reset failure counter on successful request
                consecutiveFailures = 0

                // Log progress updates
                if (progress != lastProgress || message != lastMessage) {
                    Timber.tag("pollVideoConversionStatus").d("Progress: $progress% - $message")
                    lastProgress = progress
                    lastMessage = message
                }

                when (status) {
                    "completed" -> {
                        val cid = statusData["cid"] as? String ?: run {
                            Timber.tag("pollVideoConversionStatus").e("No CID in response")
                            throw Exception(applicationContext.getString(R.string.error_no_cid_in_response))
                        }
                        
                        @OptIn(UnstableApi::class)
                        val aspectRatio = VideoManager.getVideoAspectRatio(context, uri)
                        
                        // Calculate file size from the original URI
                        val fileSize = getFileSize(uri) ?: 0L
                        Timber.tag("pollVideoConversionStatus").d("Video file size calculated: $fileSize bytes for URI: $uri")

                        Timber.tag("pollVideoConversionStatus").d("Video conversion completed successfully: $cid")
                        return MimeiFileType(
                            cid,
                            MediaType.HLS_VIDEO,
                            fileSize,
                            fileName,
                            fileTimestamp,
                            aspectRatio
                        )
                    }
                    "failed" -> {
                        val serverMessage = statusData["message"] as? String
                        Timber.tag("pollVideoConversionStatus").e("Video conversion failed: ${serverMessage ?: "Unknown error"}")
                        val errorMessage = serverMessage ?: applicationContext.getString(R.string.error_video_conversion_failed)
                        throw Exception(errorMessage)
                    }
                    "uploading", "processing" -> {
                        // Continue polling
                        delay(3000) // Poll every 3 seconds
                    }
                    else -> {
                        Timber.tag("pollVideoConversionStatus").w("Unknown status: $status")
                        delay(3000)
                    }
                }
            } catch (e: Exception) {
                consecutiveFailures++
                val englishError = e.message ?: "Unknown error"
                Timber.tag("pollVideoConversionStatus").e("Error polling status (attempt $consecutiveFailures/$maxConsecutiveFailures): $englishError")
                
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    Timber.tag("pollVideoConversionStatus").e("Failed to poll status after $maxConsecutiveFailures consecutive failures")
                    throw Exception(applicationContext.getString(R.string.error_poll_status_failures, maxConsecutiveFailures, englishError))
                }
                
                // Exponential backoff for retries, but cap at reasonable maximum
                val retryDelay = minOf(60000L, 2000L * (1 shl minOf(consecutiveFailures - 1, 5))) // Max 60 seconds
                Timber.tag("pollVideoConversionStatus").d("Retrying in ${retryDelay}ms...")
                delay(retryDelay)
            }
        }
    }

    /**
     * Calculate file size from URI - delegates to MediaUploadService
     */
    suspend fun getFileSize(uri: Uri): Long? =
        mediaUploadService.getFileSize(uri)

    /**
     * HTTP client specifically for app initialization with short timeouts
     * Uses OkHttp engine for better Android performance and reliability
     */
    private val initHttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(15, TimeUnit.SECONDS)
                writeTimeout(15, TimeUnit.SECONDS)
                protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            }
        }
    }

    /**
     * Ktor HTTP client for general API calls
     * Uses OkHttp engine for better Android performance, connection pooling, and reliability
     * OkHttp automatically handles connection pooling, keep-alive, and HTTP/2
     */
    val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                writeTimeout(60, TimeUnit.SECONDS)
                // HTTP/2 for multiplexing and better performance
                protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                // OkHttp's default connection pool (5 connections, 5 min keep-alive)
            }
        }
    }
    
    /**
     * Dedicated HTTP client for large file uploads
     * Uses OkHttp engine with extended timeouts for large video/image uploads
     */
    val uploadHttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(60, TimeUnit.SECONDS)
                readTimeout(50, TimeUnit.MINUTES)
                writeTimeout(50, TimeUnit.MINUTES)
                protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            }
        }
    }
    
    /**
     * Save incomplete upload to SharedPreferences
     */
    fun saveIncompleteUpload(context: Context, upload: IncompleteUpload) {
        val prefs = context.getSharedPreferences("incomplete_uploads", Context.MODE_PRIVATE)
        val uploadJson = Gson().toJson(upload)
        prefs.edit { putString(upload.workId, uploadJson) }
        Timber.tag("HproseInstance").d("Saved incomplete upload: ${upload.workId}")
    }
    
    /**
     * Remove incomplete upload from SharedPreferences and release URI permissions
     */
    fun removeIncompleteUpload(context: Context, workId: String) {
        // First get the upload data to release URI permissions
        val prefs = context.getSharedPreferences("incomplete_uploads", Context.MODE_PRIVATE)
        val uploadJson = prefs.getString(workId, null)
        if (uploadJson != null) {
            try {
                val upload = Gson().fromJson(uploadJson, IncompleteUpload::class.java)
                // Release persistent URI permissions
                upload.attachmentUris.forEach { uriString ->
                    try {
                        val uri = uriString.toUri()
                        context.contentResolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                        Timber.tag("HproseInstance").w("Failed to release permission for URI: $uriString")
                    }
                }
            } catch (_: Exception) {
                Timber.tag("HproseInstance").w("Failed to parse upload data for permission cleanup: $workId")
            }
        }

        prefs.edit { remove(workId) }
        Timber.tag("HproseInstance").d("Removed incomplete upload: $workId")
    }

    /**
     * Get all incomplete uploads from SharedPreferences
     */
    fun getIncompleteUploads(context: Context): List<IncompleteUpload> {
        val prefs = context.getSharedPreferences("incomplete_uploads", Context.MODE_PRIVATE)
        val allEntries = prefs.all
        val uploads = mutableListOf<IncompleteUpload>()
        
        for ((key, value) in allEntries) {
            try {
                val upload = Gson().fromJson(value as String, IncompleteUpload::class.java)
                // Only include uploads from the last 24 hours
                if (System.currentTimeMillis() - upload.timestamp < 24 * 60 * 60 * 1000) {
                    uploads.add(upload)
                } else {
                    // Remove old uploads
                    prefs.edit { remove(key) }
                }
            } catch (e: Exception) {
                Timber.tag("HproseInstance").e("Error parsing incomplete upload: $e")
                // Remove corrupted entries
                prefs.edit {remove(key) }
            }
        }
        
        return uploads
    }
    
    /**
     * Resume incomplete uploads when app comes to foreground
     */
    fun resumeIncompleteUploads(context: Context) {
        if (!isOnline.value) return
        val incompleteUploads = getIncompleteUploads(context)
        if (incompleteUploads.isEmpty()) {
            Timber.tag("HproseInstance").d("No incomplete uploads to resume")
            return
        }
        
        Timber.tag("HproseInstance").d("Found ${incompleteUploads.size} incomplete uploads to resume")
        
        for (upload in incompleteUploads) {
            try {
                // Check original WorkManager state for this upload
                try {
                    val uuid = UUID.fromString(upload.workId)
                    val info = WorkManager.getInstance(context).getWorkInfoById(uuid).get()
                    if (info != null) {
                        when (info.state) {
                            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                                // Check if work has been stuck for more than 10 minutes
                                val staleThresholdMs = 10 * 60 * 1000L // 10 minutes
                                val ageMs = System.currentTimeMillis() - upload.timestamp
                                
                                if (ageMs > staleThresholdMs) {
                                    Timber.tag("HproseInstance").w("Work ${upload.workId} stuck in ${info.state} for ${ageMs / 1000}s, cancelling and resuming")
                                    // Cancel the stuck work
                                    WorkManager.getInstance(context).cancelWorkById(uuid)
                                    // Let it proceed to resume logic below
                                } else {
                                    Timber.tag("HproseInstance").d("Skipping resume: work ${upload.workId} is ${info.state} (age: ${ageMs / 1000}s)")
                                    continue
                                }
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                Timber.tag("HproseInstance").d("Cleaning up: work ${upload.workId} already SUCCEEDED")
                                removeIncompleteUpload(context, upload.workId)
                                continue
                            }
                            else -> { /* proceed */ }
                        }
                    }
                } catch (_: Exception) { /* ignore invalid UUID or fetch errors */ }
                // Check if this is a video conversion job that needs resumption
                if (upload.videoConversionJobId != null && upload.videoConversionBaseUrl != null) {
                    Timber.tag("HproseInstance").d("Resuming video conversion for job: ${upload.videoConversionJobId}")
                    
                    // Create a coroutine to check job status and resume if needed
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val videoUri = upload.videoConversionUri?.toUri()
                            val fileName = videoUri?.lastPathSegment
                            val fileTimestamp = System.currentTimeMillis()
                            
                            // Check the backend with jobID to see how video conversion is going
                            val result = pollVideoConversionStatus(
                                context = context,
                                uri = videoUri ?: Uri.EMPTY,
                                fileName = fileName,
                                fileTimestamp = fileTimestamp,
                                jobId = upload.videoConversionJobId,
                                baseUrl = upload.videoConversionBaseUrl
                            )

                            // Video conversion is finished, upload the tweet and get it done
                            val tweet = Tweet(
                                mid = System.currentTimeMillis().toString(),
                                authorId = appUser.mid,
                                content = upload.tweetContent,
                                attachments = listOf(result),
                                isPrivate = upload.isPrivate
                            )

                            uploadTweet(tweet)?.let { uploadedTweet ->
                                Timber.tag("HproseInstance").d("Successfully completed resumed video upload: ${uploadedTweet.mid}")
                                removeIncompleteUpload(context, upload.workId)
                            } ?: run {
                                Timber.tag("HproseInstance").e("Failed to upload tweet after video conversion completion")
                            }
                        } catch (e: Exception) {
                            Timber.tag("HproseInstance").e("Error resuming video conversion: $e")
                            // Remove the problematic incomplete upload to prevent future retries
                            removeIncompleteUpload(context, upload.workId)
                        }
                    }
                    continue
                }
                
                // Handle regular file uploads (non-video uploads)
                // For non-video uploads, we still need to validate URIs since they might be expired
                val validUris = mutableListOf<String>()
                
                for (uriString in upload.attachmentUris) {
                    try {
                        val uri = uriString.toUri()
                        // Test if we can access the URI
                        context.contentResolver.openInputStream(uri)?.use {
                            validUris.add(uriString)
                        }
                    } catch (e: Exception) {
                        Timber.tag("HproseInstance").w("URI no longer accessible: $uriString - ${e.message}")
                    }
                }
                
                // If all URIs are invalid, skip this upload
                if (validUris.isEmpty() && upload.attachmentUris.isNotEmpty()) {
                    Timber.tag("HproseInstance").w("Skipping upload ${upload.workId} - all attachment URIs are no longer accessible")
                    removeIncompleteUpload(context, upload.workId)
                    continue
                }
                
                // Create new WorkManager request to resume the upload
                val data = workDataOf(
                    "tweetContent" to upload.tweetContent,
                    "attachmentUris" to validUris.toTypedArray(),
                    "isPrivate" to upload.isPrivate,
                    "isResume" to true
                )
                
                val uploadRequest = OneTimeWorkRequest.Builder(
                    UploadTweetWorker::class.java
                )
                    .setInputData(data)
                    .build()
                
                val workManager = WorkManager.getInstance(context)
                workManager.enqueue(uploadRequest)
                
                // CRITICAL: Remove the old workId since we just created a new WorkManager request with a new workId
                // The new worker will track its own success/failure with the new workId
                removeIncompleteUpload(context, upload.workId)
                
                Timber.tag("HproseInstance").d("Resumed upload - removed old workId: ${upload.workId}, created new work with ${validUris.size} valid URIs")
                
            } catch (e: Exception) {
                Timber.tag("HproseInstance").e("Error resuming upload ${upload.workId}: $e")
                // Remove the problematic incomplete upload to prevent future retries
                removeIncompleteUpload(context, upload.workId)
            }
        }
    }
}

