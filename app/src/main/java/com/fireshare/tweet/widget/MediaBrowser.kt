package com.fireshare.tweet.widget

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.fireshare.tweet.navigation.NavTwee
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.widget.Gadget.detectMimeTypeFromHeader
import com.fireshare.tweet.widget.Gadget.downloadFileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaBrowser(navController: NavController, mediaItems: List<MediaItem>, startIndex: Int) {
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { mediaItems.size })
    val mediaType = remember { mutableStateOf(MediaType.Image) }
    var showControls by remember { mutableStateOf(false) } // State for showing/hiding controls

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val mediaItem = mediaItems[page]
            if (mediaItem.type == MediaType.Unknown) {
                val coroutineScope = rememberCoroutineScope()
                LaunchedEffect(mediaItem.url.substringAfterLast("/")) {
                    coroutineScope.launch {
                        val header = withContext(Dispatchers.IO) {
                            downloadFileHeader(mediaItem.url)
                        }
                        detectMimeTypeFromHeader(header)?.let {
                            mediaItem.type = when (it.substringBefore("/")) {
                                "image" -> MediaType.Image
                                "audio" -> MediaType.Audio
                                "video" -> MediaType.Video
                                else -> MediaType.Unknown
                            }
                            mediaType.value = mediaItem.type
                        }
                    }
                }
            }
            // Display the media item based on its type and recompose
            when (mediaType.value) {
                MediaType.Video, MediaType.Audio -> {
                    // Consider caching for larger videos
                    VideoPlayer(uri = Uri.parse(mediaItem.url))
                }

                else -> {
                    ImageViewer(mediaItem.url, isPreview = false)
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { showControls = !showControls }
    )
    if (showControls) {
        Column(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = {
                    navController.navigate(NavTweet.TweetFeed) },
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.Start)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f)) // Push buttons to the bottom

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                IconButton(onClick = { /* Handle like action */ }) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "Like",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { /* Handle bookmark action */ }) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = "Bookmark",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { /* Handle share action */ }) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun VideoPlayer(uri: Uri) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            VideoView(context).apply {
                setVideoURI(uri)
                setOnPreparedListener { mediaPlayer: MediaPlayer ->
                    mediaPlayer.isLooping = false
                    start()
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}