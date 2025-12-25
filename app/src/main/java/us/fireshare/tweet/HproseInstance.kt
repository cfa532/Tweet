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
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import us.fireshare.tweet.datamodel.BlackList
import us.fireshare.tweet.datamodel.CachedTweetDao
import us.fireshare.tweet.datamodel.ChatDatabase
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatMessageDeserializer
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.FeedResetReason
import us.fireshare.tweet.datamodel.HproseService
import us.fireshare.tweet.datamodel.MediaType
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
import java.util.regex.Pattern
import us.fireshare.tweet.datamodel.User.Companion.getInstance as getUserInstance

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {
    private var _appId: MimeiId = BuildConfig.APP_ID
    val appId: MimeiId get() = _appId
    
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
    private val _appUserState = MutableStateFlow<User>(getInstance(TW_CONST.GUEST_ID))
    
    /**
     * StateFlow for observing appUser changes.
     * Others can collect this flow to observe when the app user changes.
     */
    val appUserState: StateFlow<User> = _appUserState.asStateFlow()
    
    // Lazy initialization of MediaUploadService
    private val mediaUploadService: MediaUploadService by lazy {
        MediaUploadService(applicationContext, httpClient, appUser, appId)
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
            _appUserState.value = value
            // If baseUrl changed from null to a valid value, trigger tweet feed refresh
            if (oldBaseUrl == null && value.baseUrl != null && !value.isGuest()) {
                Timber.tag("appUser").d("BaseUrl became available for logged-in user, triggering tweet feed refresh")
                TweetApplication.applicationScope.launch {
                    try {
                        // Post notification to trigger feed refresh
                        TweetNotificationCenter.post(
                            TweetEvent.FeedResetRequested(FeedResetReason.BASEURL_AVAILABLE)
                        )
                        Timber.tag("appUser").d("Posted FeedResetRequested notification after baseUrl became available: ${value.baseUrl}")
                    } catch (e: Exception) {
                        Timber.tag("appUser").e(e, "Error triggering tweet refresh after baseUrl became available")
                    }
                }
            }
        }

    private lateinit var chatDatabase: ChatDatabase
    lateinit var dao: CachedTweetDao
    
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
        try {
            // Store Application context to avoid memory leaks
            this.applicationContext = context.applicationContext as Application
            HproseClassManager.register(Tweet::class.java, "Tweet")
            HproseClassManager.register(User::class.java, "User")

            this.preferenceHelper = PreferenceHelper(context)
            chatDatabase = ChatDatabase.getInstance(context)
            val tweetCache = TweetCacheDatabase.getInstance(context)
            dao = tweetCache.tweetDao()

            // Initialize appUser with userId from preferences, or GUEST_ID if not available
            val storedUserId = preferenceHelper.getUserId()
            val initialUserId = if (storedUserId != TW_CONST.GUEST_ID) storedUserId else TW_CONST.GUEST_ID
            appUser = getInstance(initialUserId)
            appUser.followingList = if (initialUserId == TW_CONST.GUEST_ID) getAlphaIds() else emptyList()
            Timber.tag("HproseInstance").d("Initialized appUser with mid: ${appUser.mid}")
            
            // CRITICAL: initAppEntry() must complete first and set IP-based baseUrl
            // This is suspend, so init() will wait for it to complete
            initAppEntry()
            
            // Call the callback after successful initialization
            onInitialized?.invoke()
        } catch (e: Exception) {
            Timber.tag("HproseInstance").e(e, "Error during HproseInstance initialization")
            // Set up minimal fallback state to prevent app from being completely broken
            if (!::preferenceHelper.isInitialized) {
                this.preferenceHelper = PreferenceHelper(context)
            }
            // If network is unavailable (all URLs failed), try to load cached user
            if (appUser.baseUrl == null) {
                Timber.tag("HproseInstance").w("Network unavailable, attempting to load cached user for offline mode")
                val storedUserId = preferenceHelper.getUserId()
                if (storedUserId != TW_CONST.GUEST_ID) {
                    // Try to load the cached user
                    val cachedUser = TweetCacheManager.getCachedUser(storedUserId)
                    if (cachedUser != null) {
                        // Use the cached user but keep baseUrl as null for offline mode
                        appUser = cachedUser.copy(baseUrl = null)
                        Timber.tag("HproseInstance").d("✅ Loaded cached user for offline mode: userId=${appUser.mid}, username=${appUser.username}")
                    } else {
                        // No cached user found, keep the userId from preferences but set baseUrl to null
                        Timber.tag("HproseInstance").w("No cached user found for userId: $storedUserId, keeping userId but baseUrl is null")
                        appUser = appUser.copy(baseUrl = null)
                    }
                } else {
                    // Guest user
                    Timber.tag("HproseInstance").d("Guest user, using alpha IDs")
                    appUser = appUser.copy(
                        baseUrl = null,
                        followingList = getAlphaIds()
                    )
                }
            }
            // Re-throw the exception so the calling code can handle it
            throw e
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
        Timber.tag("initAppEntry").d("Attempting to initialize app entry with ${urls.size} URL(s): $urls")

        for (url in urls) {
            try {
                Timber.tag("initAppEntry").d("Trying URL: $url")
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
                val response: HttpResponse = httpClient.get(url)
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
                        Timber.tag("initAppEntry").d("Build type: ${if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"}, Using APP_ID: $_appId")

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
                        Timber.tag("initAppEntry").d("Successfully parsed paramMap: $paramMap")
                        val entryIP = filterIpAddresses(paramMap["addrs"] as List<String>)
                        if (entryIP != null) {
                            return entryIP
                        } else {
                            Timber.tag("findEntryIP").w("filterIpAddresses returned null for URL: $url")
                            // Continue to next URL
                        }
                    }
                } else {
                    Timber.tag("initAppEntry").w("No data found within window.setParam() for URL: $url")
                }
            } catch (e: Exception) {
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                if (isNetworkError) {
                    Timber.tag("initAppEntry").w(e, "Network error connecting to URL: $url (will try next URL if available)")
                } else {
                    Timber.tag("initAppEntry").e(e, "Failed to initialize app entry from URL: $url")
                }
            }
        }

        // If we reach here, all URLs failed
        val errorMsg = "Failed to find entry IP. Tried ${urls.size} URL(s): ${urls.joinToString(", ")}"
        Timber.tag("findEntryIP").e(errorMsg)
        throw IllegalStateException(errorMsg)
    }

    private suspend fun initAppEntry() {
        // Find the best IP by trying URLs
        val entryIP = findEntryIP()
        appUser.baseUrl = "http://$entryIP"
        Timber.tag("initAppEntry").d("Set baseUrl to IP: http://$entryIP")

        val userId = preferenceHelper.getUserId()
        Timber.tag("initAppEntry").d("Retrieved userId from preferences: $userId")
        if (userId != TW_CONST.GUEST_ID) {
            /**
             * If there is a valid userId in preference, this is a login user.
             * Initiate current account.
             *
             * Always force refresh of appUser's baseUrl on app start to ensure we have the latest IP.
             * Pass empty string to force IP re-resolution and bypass cache (matching iOS behavior).
             * This matches iOS initAppEntry() which calls fetchUser(appUser.mid, baseUrl: "")
             * */
            Timber.tag("initAppEntry")
                .d("Always refreshing appUser's baseUrl on app start for userId: $userId")

            // Pass empty string to getUser to force IP re-resolution (like iOS fetchUser with baseUrl: "")
            // forceRefresh ensures fresh data from server without needing to clear cache
            val refreshedUser = fetchUser(userId, baseUrl = "", forceRefresh = true)
            val refreshedBaseUrl = refreshedUser?.baseUrl
            if (refreshedUser != null && refreshedBaseUrl != null && refreshedBaseUrl.isNotEmpty()) {
                // Use the refreshed user's baseUrl
                appUser = refreshedUser
                TweetCacheManager.saveUser(refreshedUser)
                Timber.tag("initAppEntry")
                    .d("✅ App initialized with refreshed user baseUrl: ${appUser.baseUrl}")
            } else {
                // Network fetch failed, try to load cached user
                Timber.tag("initAppEntry")
                    .w("User fetch failed, attempting to load cached user for userId: $userId")
                val cachedUser = TweetCacheManager.getCachedUser(userId)
                if (cachedUser != null) {
                    // Use cached user but update baseUrl to the resolved IP
                    appUser.baseUrl = "http://$entryIP"
                    Timber.tag("initAppEntry")
                        .d("✅ Loaded cached user with resolved IP baseUrl: ${appUser.baseUrl}, username: ${appUser.username}")
                } else {
                    // No cached user found, fall back to bestIp with current userId
                    Timber.tag("initAppEntry")
                        .w("No cached user found, using resolved IP: $entryIP")
                    appUser.baseUrl = "http://$entryIP"
                }
            }
            User.updateUserInstance(appUser, true)      // sync appUser with its user instance.
            Timber.tag("initAppEntry")
                .d("User initialized. $appId, appUser.baseUrl: ${appUser.baseUrl}")
        } else {
            appUser.followingList = getAlphaIds()
            TweetCacheManager.saveUser(appUser)
            Timber.tag("initAppEntry").d("Guest user initialized. $appId, $appUser")
        }
        // once a workable URL is found, return successfully
        Timber.tag("initAppEntry").d("✅ Successfully initialized app entry")
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
                    Timber.tag("sendMessage").e("❌ Failed to send to sender node (attempt ${attempt + 1}/${maxRetries + 1}): $lastError")
                    
                    if (attempt < maxRetries) {
                        val delay = (attempt + 1) * 2000L // 2, 4 seconds
                        Timber.tag("sendMessage").d("⏳ Waiting ${delay / 1000} seconds before retry...")
                        delay(delay)
                        continue
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: applicationContext.getString(R.string.error_network)
                Timber.tag("sendMessage").e("❌ Error sending to sender node (attempt ${attempt + 1}/${maxRetries + 1}): $lastError")
                
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

            // Update timestamp to current system time for incoming messages (matching iOS)
            val currentTime = System.currentTimeMillis()
            return incomingMessages.map { message ->
                message.copy(timestamp = currentTime)
            }
        } catch (e: Exception) {
            Timber.tag("checkNewMessages").e(e, "Error in checkNewMessages")
            null
        }
    }

    suspend fun checkUpgrade(): Map<String, String>? {
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
        val entry = "get_userid"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "username" to username
        )
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
        var lastError: String? = null
        
        repeat(maxRetries) { attempt ->
            try {
                // Get user ID - retry if this fails
                val userId = getUserId(username)
                if (userId == null) {
                    lastError = context.getString(R.string.login_getuserid_fail)
                    Timber.tag("Login").w("getUserId failed for username: $username (attempt ${attempt + 1}/$maxRetries)")
                    // Don't return yet, let retry logic handle it
                    if (attempt == maxRetries - 1) {
                        // Last attempt failed
                        return Pair(null, lastError)
                    }
                    throw Exception("getUserId returned null")
                }
                
                // Fetch user - retry if this fails  
                val user = fetchUser(userId, "", maxRetries = 1)
                if (user == null) {
                    lastError = context.getString(R.string.login_getuser_fail)
                    Timber.tag("Login").w("fetchUser failed for userId: $userId (attempt ${attempt + 1}/$maxRetries)")
                    // Don't return yet, let retry logic handle it
                    if (attempt == maxRetries - 1) {
                        // Last attempt failed
                        return Pair(null, lastError)
                    }
                    throw Exception("fetchUser returned null")
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
                        // For other failures, continue to retry
                    }
                } else {
                    // Response was null or unwrapV2Response returned null (error case)
                    lastError = context.getString(R.string.login_error)
                    // Continue to retry for unknown errors
                }
            } catch (e: Exception) {
                lastError = ErrorMessageUtils.getNetworkErrorMessage(context, e)
                Timber.tag("Login").e(e, "Login attempt ${attempt + 1} failed")
                
                // Check if it's a network-related error that should be retried
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                
                if (!isNetworkError) {
                    // Don't retry for non-network errors
                    return Pair(null, lastError)
                }
            }
            
            // If this isn't the last attempt, wait before retrying
            if (attempt < maxRetries - 1) {
                val delayMs = minOf(5000L, 1000L * (1 shl attempt)) // Exponential backoff: 1s, 2s, 4s
                Timber.tag("Login").d("Retrying login in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                delay(delayMs)
            }
        }
        
        // All retries failed
        return Pair(null, lastError ?: context.getString(R.string.login_error))
    }

    /**
     * @param nodeId
     * Find IP addresses of given node.
     * */
    suspend fun getHostIP(nodeId: MimeiId, v4Only: String = "false"): String? {
        val entry = "get_node_ip"
        val params = mapOf("aid" to appId, "ver" to "last", "version" to "v2", "nodeid" to nodeId, "v4only" to v4Only)
        try {
            val rawResponse = appUser.hproseService?.runMApp<Any>(entry, params)
            return unwrapV2Response<String>(rawResponse)
        } catch (e: Exception) {
            Timber.tag("getHostIP").e("$e $nodeId")
        }
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
                } else {
                    Timber.tag("registerUser").w("Warning: User object not found in registration response")
                }

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

                // Clear user cache to ensure fresh data is loaded
                TweetCacheManager.removeCachedUser(appUser.mid)
                Timber.tag("updateUserCore").d("Cleared user cache for: ${appUser.mid}")

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
     * */
    suspend fun getFollowings(user: User): List<MimeiId> {
        if (user.isGuest()) return getAlphaIds()
        val entry = "get_followings_sorted"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "userid" to user.mid
        )
        return try {
            val rawResponse = user.hproseService?.runMApp<Any>(entry, params)
            val response = unwrapV2Response<List<Map<String, Any>>>(rawResponse)
            response?.sortedByDescending { (it["value"] as? Int) ?: 0 }
                ?.mapNotNull { it["field"] as? String } ?: getAlphaIds()
        } catch (e: Exception) {
            Timber.tag("Hprose.getFollowings").e(e)
            getAlphaIds()
        }
    }

    /**
     * Given user object get a list of Field-Value, where Field is user Id,
     * Value is timestamp when the follower is added.
     * */
    suspend fun getFans(user: User): List<MimeiId>? {
        if (user.isGuest()) return null
        val entry = "get_followers_sorted"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "userid" to user.mid
        )
        return try {
            val rawResponse = user.hproseService?.runMApp<Any>(entry, params)
            val response = unwrapV2Response<List<Map<String, Any>>>(rawResponse)
            response?.sortedByDescending { (it["value"] as? Int) ?: 0 }
                ?.mapNotNull { it["field"] as? String }
        } catch (e: Exception) {
            Timber.tag("Hprose.getFans").e(e)
            null
        }
    }

    /**
     * Load tweets of appUser and its followings from network.
     * Keep null elements in the response list and preserves their positions.
     * */
    suspend fun getTweetFeed(
        user: User = appUser,
        pageNumber: Int = 0,
        pageSize: Int = 20,
        entry: String = "get_tweet_feed"
    ): List<Tweet?> {
        val alphaIds = getAlphaIds()
        val userIdForGuest = if (alphaIds.isNotEmpty()) alphaIds.first() else ""
        
        // For guest users, if no alpha IDs are configured, return empty list
        if (user.isGuest() && alphaIds.isEmpty()) {
            Timber.tag("getTweetFeed").w("No alpha IDs configured for guest user")
            return emptyList()
        }
        
        val params = mutableMapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "pn" to pageNumber,
            "ps" to pageSize,
            "userid" to if (!user.isGuest()) user.mid else userIdForGuest,
            "appuserid" to appUser.mid
        )
        if (entry == "update_following_tweets") {
            appUser.hostIds?.first()?.let { hostId ->
                params["hostid"] = hostId
            }
        }
        return try {
            val response =
                user.hproseService?.runMApp<Map<String, Any>>(entry, params)

            // Check success status first
            val success = response?.get("success") as? Boolean
            if (success != true) {
                val errorMessage = response?.get("message") as? String ?: applicationContext.getString(R.string.error_unknown_occurred)
                Timber.tag("getTweetFeed").e("Feed failed: $errorMessage")
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
                        originalTweet.author = fetchUser(originalTweet.authorId)
                        TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId)
                    } catch (e: Exception) {
                        Timber.tag("getTweetFeed").e("Error caching original tweet: $e")
                    }
                }
            }

            // Process main tweets - cache mainfeed tweets under appUser.mid
            tweetsData?.map { tweetJson ->
                // If the element is null, keep it as null
                if (tweetJson == null) {
                    null
                } else {
                    // Try to decode the tweet
                    try {
                        val tweet = Tweet.from(tweetJson)
                        tweet.author = fetchUser(tweet.authorId)

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
        } catch (e: Exception) {
            Timber.tag("getTweetFeed").e("Exception: $e")
            Timber.tag("getTweetFeed").e("❌ STACK TRACE: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    /**
     * Load tweets of a specific user by rank.
     * Handles null elements in the response list and preserves their positions.
     * */
    suspend fun getTweetsByUser(
        user: User,
        pageNumber: Int = 0,
        pageSize: Int = 20,
        entry: String = "get_tweets_by_user"
    ): List<Tweet?> {
        try {
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "version" to "v2",
                "userid" to user.mid,
                "pn" to pageNumber,
                "ps" to pageSize,
                "appuserid" to appUser.mid
            )

            Timber.tag("getTweetsByUser")
                .d("Fetching tweets for user: ${user.mid}, page: $pageNumber, size: $pageSize")
            val response = user.hproseService?.runMApp<Map<String, Any>>(entry, params)

            // Check success status first
            val success = response?.get("success") as? Boolean
            if (success != true) {
                val errorMessage = response?.get("message") as? String ?: applicationContext.getString(R.string.error_unknown_occurred)
                Timber.tag("getTweetsByUser")
                    .e("Tweets loading failed for user ${user.mid}: $errorMessage")
                Timber.tag("getTweetsByUser").e("Response: $response")

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
                        originalTweet.author = fetchUser(originalTweet.authorId)
                        TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId)
                        Timber.tag("getTweetsByUser")
                            .d("Cached original tweet: ${originalTweet.mid}")
                    } catch (e: Exception) {
                        Timber.tag("getTweetsByUser").e("Error caching original tweet: $e")
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
                        Timber.tag("getTweetsByUser").e("Error decoding tweet: $e")
                        null
                    }
                }
            } ?: emptyList()

            Timber.tag("getTweetsByUser")
                .d("Received ${tweetsData?.size ?: 0} tweets (${result.filterNotNull().size} valid) and ${originalTweetsData?.size ?: 0} original tweets for user: ${user.mid}")

            return result
        } catch (e: Exception) {
            Timber.tag("getTweetsByUser").e("Error fetching tweets for user: ${user.mid}")
            Timber.tag("getTweetsByUser").e("Exception: $e")
            Timber.tag("getTweetsByUser").e("Stack trace: ${e.stackTraceToString()}")

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
        // Check if tweet is blacklisted
        if (BlackList.isBlacklisted(tweetId)) {
            Timber.tag("fetchTweet").d("Tweet $tweetId is blacklisted, returning null")
            return null
        }

        return try {
            // Check cache first using TweetCacheManager
            val author = fetchUser(authorId)
            val cachedTweet = TweetCacheManager.getCachedTweet(tweetId)
            if (cachedTweet != null) {
                cachedTweet.author = author
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

            val rawResponse = author?.hproseService?.runMApp<Map<String, Any>>(entry, params)
            val tweetData = unwrapV2Response<Map<String, Any>>(rawResponse)
            tweetData?.let {
                // Record successful access
                BlackList.recordSuccess(tweetId)

                Tweet.from(it).apply {
                    this.author = author
                    // Cache tweet by authorId, not appUser.mid
                    TweetCacheManager.saveTweet(
                        this,
                        userId = authorId
                    )
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
            val author = fetchUser(authorId) ?: return null
            val entry = "refresh_tweet"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "version" to "v2",
                "tweetid" to tweetId,
                "appuserid" to appUser.mid,
                "userid" to authorId,
                "hostid" to (author.hostIds?.first() ?: "")
            )
            val rawResponse = author.hproseService?.runMApp<Map<String, Any>>(entry, params)
            
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
                tweet.author = author
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
                tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
                
                // If no cached user found, create a skeleton user object as placeholder for offline loading
                if (tweet.author == null) {
                    tweet.author = getUserInstance(tweet.authorId)
                    Timber.tag("loadCachedTweets").d("📦 Created skeleton user placeholder for tweet ${tweet.mid} - authorId ${tweet.authorId}")
                }
                
                Timber.tag("loadCachedTweets").d("✅ Loaded cached tweet ${tweet.mid} with author ${tweet.author?.username ?: tweet.authorId}")
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
                tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
                
                // If no cached user found, create a skeleton user object as placeholder for offline loading
                if (tweet.author == null) {
                    tweet.author = getUserInstance(tweet.authorId)
                    Timber.tag("loadCachedTweetsByAuthor").d("Created skeleton user placeholder for tweet ${tweet.mid} - authorId ${tweet.authorId}")
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
            val rawResponse = originalTweet.author?.hproseService?.runMApp<Map<String, Any>>(entry, params)
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
            val rawResponse =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)
            if (response != null) {
                val newTweetId = response["mid"] as? String
                if (newTweetId != null) {
                    // Create a new tweet with the updated mid
                    val updatedTweet = tweet.copy(mid = newTweetId, author = appUser)

                    Timber.tag("HproseInstance").d("Tweet uploaded successfully with new ID: ${newTweetId}")

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
                            // Create a new user object to trigger recompose
                            val updatedUser = refreshedUser.copy()
                            appUser = updatedUser
                            TweetCacheManager.saveUser(appUser)
                            
                            // Notify other ViewModels that user data has been updated
                            TweetNotificationCenter.post(TweetEvent.UserDataUpdated(appUser.copy()))
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
                val errorMessage = rawResponse?.get("message") as? String ?: applicationContext.getString(R.string.error_upload_unknown)
                Timber.tag("uploadTweet").e("Upload failed: $errorMessage")
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
            val rawResponse =
                parentTweet.author?.hproseService?.runMApp<Any>(entry, params)
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
            val rawResponse =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)

            if (response != null) {
                // Handle successful response with updated user and tweet data
                val updatedUserData = response["user"] as? Map<String, Any>
                val updatedTweetData = response["tweet"] as? Map<String, Any>
                
                if (updatedUserData != null) {
                    // Update appUser with new data from server
                    appUser.from(updatedUserData)
                }
                
                if (updatedTweetData != null) {
                    // Create updated tweet from server response
                    val updatedTweet = Tweet.from(updatedTweetData)
                    updatedTweet.author = fetchUser(updatedTweet.authorId)
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
            val rawResponse =
                tweet.author?.hproseService?.runMApp<Map<String, Any>>(entry, params)
            val response = unwrapV2Response<Map<String, Any>>(rawResponse)

            if (response != null) {
                // Handle successful response with updated user and tweet data
                val updatedUserData = response["user"] as? Map<String, Any>
                val updatedTweetData = response["tweet"] as? Map<String, Any>
                
                if (updatedUserData != null) {
                    // Update appUser with new data from server
                    appUser.from(updatedUserData)
                }
                
                if (updatedTweetData != null) {
                    // Create updated tweet from server response
                    val updatedTweet = Tweet.from(updatedTweetData)
                    updatedTweet.author = fetchUser(updatedTweet.authorId)
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
     * */
    suspend fun getUserTweetsByType(
        user: User,
        type: UserContentType,
        pageNumber: Int = 0,
        pageSize: Int = TW_CONST.PAGE_SIZE
    ): List<Tweet?> {
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
            val rawResponse = user.hproseService?.runMApp<Any>(entry, params)
            val response = unwrapV2Response<List<Map<String, Any>?>>(rawResponse)

            response?.map { tweetJson ->
                // If the element is null, keep it as null
                if (tweetJson == null) {
                    null
                } else {
                    // Try to decode the tweet
                    try {
                        val tweet = Tweet.from(tweetJson)
                        tweet.author = fetchUser(tweet.authorId)
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
            val rawResponse =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
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
                    // Create a new user object to trigger recompose
                    val updatedUser = refreshedUser.copy()
                    appUser = updatedUser
                    TweetCacheManager.saveUser(appUser)

                    // Notify other ViewModels that user data has been updated
                    TweetNotificationCenter.post(TweetEvent.UserDataUpdated(appUser.copy()))
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

    /**
     * Load all comments of a tweet.
     * @param pageNumber
     * */
    suspend fun getComments(tweet: Tweet, pageNumber: Int = 0, pageSize: Int = 20): List<Tweet>? {
        return try {
            // CRITICAL: Use the tweet's author's baseUrl to fetch comments
            // Comments are stored on the tweet author's node, not the appUser's node
            // Fetch author if not already loaded
            if (tweet.author == null) {
                // Check cache first before fetching from server
                tweet.author = TweetCacheManager.getCachedUser(tweet.authorId) ?: fetchUser(tweet.authorId)
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
            val rawResponse = authorService.runMApp<Any>(entry, params)
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
            val rawResponse =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
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
                val errorMessage = rawResponse?.get("message") as? String ?: applicationContext.getString(R.string.error_unknown)
                Timber.tag("uploadComment").e("Failed to upload comment: $errorMessage")
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
     * @return User if successful, null otherwise
     */
    private suspend fun performUserUpdate(
        userId: MimeiId,
        user: User,
        maxRetries: Int,
        skipRetryAndBlacklist: Boolean,
        logContext: String = "fetchUser"
    ): User? {
        return try {
            val effectiveMaxRetries = if (skipRetryAndBlacklist) 1 else maxRetries
            val fetchSuccess = updateUserFromServerWithRetry(user, effectiveMaxRetries, skipRetryAndBlacklist)
            
            if (fetchSuccess && isValidUserData(user)) {
                TweetCacheManager.saveUser(user)
                user
            } else {
                Timber.tag(logContext).w("Failed to fetch valid user data: userId: $userId")
                null
            }
        } catch (e: Exception) {
            Timber.tag(logContext).e(e, "Exception in user update: userId: $userId")
            null
        }
    }

    /**
     * Starts background refresh for expired cached user
     */
    private fun startBackgroundRefresh(userId: MimeiId, cachedUser: User, maxRetries: Int, skipRetryAndBlacklist: Boolean) {
        TweetApplication.applicationScope.launch {
            try {
                val userInstance = getUserInstance(userId)
                userInstance.baseUrl = cachedUser.baseUrl
                
                performUserUpdate(userId, userInstance, maxRetries, skipRetryAndBlacklist, "getUser.backgroundRefresh")
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

    suspend fun fetchUser(userId: MimeiId?, baseUrl: String? = appUser.baseUrl, maxRetries: Int = 2, forceRefresh: Boolean = false, skipRetryAndBlacklist: Boolean = false): User? {
        if (userId == null) {
            Timber.tag("getUser").w("Null userId, returning null")
            return null
        }

        if (!skipRetryAndBlacklist && BlackList.isBlacklisted(userId)) {
            Timber.tag("getUser").d("User $userId is blacklisted, returning null")
            return null
        }

        // Check cache first (if not forcing refresh)
        if (!forceRefresh) {
            val cachedUser = TweetCacheManager.getCachedUser(userId)
            if (cachedUser != null && cachedUser.username != null) {
                // Matching iOS behavior: only return cached user if baseUrl parameter is not empty
                // This ensures that when ProfileScreen calls fetchUser with empty baseUrl,
                // it always fetches fresh data from server (like iOS ProfileView)
                if (!cachedUser.hasExpired && !baseUrl.isNullOrEmpty()) {
                    // Return valid cached user only if baseUrl parameter is provided
                    return cachedUser
                } else if (cachedUser.hasExpired) {
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
                            skipRetryAndBlacklist
                        )
                    }
                    // Return stale cached user while background refresh is running
                    return cachedUser
                }
                // If baseUrl is empty, fall through to fetch from server
            }
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
            return waitForConcurrentUpdate(userId, baseUrl, maxRetries, forceRefresh)
        }

        try {
            val user = getUserInstance(userId)

            // Determine base URL
            val finalBaseUrl = if (baseUrl.isNullOrEmpty()) {
                getProviderIP(userId)?.let { "http://$it" } ?: ""
            } else {
                baseUrl
            }

            if (finalBaseUrl.isEmpty()) {
                Timber.tag("getUser").w("Cannot fetch user $userId: no valid baseUrl available")
                return null
            }

            user.baseUrl = finalBaseUrl
            return performUserUpdate(userId, user, maxRetries, skipRetryAndBlacklist, "getUser")
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
     * Normalizes URL by removing http:// prefix
     */
    private fun normalizeIpFromUrl(url: String): String = url.removePrefix("http://").removePrefix("http")

    /**
     * Ensures URL has http:// prefix
     */
    private fun ensureHttpPrefix(url: String): String = 
        if (url.startsWith("http://") || url.startsWith("http")) url else "http://$url"

    /**
     * Validates user data is complete and valid
     */
    private fun isValidUserData(user: User): Boolean = user.mid.isNotEmpty() && user.username != null

    /**
     * Checks if two normalized IPs represent a redirect loop
     */
    private fun isRedirectLoop(currentIp: String, newIp: String): Boolean = 
        currentIp == newIp && currentIp.isNotEmpty()

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
     * Handles redirect response and retries the request
     */
    private suspend fun handleRedirectAndRetry(
        user: User, 
        providerIP: String, 
        entry: String, 
        params: Map<String, Any>, 
        skipRetryAndBlacklist: Boolean
    ): Boolean {
        Timber.tag("updateUserFromServer").d("🔄 PROVIDER IP RECEIVED: userId: ${user.mid}, providerIP: $providerIP")
        
        val normalizedRedirectIp = normalizeIpFromUrl(providerIP)
        val normalizedCurrentIp = normalizeIpFromUrl(user.baseUrl ?: "")
        
        if (isRedirectLoop(normalizedCurrentIp, normalizedRedirectIp)) {
            Timber.tag("updateUserFromServer").e("🔄 REDIRECT LOOP DETECTED: userId: ${user.mid}, redirected to same IP:port: $providerIP (current: ${user.baseUrl})")
            throw Exception("Redirect loop detected - redirected to same IP:port: $providerIP")
        }
        
        // Update baseUrl and retry
        user.baseUrl = ensureHttpPrefix(providerIP)
        user.clearHproseService()
        
        // Retry with new baseUrl
        val retryRawResponse = user.hproseService?.runMApp<Any>(entry, params)
        val retryResponse = if (retryRawResponse is Map<*, *>) {
            unwrapV2Response<Any>(retryRawResponse) ?: retryRawResponse
        } else {
            retryRawResponse
        }
        
        return when (retryResponse) {
            is String -> {
                val newIpAddress = retryResponse.trim()
                val newNormalizedIp = normalizeIpFromUrl(newIpAddress)
                
                if (isRedirectLoop(newNormalizedIp, normalizedRedirectIp)) {
                    Timber.tag("updateUserFromServer").e("🔄 REDIRECT LOOP DETECTED: userId: ${user.mid}, redirected server returned same IP:port: $newIpAddress")
                    throw Exception("Redirect loop detected - redirected server returned same IP:port: $newIpAddress")
                }
                
                Timber.tag("updateUserFromServer").w("⚠️ USER NOT FOUND AFTER REDIRECT: userId: ${user.mid}, second IP returned: $newIpAddress")
                throw Exception("User not found after redirect - second IP returned: $newIpAddress")
            }
            is Map<*, *> -> processUserDataResponse(user, retryResponse, skipRetryAndBlacklist)
            null -> {
                Timber.tag("updateUserFromServer").w("⚠️ NULL RESPONSE AFTER REDIRECT: userId: ${user.mid}")
                throw Exception("User not found after redirect - null response")
            }
            else -> {
                Timber.tag("updateUserFromServer").w("⚠️ UNEXPECTED RESPONSE TYPE AFTER REDIRECT: userId: ${user.mid}, type: ${retryResponse.javaClass.simpleName}")
                throw Exception("Unexpected response type after redirect: ${retryResponse.javaClass.simpleName}")
            }
        }
    }

    /**
     * Resolves and updates user's baseUrl (for first attempt or retries)
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
        if (attempt == 1 && !forceFreshIP && userHasBaseUrl && !user.baseUrl.isNullOrEmpty()) {
            Timber.tag("updateUserFromServer").d("📡 ATTEMPT $attempt/$maxRetries - Using user's existing baseUrl: ${user.baseUrl} for userId: ${user.mid} (hasExpired: $hasExpired)")
            return
        }
        
        // Resolve fresh IP
        if (attempt > 1) {
            // Retry attempts: check for redirect loop before resolving
            val providerIP = getProviderIP(user.mid)
            val normalizedProviderIp = normalizeIpFromUrl(providerIP ?: "")
            val normalizedCurrentIp = normalizeIpFromUrl(user.baseUrl ?: "")
            
            if (isRedirectLoop(normalizedCurrentIp, normalizedProviderIp)) {
                Timber.tag("updateUserFromServer").e("🔄 REDIRECT LOOP DETECTED on retry - resolved IP:port ($providerIP) same as current IP:port (${user.baseUrl})")
                throw Exception("Redirect loop detected - resolved IP:port same as current IP:port: $providerIP")
            }
            
            user.baseUrl = "http://$providerIP"
            user.clearHproseService()
        } else {
            // First attempt with fresh IP
            val reason = when {
                originalBaseUrl.isNullOrEmpty() -> "forcing fresh IP resolution (baseUrl param empty)"
                hasExpired -> "forcing fresh IP resolution (user cache expired, baseUrl also considered expired)"
                else -> "no baseUrl"
            }
            Timber.tag("updateUserFromServer").d("📡 ATTEMPT $attempt/$maxRetries - Resolving provider IP for userId: ${user.mid}, old baseUrl: ${user.baseUrl ?: ""}, reason: $reason")

            val providerIP = getProviderIP(user.mid)
            user.baseUrl = ensureHttpPrefix(providerIP ?: "")
            user.clearHproseService()
            
            if (user.hproseService == null) {
                Timber.tag("updateUserFromServer").e("hproseService is null after setting baseUrl: ${user.baseUrl} for userId: ${user.mid}")
            }
        }
    }

    /**
     * Update user data from server using "get_user" entry with retry logic
     * Matches iOS implementation: first attempt uses existing baseUrl, retries always resolve fresh IP
     * @return true if user data was successfully fetched and updated, false otherwise
     */
    private suspend fun updateUserFromServerWithRetry(user: User, maxRetries: Int = 2, skipRetryAndBlacklist: Boolean = false): Boolean {
        val originalBaseUrl = user.baseUrl
        val hasExpired = user.hasExpired
        val userHasBaseUrl = !user.baseUrl.isNullOrEmpty()
        val forceFreshIP = originalBaseUrl.isNullOrEmpty() || hasExpired
        
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
                    "version" to "v2",
                    "userid" to user.mid
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
                
                // Unwrap and process response
                val response = if (rawResponse is Map<*, *>) {
                    unwrapV2Response<Any>(rawResponse) ?: rawResponse
                } else {
                    rawResponse
                }
                
                return when (response) {
                    is String -> handleRedirectAndRetry(user, response.trim(), entry, params, skipRetryAndBlacklist)
                    is Map<*, *> -> processUserDataResponse(user, response, skipRetryAndBlacklist)
                    null -> {
                        Timber.tag("updateUserFromServer").w("❌ NULL RESPONSE: userId: ${user.mid}, attempt: $attempt")
                        throw Exception("Null response from server")
                    }
                    else -> {
                        Timber.tag("updateUserFromServer").w("⚠️ UNEXPECTED RESPONSE TYPE: userId: ${user.mid}, type: ${response.javaClass.simpleName}")
                        throw Exception("Unexpected response type: ${response.javaClass.simpleName}")
                    }
                }
            } catch (e: Exception) {
                lastError = e
                Timber.tag("updateUserFromServer").e("❌ USER UPDATE FAILED: userId: ${user.mid}, attempt: $attempt/$maxRetries, error: ${e.message}")
                
                if (e.message?.contains("Redirect loop detected") == true) {
                    Timber.tag("updateUserFromServer").e("🔄 REDIRECT LOOP DETECTED, stopping retries for userId: ${user.mid}")
                    if (!skipRetryAndBlacklist) {
                        BlackList.recordFailure(user.mid)
                    }
                    return false
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
        if (!skipRetryAndBlacklist && lastError != null) {
            BlackList.recordFailure(user.mid)
        }
        return false
    }

    /**
     * Try each IP address in the list until a healthy one is found
     * @param ipAddresses List of IP addresses to test
     * @param logPrefix Prefix for logging messages
     * @return First healthy IP address, or null if none found
     */
    private suspend fun tryIpAddresses(ipAddresses: List<String>, logPrefix: String = ""): String? {
        for ((index, ipAddress) in ipAddresses.withIndex()) {
            if (logPrefix.isNotEmpty()) {
                Timber.tag("getProviderIP").d("$logPrefix - Testing IP ${index + 1}/${ipAddresses.size}: $ipAddress")
            }

            // Validate the IP address format by attempting to construct a URL
            val testURL = try {
                if (ipAddress.startsWith("http")) ipAddress.trim() else "http://${ipAddress.trim()}"
            } catch (e: Exception) {
                continue
            }

            // Perform health check on this IP
            val isHealthy = isServerHealthy(HproseClientPool.getRegularClient(testURL))
            if (isHealthy) {
                return ipAddress
            } else {
                continue
            }
        }
        return null
    }

    /**
     * Get provider IP for a user using "get_provider_ips" entry
     * Tries each IP returned until a working one is found
     * If no IPs are returned or all IPs fail, tries again with entry IP
     */
    suspend fun getProviderIP(mid: MimeiId): String? {
        // Safety check: never try to get provider IP for GUEST_ID
        if (mid == TW_CONST.GUEST_ID) {
            Timber.tag("getProviderIP").e("❌ Refusing to get provider IP for GUEST_ID")
            return findEntryIP()
        }
        val providerIP = _getProviderIP(mid)
        if (providerIP != null) {
            return providerIP
        }

        if (mid == appUser.mid) {
            return try {
                val entryIP = findEntryIP()
                Timber.tag("getProviderIP").d("Found entry IP: $entryIP, retrying with entry IP client")
                val baseUrl = "http://$entryIP"
                _getProviderIP(mid, HproseClientPool.getRegularClient(baseUrl))
            } catch(_: Exception) {
                // All attempts failed - throw error
                Timber.tag("getProviderIP").e("All provider IP resolution attempts failed for appUser $mid")
                null
            }
        } else {
            return if (! isServerHealthy(appUser.hproseService)) {
                try {
                    val entryIP = findEntryIP()
                    Timber.tag("getProviderIP").d("Found entry IP: $entryIP, retrying with entry IP client")
                    val baseUrl = "http://$entryIP"
                    _getProviderIP(mid, HproseClientPool.getRegularClient(baseUrl))
                } catch(_: Exception) {
                    // All attempts failed - throw error
                    Timber.tag("getProviderIP").e("All provider IP resolution attempts failed for user $mid")
                    null
                }
            } else {
                null
            }
        }
    }

    private suspend fun _getProviderIP(mid: MimeiId, hproseService: HproseService? = appUser.hproseService): String? {
        val entry = "get_provider_ips"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "mid" to mid
        )

        return try {
            val rawResponse = hproseService?.runMApp<Any>(entry, params)
            val ipArray = unwrapV2Response<List<String>>(rawResponse)

            // If ipArray is valid, try each IP
            if (ipArray != null && ipArray.isNotEmpty()) {
                return tryIpAddresses(ipArray)
            }
            null
        } catch (e: Exception) {
            Timber.tag("getProviderIP").e("Error getting provider IPs for user $mid at ${appUser.baseUrl}: $e")
            null
        }
    }

    /**
     * Check if a server is healthy by making a simple HTTP request
     */
    private suspend fun isServerHealthy(hproseService: HproseService?): Boolean {
        return try {
            val params = mapOf(
                "aid" to appId,
                "ver" to "last"
            )
            val rawResponse = hproseService?.runMApp<Any>("health", params)
            val responseMap = rawResponse as? Map<String, Any>
            return responseMap?.get("success") == true
        } catch (e: Exception) {
            Timber.tag("isServerHealthy").w("Health check exception for $hproseService: ${e.message}")
            false
        }
    }

    /**
     * Return the current tweet list that is pinned to top.
     * */
    suspend fun togglePinnedTweet(tweetId: MimeiId): Boolean? {
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
                        val cid = statusData["cid"] as? String
                            ?: throw Exception(applicationContext.getString(R.string.error_no_cid_in_response))
                        
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
                        val errorMessage = statusData["message"] as? String ?: applicationContext.getString(R.string.error_video_conversion_failed)
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
                Timber.tag("pollVideoConversionStatus").e("Error polling status (attempt $consecutiveFailures/$maxConsecutiveFailures): ${e.message}")
                
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    throw Exception(applicationContext.getString(R.string.error_poll_status_failures, maxConsecutiveFailures, e.message ?: ""))
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
     * Ktor HTTP client with optimized connection pooling for distributed nodes
     * - Supports high concurrency for multiple node connections
     * - Reuses connections efficiently across requests to same nodes
     * - Proper timeout configuration for upload/download operations
     */
    val httpClient = HttpClient(CIO) {
        engine {
            maxConnectionsCount = 1000 // Total connections across all nodes
            // CIO engine handles connection pooling automatically
            // Connections are reused efficiently per host
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000_000 // Total request timeout (50 minutes for large uploads)
            connectTimeoutMillis = 60_000  // Connection timeout (1 minute)
            socketTimeoutMillis = 300_000  // Socket timeout (5 minutes)
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
                    } catch (e: Exception) {
                        Timber.tag("HproseInstance").w("Failed to release permission for URI: $uriString")
                    }
                }
            } catch (e: Exception) {
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
                
                Timber.tag("HproseInstance").d("Resumed non-video upload for workId: ${upload.workId} with ${validUris.size} valid URIs")
                
            } catch (e: Exception) {
                Timber.tag("HproseInstance").e("Error resuming upload ${upload.workId}: $e")
                // Remove the problematic incomplete upload to prevent future retries
                removeIncompleteUpload(context, upload.workId)
            }
        }
    }
    
    /**
     * Clear all stuck/stale uploads (useful for debugging or cleanup)
     * Cancels WorkManager jobs and removes incomplete upload tracking
     */
    fun clearStuckUploads(context: Context) {
        val incompleteUploads = getIncompleteUploads(context)
        if (incompleteUploads.isEmpty()) {
            Timber.tag("HproseInstance").d("No incomplete uploads to clear")
            return
        }
        
        Timber.tag("HproseInstance").d("Clearing ${incompleteUploads.size} stuck uploads")
        
        for (upload in incompleteUploads) {
            try {
                // Cancel WorkManager job
                try {
                    val uuid = UUID.fromString(upload.workId)
                    WorkManager.getInstance(context).cancelWorkById(uuid)
                    Timber.tag("HproseInstance").d("Cancelled work: ${upload.workId}")
                } catch (e: Exception) {
                    Timber.tag("HproseInstance").w("Could not cancel work ${upload.workId}: $e")
                }
                
                // Remove from tracking
                removeIncompleteUpload(context, upload.workId)
            } catch (e: Exception) {
                Timber.tag("HproseInstance").e("Error clearing upload ${upload.workId}: $e")
            }
        }
        
        Timber.tag("HproseInstance").d("Cleared all stuck uploads")
    }
}

