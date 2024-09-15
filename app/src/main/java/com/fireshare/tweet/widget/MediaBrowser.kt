package com.fireshare.tweet.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.navigation.compose.rememberNavController
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaBrowser(navController: NavController, mediaUrl: String) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cachedImageUrls = remember { mutableStateMapOf<String, String>() }
    val cachedPath = cachedImageUrls[mediaUrl]

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                        withContext(Dispatchers.IO) {
                            downloadImageToCache(context, mediaUrl)
                        }
                    } catch (e: Exception) {
                        null
                    }
                    if (downloadedPath != null) {
                        cachedImageUrls[mediaUrl] = downloadedPath
                    }
                }
            }
        }

        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clickable { navController.popBackStack() }
        )
    }
}