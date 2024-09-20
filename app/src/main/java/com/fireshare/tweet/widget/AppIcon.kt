package com.fireshare.tweet.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter.State.Empty.painter
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User

@Composable
fun AppIcon() {
    Image(
        painter = rememberAsyncImagePainter(R.drawable.ic_app_icon),
        contentDescription = "App Icon",
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}

@Composable
//fun UserAvatar(user: User?, size: Int = 40) {
//    val avatarUrl = remember { getMediaUrl(user?.avatar, user?.baseUrl).toString() }
//    val painter = rememberAsyncImagePainter(
//        model = avatarUrl,
////        placeholder = rememberAsyncImagePainter(R.drawable.ic_placeholder),
////        error = rememberAsyncImagePainter(R.drawable.ic_error)
//    )
//
//    Image(
//        painter = painter,
//        contentDescription = "User Avatar",
//        modifier = Modifier
//            .size(size.dp)
//            .clip(CircleShape)
//            .background(Color.Gray),
//        contentScale = ContentScale.Crop
//    )
//}

fun UserAvatar(user: User?, size: Int = 40) {
    val avatarUrl = getMediaUrl(user?.avatar, user?.baseUrl).toString()
    ImageViewer(avatarUrl, modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(Color.Gray),
        isPreview = true, imageSize = 50)
}