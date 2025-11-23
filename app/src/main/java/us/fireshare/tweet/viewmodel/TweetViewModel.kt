package us.fireshare.tweet.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.service.UploadCommentWorker
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.widget.VideoManager
import us.fireshare.tweet.widget.createExoPlayer
import java.io.File
import java.io.FileOutputStream
import java.lang.Integer.max
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import us.fireshare.tweet.datamodel.User

@HiltViewModel(assistedFactory = TweetViewModel.TweetViewModelFactory::class)
class TweetViewModel @AssistedInject constructor(
    @Assisted private val tweet: Tweet,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    @AssistedFactory
    interface TweetViewModelFactory {
        fun create(tweet: Tweet): TweetViewModel
    }

    private val _tweetState = MutableStateFlow(tweet)
    val tweetState: StateFlow<Tweet> get() = _tweetState.asStateFlow()

    private val _attachments = MutableStateFlow(tweet.attachments)
    val attachments: StateFlow<List<MimeiFileType>?> get() = _attachments.asStateFlow()

    private val _comments = MutableStateFlow<List<Tweet>>(emptyList())
    val comments: StateFlow<List<Tweet>> get() = _comments.asStateFlow()

    private val _mediaGridVideoIndex = MutableStateFlow(-1)
    val mediaGridVideoIndex: StateFlow<Int> get() = _mediaGridVideoIndex.asStateFlow()

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> get() = _isSharing.asStateFlow()

    private val exoPlayers = mutableMapOf<String, ExoPlayer>()

    // remember current video playback position after configuration changes.
    private val playbackPositions = mutableMapOf<String, Long>()

    fun getAudioPlayer(url: String, context: Context): ExoPlayer {
        // Extract media ID (CID) from URL to use as key
        val mediaId = extractMediaIdFromUrl(url)
        return exoPlayers.getOrPut(mediaId) {
            createExoPlayer(context, url).also { player ->
                val position = savedStateHandle.get<Long>("playbackPosition_$mediaId") ?: 0L
                player.seekTo(position)
                playbackPositions[mediaId] = position
            }
        }
    }
    
    /**
     * Extract media ID (IPFS CID) from URL
     * Returns the CID (e.g., "QmZeP6Cc1r8yG4u1iEksvEBM3ohajhobUfHdGyoBVAs6Qg")
     */
    private fun extractMediaIdFromUrl(url: String): String {
        // Look for IPFS CID pattern (Qm followed by 44 characters)
        val cidPattern = Regex("(Qm[A-Za-z0-9]{44})")
        val match = cidPattern.find(url)
        return match?.value ?: url // Fallback to full URL if no CID found
    }

    fun updateRetweetCount(tweet: Tweet) {
        _tweetState.value = tweetState.value.copy(retweetCount = tweet.retweetCount + 1)
        _tweetState.value = tweetState.value.copy(retweetCount = tweet.retweetCount - 1)
    }

    fun savePlaybackPosition(url: String, position: Long) {
        val mediaId = extractMediaIdFromUrl(url)
        playbackPositions[mediaId] = position
        savedStateHandle["playbackPosition_$mediaId"] = position
    }

    fun releaseAllPlayers() {
        exoPlayers.values.forEach { it.release() }
        exoPlayers.clear()
    }

    fun stopPlayer(url: String) {
        val mediaId = extractMediaIdFromUrl(url)
        exoPlayers[mediaId]?.playWhenReady = false  // have to set it here, otherwise won't work.
    }

    init {
        /**
         * Usually a tweet object has been well initialized in the tweet feed list.
         * However if invoked by Deeplink, the tweet object has to be initiated separately.
         * */
        Timber.d("TweetViewModel - Initializing with tweet: ${tweet.mid}, attachments: ${tweet.attachments?.size}")
        if (tweetState.value.author == null) {
            Timber.d("TweetViewModel - Author is null, checking cache first")
            viewModelScope.launch(Dispatchers.IO) {
                // Step 1: Check if there's a cached tweet with author already populated
                @Suppress("SENSELESS_COMPARISON")
                if (tweet.mid != null) {
                    val cachedTweet = TweetCacheManager.getCachedTweet(tweet.mid)
                    if (cachedTweet != null && cachedTweet.author != null) {
                        Timber.d("TweetViewModel - Found cached tweet with author: ${cachedTweet.mid}, author: ${cachedTweet.author?.username}")
                        _tweetState.value = cachedTweet
                        _attachments.value = cachedTweet.attachments
                        return@launch
                    }
                } else {
                    Timber.w("Tweet mid is null, skipping cache check")
                }
                
                // Step 2: Try to get user from cache and populate author
                var cachedUser: User? = null  // Declare here to make it accessible later
                @Suppress("SENSELESS_COMPARISON")
                if (tweet.authorId != null) {
                    cachedUser = TweetCacheManager.getCachedUser(tweet.authorId)
                    if (cachedUser != null) {
                        Timber.d("TweetViewModel - Found cached user: ${cachedUser.username}, populating author")
                        val tweetWithAuthor = tweet.copy(author = cachedUser)
                        _tweetState.value = tweetWithAuthor
                        // Continue to refresh tweet to get latest data, but now with author populated
                    } else {
                        Timber.d("TweetViewModel - User not in cache, will fetch from server")
                    }
                } else {
                    Timber.w("Tweet authorId is null, skipping user cache check")
                }
                
                // Step 3: Refresh tweet from server (getUser inside refreshTweet will use cache or fetch)
                @Suppress("SENSELESS_COMPARISON")
                if (tweet.mid != null && tweet.authorId != null) {
                    HproseInstance.refreshTweet(tweet.mid, tweet.authorId)?.let { refreshedTweet ->
                        Timber.d("TweetViewModel - Refreshed tweet: ${refreshedTweet.mid}, attachments: ${refreshedTweet.attachments?.size}, author: ${refreshedTweet.author?.username}")
                        _tweetState.value = refreshedTweet
                        _attachments.value = refreshedTweet.attachments
                    } ?: run {
                        Timber.w("TweetViewModel - Failed to refresh tweet, but keeping cached user if available")
                        // If refresh failed but we have cached user, at least show tweet with author
                        if (cachedUser != null && tweetState.value.author == null) {
                            _tweetState.value = tweet.copy(author = cachedUser)
                        }
                    }
                } else {
                    Timber.w("Cannot refresh tweet due to null mid or authorId")
                    // Optionally populate with cachedUser if available
                    if (cachedUser != null && tweetState.value.author == null) {
                        _tweetState.value = tweet.copy(author = cachedUser)
                    }
                }
            }
        } else {
            Timber.d("TweetViewModel - Author exists, using existing tweet data")
        }
    }

    /**
     * Refresh the appropriate tweet based on whether this is a retweet or not
     */
    suspend fun refreshTweetAndOriginal() {
        val currentTweet = tweetState.value

        try {
            if (currentTweet.originalTweetId != null && currentTweet.originalAuthorId != null) {
                // Check if this is a pure retweet (no content/attachments) or a quoted tweet (has content/attachments)
                if (currentTweet.content.isNullOrEmpty() && currentTweet.attachments.isNullOrEmpty()) {
                    // Pure retweet - refresh the original tweet that the user actually sees
                    // Since we don't store the original tweet in the ViewModel, we trigger a reload
                    // by updating the tweet state, which will cause the UI to reload the original tweet
                    _tweetState.value = currentTweet.copy(timestamp = currentTweet.timestamp)
                } else {
                    // Quoted tweet - refresh both the quoting tweet and the original tweet
                    HproseInstance.refreshTweet(currentTweet.mid, currentTweet.authorId)
                        ?.let { refreshedTweet ->
                            // Only update if the refreshed tweet has valid content
                            if (refreshedTweet.content != null || !refreshedTweet.attachments.isNullOrEmpty()) {
                                _tweetState.value = refreshedTweet
                            }
                        }

                    // Also refresh the original tweet that's displayed as a quote
                    HproseInstance.refreshTweet(
                        currentTweet.originalTweetId!!,
                        currentTweet.originalAuthorId!!
                    )?.let { originalTweet ->
                        Timber.tag("TweetViewModel")
                            .d("Refreshed original tweet for quoted tweet: ${originalTweet.mid}")
                    }
                }
            } else {
                // This is an original tweet - refresh it directly
                HproseInstance.refreshTweet(currentTweet.mid, currentTweet.authorId)
                    ?.let { refreshedTweet ->
                        // Only update if the refreshed tweet has valid content
                        if (refreshedTweet.content != null || !refreshedTweet.attachments.isNullOrEmpty()) {
                            _tweetState.value = refreshedTweet
                        }
                    }
            }
        } catch (e: Exception) {
            // Log error but don't update state to prevent content from disappearing
            Timber.tag("TweetViewModel").e(e, "Error refreshing tweet ${currentTweet.mid}")
        }
    }

    /**
     * when composing a comment, also post it as a tweet or not.
     * */
    val isCheckedToTweet = mutableStateOf(false)
    fun onCheckedChange(value: Boolean) {
        isCheckedToTweet.value = value
    }

    suspend fun loadComments(tweet: Tweet, pageNumber: Number = 0) {
        val newComments = HproseInstance.getComments(tweet, pageNumber.toInt())?.map {
            it.author = HproseInstance.getUser(it.authorId)
            it
        } ?: emptyList()

        // Always merge new comments with existing ones, keeping newly fetched ones over existing duplicates
        _comments.update { currentComments ->
            val mergedComments = newComments + currentComments.filter { it.mid !in newComments.map { new -> new.mid } }
            val finalComments = mergedComments.sortedByDescending { it.timestamp }
            // Only log when there are actually new comments or when comments count changes significantly
            if (newComments.isNotEmpty() || finalComments.size != currentComments.size) {
                Timber.tag("TweetViewModel").d("Merged to ${finalComments.size} total comments (${newComments.size} new)")
            }
            finalComments
        }
    }

    suspend fun delComment(commentId: MimeiId) {
        // Remove manual UI updates - let notification system handle them
        HproseInstance.delComment(tweetState.value, commentId) {
            // Callback is kept for backward compatibility but UI updates are handled by notifications
        }
    }

    /**
     * Delete a comment with optimistic updates for better UX
     */
    suspend fun deleteComment(commentId: MimeiId, comment: Tweet) {
        try {
            // Optimistically remove the comment from the UI immediately
            optimisticallyRemoveComment(commentId)

            // Make the backend call
            delComment(commentId)
        } catch (e: Exception) {
            // If backend call fails, revert the optimistic update
            Timber.tag("TweetViewModel").e(e, "Error deleting comment: ${e.message}")
            revertCommentRemoval(comment)
            throw e // Re-throw to let UI handle the error if needed
        }
    }

    /**
     * Optimistically remove a comment from the UI immediately
     */
    fun optimisticallyRemoveComment(commentId: MimeiId) {
        _comments.update { currentComments ->
            currentComments.filterNot { it.mid == commentId }
        }

        // Update comment count
        _tweetState.value = tweetState.value.copy(
            commentCount = max(0, tweetState.value.commentCount - 1)
        )
    }

    /**
     * Revert optimistic comment removal (add comment back to UI)
     */
    fun revertCommentRemoval(comment: Tweet) {
        _comments.update { currentComments ->
            (currentComments + comment)
                .distinctBy { it.mid }
                .sortedByDescending { it.timestamp }
        }

        // Restore comment count
        _tweetState.value = tweetState.value.copy(
            commentCount = tweetState.value.commentCount + 1
        )
    }

    fun updateMediaGridVideoIndex(index: Int) {
        _mediaGridVideoIndex.value = index
    }

    // add new Comment object to its parent Tweet. The code runs on Main thread.
    fun uploadComment(
        context: Context,
        content: String,
        attachments: List<Uri>? = null,
    ) {
        val data = workDataOf(
            "tweetId" to tweetState.value.mid,
            "authorId" to tweetState.value.authorId,
            "isCheckedToTweet" to isCheckedToTweet.value,
            "content" to content,   // content for new comment
            "attachmentUris" to attachments?.map { it.toString() }?.toTypedArray()
        )
        val uploadRequest = OneTimeWorkRequest.Builder(UploadCommentWorker::class.java)
            .setInputData(data)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10_000L, // 10 seconds
                TimeUnit.MILLISECONDS
            )
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(uploadRequest)

        // No need to observe work status - UI will update via notification system
    }

    suspend fun shareTweet(context: Context) {
        _isSharing.value = true
        try {
            /**
             * Call to checkUpgrade() also returns a map of environmental variables,
             * which includes environment variables of the App.
             * */
            val map = HproseInstance.checkUpgrade()
            if (map == null) {
                return
            }
            // Use appUser.domainToShare if available, otherwise fall back to checkUpgrade domain
            val domain = if (!appUser.domainToShare.isNullOrBlank()) {
                appUser.domainToShare!!
            } else {
                map["domain"] ?: run {
                    return
                }
            }
        val deepLink = if (BuildConfig.IS_PLAY_VERSION) {
            "http://gplay.fireshare.us/tweet/${tweet.mid}/${tweet.authorId}"
        } else {
            "http://$domain/tweet/${tweet.mid}/${tweet.authorId}"
        }

        // Generate share content based on tweet title, content, or attachment types
        val shareContent = when {
            !tweet.title.isNullOrBlank() -> {
                val title = tweet.title!!.trim()
                if (title.length <= 40) title else "${title.take(40)}..."
            }
            !tweet.content.isNullOrBlank() -> {
                val content = tweet.content!!.replace("\n", " ").trim()
                if (content.length <= 40) content else "${content.take(40)}..."
            }
            else -> {
                // No title or content, compose from first 3 attachment types
                composeAttachmentTypeText(tweet)
            }
        }

        val textToShare = if (shareContent.isNotEmpty()) {
            "$shareContent\n\n$deepLink"
        } else {
            deepLink
        }

        // Skip preview image generation - Android doesn't support sharing text and image together
        // This makes sharing instant instead of waiting for image processing
        Timber.tag("SHARE").d("Sharing text only (preview images not supported with text)")
        
        // Create share intent - share text only
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, textToShare)
            type = "text/plain"
        }
        
        // Create chooser
        val chooserIntent = Intent.createChooser(sendIntent, "Share Tweet")
        context.startActivity(chooserIntent, null)
        } finally {
            // Clear loading state after share sheet is shown
            _isSharing.value = false
        }
    }
    
    
    /**
     * Load attachment preview image for sharing
     * Returns a 270x270 center-cropped preview of the first image or video attachment
     */
    private suspend fun loadAttachmentPreviewImage(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        try {
            Timber.tag("SHARE").d("Loading attachment preview for tweet: ${tweet.mid}")
            
            // Find the source tweet with attachments (could be original for retweets/quotes)
            val sourceTweet = resolveSourceTweetWithAttachments() ?: run {
                Timber.tag("SHARE").d("No source tweet with attachments found")
                return@withContext null
            }
            
            Timber.tag("SHARE").d("Source tweet found: ${sourceTweet.mid}, attachments: ${sourceTweet.attachments?.size ?: 0}")
            
            val attachment = sourceTweet.attachments?.firstOrNull() ?: run {
                Timber.tag("SHARE").d("No first attachment found")
                return@withContext null
            }
            
            Timber.tag("SHARE").d("First attachment type: ${attachment.type}, mid: ${attachment.mid}")
            
            // Get base URL for attachments
            val baseURL = resolveAttachmentBaseURL(sourceTweet)
            Timber.tag("SHARE").d("Resolved baseURL: $baseURL")
            
            when (attachment.type) {
                MediaType.Image -> {
                    Timber.tag("SHARE").d("Processing image attachment")
                    var fullImage: Bitmap?

                    // Try to get cached image first
                    fullImage = ImageCacheManager.getCachedImage(context, attachment.mid)
                    
                    if (fullImage == null) {
                        // Download image if not cached
                        val imageUrl = HproseInstance.getMediaUrl(attachment.mid, baseURL).toString()
                        Timber.tag("SHARE").d("Loading image from URL: $imageUrl")
                        fullImage = ImageCacheManager.downloadAndCacheImage(context, imageUrl, attachment.mid)
                        Timber.tag("SHARE").d("Image loaded: ${fullImage != null}")
                    } else {
                        Timber.tag("SHARE").d("Found cached image")
                    }
                    
                    // Crop to center square and resize to 270x270
                    if (fullImage != null) {
                        val croppedImage = cropToCenter(fullImage, 270)
                        Timber.tag("SHARE").d("Image cropped to center 270x270")
                        return@withContext croppedImage
                    }
                    return@withContext null
                }
                MediaType.Video, MediaType.HLS_VIDEO -> {
                    Timber.tag("SHARE").d("Processing video attachment, type: ${attachment.type}")
                    val videoUrl = HproseInstance.getMediaUrl(attachment.mid, baseURL).toString()
                    Timber.tag("SHARE").d("Generating video preview from URL: $videoUrl")
                    val preview = generateVideoPreviewImage(videoUrl, attachment.mid, attachment.type)
                    Timber.tag("SHARE").d("Video preview generated: ${preview != null}")
                    return@withContext preview
                }
                else -> {
                    Timber.tag("SHARE").d("Attachment type not supported: ${attachment.type}")
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Timber.tag("SHARE").e(e, "Error loading attachment preview")
            return@withContext null
        }
    }
    
    /**
     * Resolve the source tweet that contains attachments
     * For retweets/quotes, returns the original tweet if it has attachments
     */
    private suspend fun resolveSourceTweetWithAttachments(): Tweet? {
        // Check if current tweet has attachments
        if (!tweet.attachments.isNullOrEmpty()) {
            return tweet
        }
        
        // Check if it's a retweet/quote and get original tweet
        if (tweet.originalTweetId != null && tweet.originalAuthorId != null) {
            try {
                val originalTweet = HproseInstance.fetchTweet(
                    tweet.originalTweetId!!,
                    tweet.originalAuthorId!!
                )
                if (originalTweet != null && !originalTweet.attachments.isNullOrEmpty()) {
                    return originalTweet
                }
            } catch (e: Exception) {
                Timber.tag("SHARE").e(e, "Error fetching original tweet")
            }
        }
        
        return null
    }
    
    /**
     * Resolve the base URL for attachments
     * Tries author's base URL first, then falls back to app user's base URL
     */
    private suspend fun resolveAttachmentBaseURL(sourceTweet: Tweet): String {
        // Try to get author's base URL
        try {
            val author = HproseInstance.getUser(sourceTweet.authorId)
            if (author?.baseUrl != null) {
                return author.baseUrl.toString()
            }
        } catch (e: Exception) {
            Timber.tag("SHARE").w("Error fetching author base URL: ${e.message}")
        }
        
        // Fall back to app user's base URL
        return appUser.baseUrl ?: "https://fireshare.us"
    }
    
    /**
     * Generate a video preview image from the existing player or video URL
     * For HLS: Uses a direct segment URL from the cached video
     * For Progressive: Uses MediaMetadataRetriever with the video URL
     * Returns a 270x270 center-cropped preview
     */
    private suspend fun generateVideoPreviewImage(videoUrl: String, mediaId: String, mediaType: MediaType): Bitmap? = withContext(Dispatchers.IO) {
        if (mediaType == MediaType.HLS_VIDEO) {
            // For HLS, get current playback position from player
            val playerInfo = withContext(Main) {
                val player = VideoManager.getCachedVideoPlayer(mediaId)
                player?.currentPosition
            }
            
            if (playerInfo == null) {
                Timber.tag("SHARE").d("No cached player for HLS, skipping preview generation")
                return@withContext null
            }

            // MediaMetadataRetriever needs a direct .ts segment file, not a .m3u8 playlist
            // Calculate which segment to use based on current position
            // Assuming standard HLS segment duration of 5 seconds
            val segmentDurationMs = 5000L
            val segmentNumber = (playerInfo / segmentDurationMs).toInt()
            val offsetWithinSegmentMs = playerInfo % segmentDurationMs
            val segmentName = "segment%03d.ts".format(segmentNumber)
            
            Timber.tag("SHARE").d("Current position: ${playerInfo}ms, using segment: $segmentName, offset: ${offsetWithinSegmentMs}ms")
            
            // Try different quality levels in order (smallest first for speed)
            val baseUrl = if (videoUrl.endsWith("/")) videoUrl else "$videoUrl/"
            val qualityLevels = listOf("480p", "720p")
            
            for (quality in qualityLevels) {
                val segmentUrl = "${baseUrl}${quality}/$segmentName"
                
                Timber.tag("SHARE").d("Trying HLS segment: $segmentUrl")
                
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(segmentUrl, mapOf("timeout" to "3000"))
                    
                    // Get frame at the offset within the segment
                    val captureTimeUs = offsetWithinSegmentMs * 1000
                    val frame = retriever.getFrameAtTime(captureTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    
                    if (frame != null) {
                        Timber.tag("SHARE").d("Successfully captured frame from $quality segment at offset ${offsetWithinSegmentMs}ms")
                        return@withContext cropToCenter(frame, 270)
                    }
                } catch (e: Exception) {
                    Timber.tag("SHARE").d("Failed with $quality: ${e.message}")
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                        // Ignore
                    }
                }
            }
            
            Timber.tag("SHARE").w("Failed to capture frame from any quality level")
            return@withContext null
        } else {
            // For progressive videos, use the direct URL
            // Get current playback position if player exists
            val currentPositionMs = withContext(Main) {
                val player = VideoManager.getCachedVideoPlayer(mediaId)
                player?.currentPosition ?: 1000L // Default to 1 second if no player
            }
            
            val retriever = MediaMetadataRetriever()
            try {
                Timber.tag("SHARE").d("Using progressive video URL: $videoUrl at position ${currentPositionMs}ms")
                retriever.setDataSource(videoUrl, mapOf("timeout" to "3000"))
                
                val captureTimeUs = currentPositionMs * 1000 // Convert ms to microseconds
                val frame = retriever.getFrameAtTime(captureTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                
                if (frame != null) {
                    Timber.tag("SHARE").d("Successfully captured frame")
                    return@withContext cropToCenter(frame, 270)
                } else {
                    Timber.tag("SHARE").w("Failed to capture frame from retriever")
                    return@withContext null
                }
            } catch (e: Exception) {
                Timber.tag("SHARE").e(e, "Failed to generate preview with MediaMetadataRetriever")
                return@withContext null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    Timber.tag("SHARE").w("Error releasing MediaMetadataRetriever: ${e.message}")
                }
            }
        }
    }

    /**
     * Crop image to center square and resize to target size
     */
    private fun cropToCenter(image: Bitmap, targetSize: Int = 360): Bitmap {
        val width = image.width
        val height = image.height
        
        // Determine the crop size (square based on the shorter dimension)
        val cropSize = minOf(width, height)
        
        // Calculate the crop rect (centered)
        val left = (width - cropSize) / 2
        val top = (height - cropSize) / 2
        
        // Create cropped bitmap
        val croppedBitmap = Bitmap.createBitmap(image, left, top, cropSize, cropSize)
        
        // Resize to target size
        val resizedBitmap = croppedBitmap.scale(targetSize, targetSize)
        
        // Clean up intermediate bitmap if different from input
        if (croppedBitmap != image && croppedBitmap != resizedBitmap) {
            croppedBitmap.recycle()
        }
        
        return resizedBitmap
    }
    
    /**
     * Save bitmap to cache directory and return the file
     * Also cleans up old preview files to avoid cache bloat
     */
    private fun saveBitmapToCache(context: Context, bitmap: Bitmap, filename: String): File? {
        return try {
            val cacheDir = File(context.cacheDir, "share_previews")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            // Clean up old preview files (keep only last 5)
            cacheDir.listFiles()?.let { files ->
                if (files.size > 5) {
                    files.sortedBy { it.lastModified() }
                        .dropLast(5)
                        .forEach { it.delete() }
                }
            }
            
            val file = File(cacheDir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            Timber.tag("SHARE").d("Saved preview image to: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Timber.tag("SHARE").e(e, "Error saving bitmap to cache")
            null
        }
    }
    
    /**
     * Compose a descriptive string from the first three attachment types
     */
    private fun composeAttachmentTypeText(tweet: Tweet): String {
        // Get attachments from the tweet or its original tweet
        var attachments: List<MimeiFileType>? = null
        
        if (!tweet.attachments.isNullOrEmpty()) {
            attachments = tweet.attachments
        } else if (tweet.originalTweetId != null) {
            // Try to get original tweet from cache
            val originalTweet = Tweet.getInstance(tweet.originalTweetId!!, tweet.originalAuthorId ?: "")
            if (!originalTweet.attachments.isNullOrEmpty()) {
                attachments = originalTweet.attachments
            }
        }
        
        if (attachments.isNullOrEmpty()) {
            return ""
        }
        
        // Get first 3 attachment types
        val firstThree = attachments.take(3)
        val typeTexts = firstThree.map { attachment ->
            when (attachment.type) {
                MediaType.Image -> "📷 Image"
                MediaType.Video, MediaType.HLS_VIDEO -> "🎬 Video"
                MediaType.Audio -> "🎵 Audio"
                MediaType.PDF -> "📄 PDF"
                MediaType.Word -> "📝 Word"
                MediaType.Excel -> "📊 Excel"
                MediaType.PPT -> "📊 PPT"
                MediaType.Zip -> "🗜️ Zip"
                MediaType.Txt -> "📄 Text"
                MediaType.Html -> "🌐 HTML"
                else -> "📎 File"
            }
        }
        
        // Add count if there are more attachments
        return if (attachments.size > 3) {
            val remaining = attachments.size - 3
            typeTexts.joinToString(", ") + " +$remaining more"
        } else {
            typeTexts.joinToString(", ")
        }
    }

    /**
     * Update favorite count and icon right away for better user experience.
     * */
    suspend fun toggleFavorite(
        updateAppUser: (Tweet, Boolean) -> Unit     // callback to update current user's account.
    ) {
        val isFavorite = tweetState.value.isFavorite
        _tweetState.value.isFavorite = !isFavorite
        _tweetState.value = tweetState.value.copy(
            favoriteCount = if (isFavorite) max(0, tweetState.value.favoriteCount - 1)
            else tweetState.value.favoriteCount + 1,
        )

        /**
         * Get the actual server response and update with real data.
         * */
        val updatedTweet = HproseInstance.toggleFavorite(tweetState.value)

        // Check if the operation failed (if the tweet state didn't change)
        if (updatedTweet.isFavorite == isFavorite) {
            // Revert optimistic changes on failure
            _tweetState.value.isFavorite = isFavorite
            _tweetState.value = tweetState.value.copy(
                favoriteCount = if (isFavorite) tweetState.value.favoriteCount + 1
                else max(0, tweetState.value.favoriteCount - 1),
            )
            // Show error toast
            notificationContextRef?.get()?.let { context ->
                Toast.makeText(
                    context,
                    "Failed to update favorite",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            _tweetState.value = updatedTweet
            // Update UserViewModel with the updated appUser data from server
            updateAppUser(updatedTweet, updatedTweet.isFavorite)
        }
    }

    /**
     * Update bookmark count and icon right away for better user experience.
     * */
    suspend fun toggleBookmark(updateAppUser: (Tweet, Boolean) -> Unit) {
        val hasBookmarked = tweetState.value.isBookmarked
        _tweetState.value.isBookmarked = !hasBookmarked
        _tweetState.value = tweetState.value.copy(
            bookmarkCount = if (hasBookmarked) max(0, tweetState.value.bookmarkCount - 1)
            else tweetState.value.bookmarkCount + 1,
        )

        /**
         * Get the actual server response and update with real data.
         * If backend fails, the original value will be restored.
         * */
        val updatedTweet = HproseInstance.toggleBookmark(tweetState.value)

        // Check if the operation failed (if the tweet state didn't change)
        if (updatedTweet.isBookmarked == hasBookmarked) {
            // Revert optimistic changes on failure
            _tweetState.value.isBookmarked = hasBookmarked
            _tweetState.value = tweetState.value.copy(
                bookmarkCount = if (hasBookmarked) tweetState.value.bookmarkCount + 1
                else max(0, tweetState.value.bookmarkCount - 1),
            )
            // Show error toast
            notificationContextRef?.get()?.let { context ->
                Toast.makeText(
                    context,
                    "Failed to update bookmark",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            _tweetState.value = updatedTweet
            // Update UserViewModel with the updated appUser data from server
            updateAppUser(updatedTweet, updatedTweet.isBookmarked)
        }
    }

    /**
     * Perform a retweet action and update the UI immediately for better user experience.
     * */
    suspend fun retweetTweet() {
        // Optimistic update of retweet count and status immediately for better UX
        val currentCount = tweetState.value.retweetCount
        _tweetState.value = tweetState.value.copy(
            retweetCount = currentCount + 1,
        )

        // Perform the actual retweet operation
        try {
            HproseInstance.retweet(tweetState.value)
        } catch (e: Exception) {
            // Revert the UI changes if the retweet failed
            _tweetState.value = tweetState.value.copy(
                retweetCount = currentCount,
            )
            throw e
        }
    }

    private var notificationContextRef: WeakReference<Context>? = null

    /**
     * Set the context for showing toast messages in notifications
     */
    fun setNotificationContext(context: Context) {
        notificationContextRef = WeakReference(context)
    }

    /**
     * Listen to tweet notifications and update the tweet detail accordingly
     */
    fun startListeningToNotifications(context: Context? = null) {
        if (context != null) {
            notificationContextRef = WeakReference(context)
        }
        viewModelScope.launch {
            try {
                Timber.tag("TweetViewModel")
                    .d("Starting notification listener for tweet ${tweetState.value.mid}")
                TweetNotificationCenter.events.collect { event ->
                    when (event) {
                        is TweetEvent.CommentUploaded -> {
                            // Only handle if this is the parent tweet for the comment
                            if (event.parentTweet.mid == tweetState.value.mid) {
                                Timber.tag("TweetViewModel")
                                    .d("CommentUploaded event received for tweet ${tweetState.value.mid}, comment ${event.comment.mid}, author ${event.comment.authorId}, current user ${appUser.mid}")

                                // Update the tweet state with new comment count
                                _tweetState.value = event.parentTweet

                                // Add the new comment to the comments list
                                _comments.update { currentComments ->
                                    (listOf(event.comment) + currentComments)
                                        .distinctBy { it.mid }
                                        .sortedByDescending { it.timestamp }
                                }
                            }
                        }

                        is TweetEvent.CommentUploadFailed -> {
                            // Show failure toast
                            val context = notificationContextRef?.get()
                            if (context != null) {
                                withContext(Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.comment_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            Timber.tag("TweetViewModel").e("Comment upload failed: ${event.error}")
                        }

                        is TweetEvent.CommentDeleted -> {
                            // Only handle if this is the parent tweet for the comment
                            if (event.parentTweetId == tweetState.value.mid) {
                                // Remove the comment from the comments list
                                _comments.update { currentComments ->
                                    currentComments.filterNot { it.mid == event.commentId }
                                }

                                // Update comment count
                                _tweetState.value = tweetState.value.copy(
                                    commentCount = max(0, tweetState.value.commentCount - 1)
                                )
                            }
                        }

                        is TweetEvent.TweetUpdated -> {
                            // Update tweet if this is the same tweet
                            if (event.tweet.mid == tweetState.value.mid) {
                                _tweetState.value = event.tweet
                            }
                        }

                        else -> {
                            // Handle other events if needed
                        }
                    }
                }
            } catch (e: CancellationException) {
                // This is expected when the ViewModel is destroyed
                Timber.tag("TweetViewModel").d("Notification listener cancelled: ${e.message}")
            } catch (e: Exception) {
                Timber.tag("TweetViewModel").e(e, "Error in notification listener: ${e.message}")
            }
        }
    }
}
