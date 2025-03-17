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
import us.fireshare.tweet.datamodel.isGuest
import us.fireshare.tweet.widget.ImageViewer

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
fun UserAvatar(
    modifier: Modifier = Modifier,
    user: User,
    size: Int = 40
) {
    var avatarUrl by remember(user.avatar) {
        mutableStateOf(getMediaUrl(user.avatar, user.baseUrl))
    }
    LaunchedEffect(user.avatar) {
        avatarUrl = getMediaUrl(user.avatar, user.baseUrl)
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
        painter = painterResource(id =
            if (user.isGuest()) R.drawable.ic_splash
            else R.drawable.ic_user_avatar
        ),
        contentDescription = "Placeholder Avatar",
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}