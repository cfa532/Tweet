package com.fireshare.tweet.widget

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.zIndex
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
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier
                .size(48.dp)
                .align(Alignment.TopStart)
                .zIndex(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }

        HorizontalPager(
            verticalAlignment = Alignment.Top,
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp)
                .zIndex(0.2f)
        ) { page ->
            val mediaUrl = mediaItems[page].url
            val cachedPath = cachedImageUrls[mediaUrl]

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
                LaunchedEffect(mediaUrl) {
                    coroutineScope.launch {
                        val downloadedPath = try {
                            withContext(Dispatchers.IO) { downloadImageToCache(context, mediaUrl) }
                        } catch (e: Exception) {
                            null
                        }
                        if (downloadedPath != null) {
                            cachedImageUrls[mediaUrl] = downloadedPath
                        }
                    }
                }
            }
        }
    }
}

