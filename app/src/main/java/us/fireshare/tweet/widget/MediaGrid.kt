package us.fireshare.tweet.widget

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.datamodel.MediaItem
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.MediaViewerParams
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.tweet.MediaItemView
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.Gadget.calculateVisibilityRatio
import us.fireshare.tweet.widget.VideoPlaybackCoordinator

/**
 * MediaGrid displays a grid of media items with intelligent layout based on aspect ratios.
 * 
 * Layout Strategy (matching iOS implementation):
 * - Uses PROPORTIONAL SIZING based on individual media aspect ratios
 * - Ensures maximum content visibility without excessive cropping
 * - Hero images in 3-item grids use golden ratio (61.8%) for aesthetic balance
 * - Clamps grid aspect ratios between 0.8 (portrait) and 1.618 (landscape/golden ratio)
 * 
 * 1 Item: Uses individual aspect ratio (min 0.8)
 * 2 Items: 
 *   - Both portrait: Horizontal layout with proportional widths
 *   - Both landscape: Vertical layout with proportional heights
 *   - Mixed: Horizontal layout with dynamic proportional widths
 * 3 Items:
 *   - All portrait: Hero left (61.8%), two stacked right with proportional heights
 *   - All landscape: Hero top (61.8%), two side-by-side bottom with proportional widths
 *   - Mixed: Hero (61.8% minimum) adapts based on first item orientation with proportional sizing
 * 4+ Items: 2x2 grid with aspect ratio based on all items' orientation
 */
@RequiresApi(Build.VERSION_CODES.R)
@OptIn(UnstableApi::class)
@Composable
fun MediaGrid(
    mediaItems: List<MimeiFileType>,
    viewModel: TweetViewModel,
    parentTweetId: MimeiId? = null,
    enableCoordinator: Boolean = true,
    containerTopY: Float? = null
) {
    Timber.d("MediaPreviewGrid: Composable called with ${mediaItems.size} items")
    val tweet by viewModel.tweetState.collectAsState()
    val navController = LocalNavController.current
    
    // Optimize: Pre-compute derived values to avoid recalculation
    val maxItems by remember(mediaItems.size) {
        derivedStateOf {
            when (mediaItems.size) {
                1 -> 1
                2, 3 -> mediaItems.size
                else -> 4
            }
        }
    }
    
    val limitedMediaList by remember(mediaItems, maxItems) {
        derivedStateOf { mediaItems.take(maxItems) }
    }

    val gridState = rememberLazyGridState()

    // Optimize: Pre-compute aspect ratios once instead of on every recomposition
    // This function is NOT @Composable to avoid unnecessary recomposition checks
    // Matches iOS implementation for consistent cross-platform media display
    fun aspectRatioOf(item: MimeiFileType): Float {
        val itemType = inferMediaTypeFromAttachment(item)
        return when (itemType) {
            MediaType.Video, MediaType.HLS_VIDEO -> {
                // For videos, try to get aspect ratio from stored value first
                // Default to 16:9 (standard video format) to match iOS implementation
                item.aspectRatio?.takeIf { it > 0 } ?: (16f / 9f)
            }
            MediaType.Image -> {
                // For images, use stored aspect ratio
                // If not available, return -1 to indicate unknown (will be handled appropriately)
                item.aspectRatio?.takeIf { it > 0 } ?: -1f
            }
            else -> {
                // For other types, use golden ratio
                1.618f
            }
        }
    }
    
    // Optimize: Cache aspect ratios to avoid recalculation during recomposition
    val cachedAspectRatios by remember(limitedMediaList) {
        derivedStateOf {
            limitedMediaList.map { item -> aspectRatioOf(item) }
        }
    }

    val context = LocalContext.current
    
    // Track which video should autoplay (only the first video in the grid)
    val firstVideoIndex by remember(limitedMediaList) {
        derivedStateOf {
            limitedMediaList.indexOfFirst { 
                val mediaType = inferMediaTypeFromAttachment(it)
                mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
            }.takeIf { it >= 0 } ?: -1
        }
    }

    fun isAutoPlayForGridIndex(gridIndex: Int): Boolean {
        // Only autoplay the first video in the grid
        return firstVideoIndex >= 0 && gridIndex == firstVideoIndex
    }
    
    // Preload videos and images
    LaunchedEffect(limitedMediaList) {
        // Preload videos to reduce memory pressure
        // Use LaunchedEffect's coroutine scope (launch) so children are cancelled when composable is disposed
        limitedMediaList.forEach { item ->
            val mediaType = inferMediaTypeFromAttachment(item)
            if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) {
                val mediaUrl = getMediaUrl(item.mid, tweet.author?.baseUrl.orEmpty()).toString()
                if (!VideoManager.isVideoPreloaded(item.mid)) {
                    // Launch in LaunchedEffect's scope so it's cancelled when composable is disposed
                    launch(Dispatchers.IO) {
                        try {
                            VideoManager.preloadVideo(context, item.mid, mediaUrl, item.type)
                        } catch (e: Exception) {
                            // Log error but don't block UI
                            Timber.tag("MediaGrid").e(e, "Failed to preload video: ${item.mid}")
                        }
                    }
                }
            }
        }
        
        // Preload images for faster loading
        limitedMediaList.forEach { item ->
            val mediaType = inferMediaTypeFromAttachment(item)
            if (mediaType == MediaType.Image) {
                val mediaUrl = getMediaUrl(item.mid, tweet.author?.baseUrl.orEmpty()).toString()
                // Launch in LaunchedEffect's scope so it's cancelled when composable is disposed
                launch(Dispatchers.IO) {
                    try {
                        ImageCacheManager.preloadImages(context, item.mid, mediaUrl)
                    } catch (e: Exception) {
                        // Log error but don't block UI
                        Timber.tag("MediaGrid").e(e, "Failed to preload image: ${item.mid}")
                    }
                }
            }
        }
    }

    // Track MediaGrid visibility and report it for all videos
    var gridVisibilityRatio by remember { mutableStateOf(0f) }
    
    // Get the tweet ID to use for video visibility reporting
    val tweetIdForVisibility = if (enableCoordinator) {
        if (parentTweetId != null && parentTweetId.isNotEmpty()) {
            parentTweetId
        } else {
            tweet.mid
        }
    } else null
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { layoutCoordinates ->
                    // Calculate MediaGrid visibility ratio
                    val visibility = calculateVisibilityRatio(layoutCoordinates)
                    gridVisibilityRatio = visibility
                    
                    // Report visibility for all videos in this MediaGrid
                    if (enableCoordinator && tweetIdForVisibility != null) {
                        limitedMediaList.forEachIndexed { index, item ->
                            val mediaType = inferMediaTypeFromAttachment(item)
                            if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) {
                                VideoPlaybackCoordinator.updateVideoVisibility(
                                    videoMid = item.mid,
                                    tweetId = tweetIdForVisibility,
                                    visibilityRatio = visibility
                                )
                            }
                        }
                    }
                }
        ) {
            when (limitedMediaList.size) {
            1 -> {
                // Use cached aspect ratio for better performance
                val aspectRatio = if (cachedAspectRatios[0] > 0.8f) {
                    cachedAspectRatios[0]
                } else {
                    0.8f
                }
                MediaItemView(
                    limitedMediaList,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .clipToBounds()
                        .clickable {
                            val params = MediaViewerParams(
                                mediaItems.map {
                                    MediaItem(
                                        getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                                        it.type
                                    )
                                }, 0, tweet.mid, tweet.authorId
                            )
                            navController.navigate(NavTweet.MediaViewer(params))
                        },
                    index = 0,
                    numOfHiddenItems = if (mediaItems.size > maxItems) mediaItems.size - maxItems else 0,
                    autoPlay = isAutoPlayForGridIndex(0),
                    inPreviewGrid = true,
                    loadOriginalImage = true, // Load original high-res image for single image
                    viewModel = viewModel,
                    parentTweetId = parentTweetId,
                    enableCoordinator = enableCoordinator,
                    containerTopY = containerTopY,
                    allMediaItems = mediaItems // Pass all items for full screen navigation
                )
            }
            2 -> {
                // Use cached aspect ratios for better performance
                val ar0 = cachedAspectRatios[0]
                val ar1 = cachedAspectRatios[1]
                
                // Check if aspect ratios are known (> 0 means valid aspect ratio)
                val hasValidAspectRatios = ar0 > 0 && ar1 > 0
                
                val isPortrait0 = ar0 < 1f
                val isPortrait1 = ar1 < 1f
                val isLandscape0 = ar0 > 1f
                val isLandscape1 = ar1 > 1f
                
            // If aspect ratios are unknown, default to horizontal layout with equal weights
                // This prevents incorrect vertical stacking of portrait images
                if (!hasValidAspectRatios) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.0f), // Square aspect for unknown
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(0.5f)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = mediaItems
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(0.5f)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                index = 1,
                                autoPlay = isAutoPlayForGridIndex(1),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = mediaItems
                            )
                        }
                    }
                } else if (isLandscape0 && isLandscape1) {
                // Both landscape: vertical layout with proportional heights based on aspect ratios
                // Calculate proportional heights for each image to show maximum content
                // Handle unknown aspect ratios (-1) by using equal weights
                val normalizedWeight0: Float
                val normalizedWeight1: Float
                if (ar0 > 0 && ar1 > 0) {
                    val weight0 = 1f / ar0  // Inverse of aspect ratio gives proportional height
                    val weight1 = 1f / ar1
                    val totalWeight = weight0 + weight1
                    normalizedWeight0 = weight0 / totalWeight
                    normalizedWeight1 = weight1 / totalWeight
                } else {
                    // If either aspect ratio is unknown, use equal weights
                    normalizedWeight0 = 0.5f
                    normalizedWeight1 = 0.5f
                }
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.8f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(normalizedWeight0)
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = mediaItems
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(normalizedWeight1)
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                index = 1,
                                autoPlay = isAutoPlayForGridIndex(1),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = mediaItems
                            )
                        }
                    }
            } else if (isPortrait0 && isPortrait1) {
                // Both portrait: horizontal layout with proportional widths based on aspect ratios
                // Calculate proportional widths for each image to show maximum content
                // Handle unknown aspect ratios (-1) by using equal weights
                val weight0: Float
                val weight1: Float
                if (ar0 > 0 && ar1 > 0) {
                    val totalIdealWidth = ar0 + ar1
                    weight0 = ar0 / totalIdealWidth
                    weight1 = ar1 / totalIdealWidth
                } else {
                    // If either aspect ratio is unknown, use equal weights
                    weight0 = 0.5f
                    weight1 = 0.5f
                }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.5f.coerceAtMost(1.618f)), // Clamp to max 1.618 (golden ratio)
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(weight0)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = mediaItems
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(weight1)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                index = 1,
                                autoPlay = isAutoPlayForGridIndex(1),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = mediaItems
                            )
                        }
                    }
            } else {
                // Mixed orientations or unknown aspect ratios: horizontal layout with proportional widths
                // Calculate dynamic aspect ratio based on sum of individual aspect ratios
                // Handle unknown aspect ratios (-1) by using defaults
                val weight0: Float
                val weight1: Float
                val gridAspectRatio: Float
                if (ar0 > 0 && ar1 > 0) {
                    val totalIdealWidth = ar0 + ar1
                    gridAspectRatio = totalIdealWidth.coerceIn(0.8f, 1.618f) // Clamp between min and max
                    weight0 = ar0 / totalIdealWidth
                    weight1 = ar1 / totalIdealWidth
                } else {
                    // If either aspect ratio is unknown, use equal weights and default grid aspect ratio
                    gridAspectRatio = 1.0f // Square aspect for unknown
                    weight0 = 0.5f
                    weight1 = 0.5f
                }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(gridAspectRatio),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(weight0)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = mediaItems
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(weight1)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                index = 1,
                                autoPlay = isAutoPlayForGridIndex(1),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = mediaItems
                            )
                        }
                    }
                }
            }
            3 -> {
                // Use cached aspect ratios for better performance
                val ar0 = cachedAspectRatios[0]
                val ar1 = cachedAspectRatios[1]
                val ar2 = cachedAspectRatios[2]
                val allPortrait = ar0 < 1f && ar1 < 1f && ar2 < 1f
                val allLandscape = ar0 > 1f && ar1 > 1f && ar2 > 1f
                val isPortrait0 = ar0 < 1f
                // Hero uses golden ratio for aesthetically pleasing proportions
                val minHeroWeight = 0.618f  // Golden ratio (φ)

                if (allPortrait) {
                    // All portrait: hero on left (61.8% golden ratio), two stacked on right with proportional heights
                    // Calculate proportional heights for right-side images
                    val weight1 = 1f / ar1  // Inverse of aspect ratio
                    val weight2 = 1f / ar2
                    val totalWeight = weight1 + weight2
                    val normalizedWeight1 = weight1 / totalWeight
                    val normalizedWeight2 = weight2 / totalWeight
                    val heroWeight = minHeroWeight
                    val sideWeight = 1f - heroWeight
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: left 61.8% (golden ratio)
                        Box(
                            modifier = Modifier
                                .weight(heroWeight)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                            )
                        }
                        // Second and third: right side (38.2%), stacked with proportional heights
                        Column(
                            modifier = Modifier.weight(sideWeight),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(normalizedWeight1)
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 1,
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(normalizedWeight2)
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 2,
                                    autoPlay = isAutoPlayForGridIndex(2),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                        }
                    }
                } else if (allLandscape) {
                    // All landscape: hero on top (61.8% golden ratio), two side-by-side on bottom with proportional widths
                    // Calculate proportional widths for bottom images
                    val totalIdealWidth = ar1 + ar2
                    val weight1 = ar1 / totalIdealWidth
                    val weight2 = ar2 / totalIdealWidth
                    val heroWeight = minHeroWeight
                    val bottomWeight = 1f - heroWeight
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: top 61.8% (golden ratio)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(heroWeight)
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                            )
                        }
                        // Second and third: bottom (38.2%), side by side with proportional widths
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(bottomWeight),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(weight1)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 1,
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(weight2)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 2,
                                    autoPlay = isAutoPlayForGridIndex(2),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                        }
                    }
                } else if (isPortrait0) {
                    // Mixed: first is portrait (hero on left), others stacked on right with proportional heights
                    // Calculate proportional heights for right-side images
                    val weight1 = 1f / ar1
                    val weight2 = 1f / ar2
                    val totalWeight = weight1 + weight2
                    val normalizedWeight1 = weight1 / totalWeight
                    val normalizedWeight2 = weight2 / totalWeight
                    // Ensure hero takes more than 50% of area
                    val heroWeight = minHeroWeight
                    val sideWeight = 1f - heroWeight
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: left 61.8% (golden ratio)
                        Box(
                            modifier = Modifier
                                .weight(heroWeight)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                            )
                        }
                        // Second and third: right side (38.2%), stacked with proportional heights
                        Column(
                            modifier = Modifier.weight(sideWeight),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(normalizedWeight1)
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 1,
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(normalizedWeight2)
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 2,
                                    autoPlay = isAutoPlayForGridIndex(2),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                        }
                    }
                } else {
                    // Mixed: first is landscape (hero on top), others side-by-side on bottom with proportional widths
                    // Calculate proportional widths for bottom images
                    val totalIdealWidth = ar1 + ar2
                    val weight1 = ar1 / totalIdealWidth
                    val weight2 = ar2 / totalIdealWidth
                    // Ensure hero takes more than 50% of area
                    val heroWeight = minHeroWeight
                    val bottomWeight = 1f - heroWeight
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: top 61.8% (golden ratio)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(heroWeight)
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                            )
                        }
                        // Second and third: bottom (38.2%), side by side with proportional widths
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(bottomWeight),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(weight1)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 1,
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(weight2)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 2,
                                    autoPlay = isAutoPlayForGridIndex(2),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                        }
                    }
                }
            }
            4 -> {
                // Use cached aspect ratios to determine grid aspect ratio
                val ar0 = cachedAspectRatios[0]
                val ar1 = cachedAspectRatios[1]
                val ar2 = cachedAspectRatios[2]
                val ar3 = cachedAspectRatios[3]
                val allLandscape = ar0 > 1f && ar1 > 1f && ar2 > 1f && ar3 > 1f
                val allPortrait = ar0 < 1f && ar1 < 1f && ar2 < 1f && ar3 < 1f
                
                val gridAspectRatio = when {
                    allLandscape -> 1.618f
                    allPortrait -> 0.8f
                    else -> null // No specific aspect ratio for mixed orientations
                }
                
                // Use improved lazy grid method for 4+ items with better loading optimization
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (gridAspectRatio != null) {
                                Modifier.aspectRatio(gridAspectRatio)
                            } else {
                                Modifier
                            }
                        )
                        .wrapContentWidth(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    itemsIndexed(
                        items = limitedMediaList,
                        key = { index, item -> "${item.mid}_$index" } // Stable key for better performance
                    ) { index, mediaItem ->
                        // Use grid aspect ratio for items when all have same orientation, otherwise use square
                        val itemAspectRatio = gridAspectRatio ?: 1f
                        MediaItemView(
                            limitedMediaList,
                            modifier = Modifier
                                .aspectRatio(itemAspectRatio)
                                .clipToBounds()
                                .clickable {
                                    val params = MediaViewerParams(
                                        mediaItems.map {
                                            MediaItem(
                                                getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                                                it.type
                                            )
                                        }, index, tweet.mid, tweet.authorId
                                    )
                                    navController.navigate(NavTweet.MediaViewer(params))
                                },
                            index = index,
                            numOfHiddenItems = if (index == limitedMediaList.size - 1 && mediaItems.size > maxItems)
                                mediaItems.size - maxItems else 0,
                            autoPlay = isAutoPlayForGridIndex(index),
                            inPreviewGrid = true,
                            viewModel = viewModel,
                            parentTweetId = parentTweetId,
                            enableCoordinator = enableCoordinator,
                            containerTopY = containerTopY,
                            allMediaItems = mediaItems // Pass all items for full screen navigation
                        )
                    }
                }
            }
            }
        }

        // Caption for single-video grid: title or file name (without extension)
        if (limitedMediaList.size == 1) {
            val singleItem = limitedMediaList[0]
            val singleItemType = inferMediaTypeFromAttachment(singleItem)
            if (singleItemType == MediaType.Video || singleItemType == MediaType.HLS_VIDEO) {
                // Prefer tweet title; fallback to file name without extension
                val rawTitle = tweet.title?.takeIf { it.isNotBlank() }
                val fileNameWithoutExt = singleItem.fileName
                    ?.substringBeforeLast('.', missingDelimiterValue = singleItem.fileName)
                    ?.takeIf { it.isNotBlank() }

                val captionText = rawTitle ?: fileNameWithoutExt

                if (!captionText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = captionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Infer media type from attachment properties when backend doesn't provide type
 */
fun inferMediaTypeFromAttachment(attachment: MimeiFileType): MediaType {
    // Check if type is provided and valid
    if (attachment.type != null && attachment.type != MediaType.Unknown) {
        return attachment.type
    }

    // If type is Unknown but we have a valid type, try to infer from other properties
    if (attachment.type == MediaType.Unknown) {
        // Check if it has aspect ratio (indicates video)
        if (attachment.aspectRatio != null && attachment.aspectRatio > 0) {
            return MediaType.Video
        }
    }

    // Check filename extension
    val fileName = attachment.fileName?.lowercase() ?: ""

    // Special case for .3gp files - check if they have aspect ratio (video) or not (audio)
    if (fileName.endsWith(".3gp")) {
        return if (attachment.aspectRatio != null && attachment.aspectRatio > 0) {
            MediaType.Video
        } else {
            MediaType.Audio
        }
    }

    val inferredType = when {
        fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                fileName.endsWith(".png") || fileName.endsWith(".gif") ||
                fileName.endsWith(".webp") || fileName.endsWith(".bmp") -> MediaType.Image

        fileName.endsWith(".mp4") || fileName.endsWith(".mov") ||
                fileName.endsWith(".avi") || fileName.endsWith(".mkv") ||
                fileName.endsWith(".webm") || fileName.endsWith(".m4v") ||
                fileName.endsWith(".3gpp") -> MediaType.Video

        fileName.endsWith(".m3u8") -> MediaType.HLS_VIDEO

        fileName.endsWith(".mp3") || fileName.endsWith(".wav") ||
                fileName.endsWith(".aac") || fileName.endsWith(".ogg") ||
                fileName.endsWith(".flac") || fileName.endsWith(".m4a") ||
                fileName.endsWith(".wma") || fileName.endsWith(".opus") ||
                fileName.endsWith(".amr") -> MediaType.Audio

        fileName.endsWith(".pdf") -> MediaType.PDF
        fileName.endsWith(".doc") || fileName.endsWith(".docx") -> MediaType.Word
        fileName.endsWith(".xls") || fileName.endsWith(".xlsx") -> MediaType.Excel
        fileName.endsWith(".ppt") || fileName.endsWith(".pptx") -> MediaType.PPT
        fileName.endsWith(".zip") || fileName.endsWith(".rar") ||
                fileName.endsWith(".7z") -> MediaType.Zip

        fileName.endsWith(".txt") -> MediaType.Txt
        fileName.endsWith(".html") || fileName.endsWith(".htm") -> MediaType.Html

        else -> MediaType.Unknown
    }
    return inferredType
}
