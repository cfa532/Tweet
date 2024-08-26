package com.fireshare.tweet.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.network.HproseInstance.getMediaUrl

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
fun UserAvatar(author: User?, size: Int = 40) {
    Image(
        painter = rememberAsyncImagePainter(
            getMediaUrl(author?.avatar, author?.baseUrl)
        ),
        contentDescription = "User Avatar",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
    )
}