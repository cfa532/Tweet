# Independent Fullscreen Player Implementation Plan

## Step 1: Create FullScreenPlayerManager Singleton

### File: `app/src/main/java/us/fireshare/tweet/widget/FullScreenPlayerManager.kt`

```kotlin
object FullScreenPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var currentTweetList: List<Tweet>? = null
    private var currentVideoIndex: Int = 0
    private var onVideoChanged: ((Tweet, Int) -> Unit)? = null
    private var onPlayerStateChanged: ((PlayerState) -> Unit)? = null
    
    fun initialize(context: Context) {
        if (exoPlayer == null) {
            exoPlayer = createExoPlayer(context)
            exoPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            // Auto-play next video when current video ends
                            playNextVideo()
                        }
                    }
                }
            })
        }
    }
    
    fun setTweetList(tweets: List<Tweet>, startIndex: Int) {
        currentTweetList = tweets
        currentVideoIndex = startIndex
        playCurrentVideo()
    }
    
    fun playNextVideo() {
        val tweets = currentTweetList ?: return
        if (currentVideoIndex < tweets.size - 1) {
            currentVideoIndex++
            playCurrentVideo()
        } else {
            // End of list - could loop back to start or close player
            currentVideoIndex = 0
            playCurrentVideo()
        }
    }
    
    fun playPreviousVideo() {
        val tweets = currentTweetList ?: return
        if (currentVideoIndex > 0) {
            currentVideoIndex--
            playCurrentVideo()
        } else {
            // Beginning of list - could loop to end or stay
            currentVideoIndex = tweets.size - 1
            playCurrentVideo()
        }
    }
    
    private fun playCurrentVideo() {
        val tweets = currentTweetList ?: return
        val tweet = tweets[currentVideoIndex]
        
        // Find video attachment
        val videoAttachment = tweet.attachments?.find { 
            inferMediaTypeFromAttachment(it) == MediaType.Video || 
            inferMediaTypeFromAttachment(it) == MediaType.HLS_VIDEO 
        }
        
        if (videoAttachment != null) {
            val videoUrl = HproseInstance.getMediaUrl(videoAttachment.mid, tweet.author?.baseUrl.orEmpty())
            if (videoUrl != null) {
                loadVideo(videoUrl, inferMediaTypeFromAttachment(videoAttachment))
                onVideoChanged?.invoke(tweet, currentVideoIndex)
            }
        }
    }
    
    fun getCurrentPlayer(): ExoPlayer? = exoPlayer
    fun getCurrentTweet(): Tweet? = currentTweetList?.getOrNull(currentVideoIndex)
    fun getCurrentIndex(): Int = currentVideoIndex
    fun getTotalVideos(): Int = currentTweetList?.size ?: 0
    
    fun cleanup() {
        exoPlayer?.release()
        exoPlayer = null
        currentTweetList = null
        currentVideoIndex = 0
    }
}
```

## Step 2: Create New FullScreenVideoPlayer Composable

### File: `app/src/main/java/us/fireshare/tweet/widget/IndependentFullScreenPlayer.kt`

```kotlin
@Composable
fun IndependentFullScreenPlayer(
    tweetList: List<Tweet>,
    startIndex: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(false) }
    var currentTweet by remember { mutableStateOf<Tweet?>(null) }
    var currentIndex by remember { mutableStateOf(0) }
    var totalVideos by remember { mutableStateOf(0) }
    
    // Initialize the singleton player
    LaunchedEffect(Unit) {
        FullScreenPlayerManager.initialize(context)
        FullScreenPlayerManager.setTweetList(tweetList, startIndex)
        
        // Set up callbacks
        FullScreenPlayerManager.onVideoChanged = { tweet, index ->
            currentTweet = tweet
            currentIndex = index
            totalVideos = FullScreenPlayerManager.getTotalVideos()
        }
    }
    
    val exoPlayer = remember { FullScreenPlayerManager.getCurrentPlayer() }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls }
                )
            }
    ) {
        // Video player view
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Video information overlay
        if (showControls && currentTweet != null) {
            VideoInfoOverlay(
                tweet = currentTweet!!,
                currentIndex = currentIndex,
                totalVideos = totalVideos,
                onClose = onClose,
                onNext = { FullScreenPlayerManager.playNextVideo() },
                onPrevious = { FullScreenPlayerManager.playPreviousVideo() }
            )
        }
    }
    
    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            FullScreenPlayerManager.cleanup()
        }
    }
}

@Composable
private fun VideoInfoOverlay(
    tweet: Tweet,
    currentIndex: Int,
    totalVideos: Int,
    onClose: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top bar with close button and video counter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${currentIndex + 1} of $totalVideos",
                color = Color.White,
                fontSize = 16.sp
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }
        }
        
        // Tweet content
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = tweet.content,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "@${tweet.author?.username ?: "Unknown"}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
        
        // Bottom controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White
                )
            }
            
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White
                )
            }
        }
    }
}
```

## Step 3: Update Entry Points

### Update MediaBrowser.kt
Replace the existing FullScreenVideoPlayer call with:
```kotlin
IndependentFullScreenPlayer(
    tweetList = listOf(tweet), // Current tweet as single-item list
    startIndex = 0,
    onClose = { navController.popBackStack() }
)
```

### Update MediaItemView.kt
For videos in tweet lists, launch the independent player with the full tweet list context.

## Step 4: Add Gesture Support

### Swipe Navigation
```kotlin
.pointerInput(Unit) {
    detectDragGestures(
        onDragEnd = { dragAmount ->
            if (abs(dragAmount.x) > 100f) {
                if (dragAmount.x > 0) {
                    FullScreenPlayerManager.playPreviousVideo()
                } else {
                    FullScreenPlayerManager.playNextVideo()
                }
            }
        }
    ) { _, _ -> }
}
```

## Benefits of This Approach

1. **Automatic Progression**: Videos automatically advance when they finish
2. **Consistent Experience**: Single player instance ensures smooth transitions
3. **Better Performance**: No player conflicts or memory leaks
4. **Enhanced UX**: Users can binge-watch videos in tweet lists
5. **Simplified Architecture**: Centralized video management
6. **Gesture Support**: Intuitive swipe navigation between videos

## Migration Strategy

1. Implement FullScreenPlayerManager alongside existing system
2. Create IndependentFullScreenPlayer composable
3. Test with single videos first, then multi-video tweet lists
4. Gradually replace existing fullscreen entry points
5. Remove old player transfer logic once stable
