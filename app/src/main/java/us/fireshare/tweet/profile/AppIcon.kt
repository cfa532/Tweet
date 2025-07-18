package us.fireshare.tweet.profile

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
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.widget.ImageViewer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext

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
fun SimpleAvatar(
    modifier: Modifier = Modifier,
    user: User,
    size: Int = 40
) {
    var avatarUrl by remember(key1 = user.avatar) {
        mutableStateOf(getMediaUrl(user.avatar, user.baseUrl))
    }
    LaunchedEffect(key1 = user.avatar) {
        avatarUrl = getMediaUrl(user.avatar, user.baseUrl)
    }
    
    // Use AsyncImage directly instead of ImageViewer to avoid touch event conflicts
    avatarUrl?.let {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(it)
                .crossfade(true)
                .build(),
            contentDescription = "User Avatar",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape)
        )
    } ?: Image(
        painter = painterResource(id = R.drawable.ic_splash),
        contentDescription = "Placeholder Avatar",
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}

@Composable
fun UserAvatar(
    modifier: Modifier = Modifier,
    user: User,
    size: Int = 40,
    enableLongPress: Boolean = false  // Disable long press by default to prevent conflicts with clickable parents
) {
    var avatarUrl by remember(key1 = user.avatar) {
        mutableStateOf(getMediaUrl(user.avatar, user.baseUrl))
    }
    LaunchedEffect(key1 = user.avatar) {
        avatarUrl = getMediaUrl(user.avatar, user.baseUrl)
    }
    avatarUrl?.let {
        ImageViewer(
            it,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape),
            imageSize = size,
            enableLongPress = enableLongPress
        )
    } ?: Image(
        painter = painterResource(id = R.drawable.ic_splash),
        contentDescription = "Placeholder Avatar",
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}