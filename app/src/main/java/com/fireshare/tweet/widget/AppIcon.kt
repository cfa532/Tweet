package com.fireshare.tweet.widget

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.User

@Composable
fun AppIcon() {
    Image(
        painter = rememberAsyncImagePainter(R.drawable.ic_app_logo),
        contentDescription = "App Icon",
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}

@Composable
fun UserAvatar(user: User?, size: Int = 40, modifier: Modifier = Modifier) {
    var avatarUrl by remember { mutableStateOf(getMediaUrl(user?.avatar, user?.baseUrl)) }
    LaunchedEffect(user?.avatar) {
        avatarUrl = getMediaUrl(user?.avatar, user?.baseUrl)
    }
    avatarUrl?.let {
        ImageViewer(
            it,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape),
            isPreview = true,
            imageSize = size
        )
    } ?: Image(
        painter = painterResource(id = R.drawable.ic_user_avatar),
        contentDescription = "Placeholder Avatar",
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}