package com.fireshare.tweet.widget

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaBrowser(navController: NavController, mediaItems: List<MediaItem>, startIndex: Int) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { mediaItems.size })
    val cachedImageUrls = remember { mutableStateMapOf<String, String>() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(32.dp) // Adjust the width as needed
                    .align(Alignment.CenterVertically)
            ) {
                IconButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val mediaItem = mediaItems[page]
                val mid = mediaItem.url.substringAfterLast('/')
                val cachedPath = cachedImageUrls[mid]

                if (mediaItem.type == MediaType.Video) {
                    // Directly play the video without caching
                    VideoPlayer(uri = Uri.parse(mediaItem.url))
                } else {
                    if (cachedPath != null) {
                        loadImageFromCache(cachedPath)?.let {
                            Image(
                                painter = BitmapPainter(it),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        LaunchedEffect(mid) {
                            coroutineScope.launch {
                                val downloadedPath = try {
                                    withContext(Dispatchers.IO) {
                                        downloadFullImageToCache(
                                            context,
                                            mediaItem.url
                                        )
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                                if (downloadedPath != null) {
                                    cachedImageUrls[mid] = downloadedPath
                                }
                            }
                        }
                    }
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