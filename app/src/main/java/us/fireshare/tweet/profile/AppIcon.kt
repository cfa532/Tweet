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
import androidx.compose.ui.graphics.asImageBitmap
import coil.imageLoader
import coil.request.SuccessResult
import us.fireshare.tweet.widget.ImageCacheManager

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
    val context = LocalContext.current
    val mid = user.avatar ?: ""
    var cachedBitmap by remember(mid) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var avatarUrl by remember(key1 = user.avatar) {
        mutableStateOf(getMediaUrl(user.avatar, user.baseUrl))
    }
    LaunchedEffect(key1 = user.avatar) {
        avatarUrl = getMediaUrl(user.avatar, user.baseUrl)
        cachedBitmap = if (mid.isNotEmpty()) ImageCacheManager.getCachedImage(context, mid) else null
        if (cachedBitmap == null && mid.isNotEmpty() && !isLoading) {
            isLoading = true
            val request = ImageRequest.Builder(context)
                .data(avatarUrl)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bmp != null) {
                    ImageCacheManager.cacheImage(context, mid, bmp)
                    cachedBitmap = bmp
                }
            }
            isLoading = false
        }
    }
    if (cachedBitmap != null) {
        Image(
            bitmap = cachedBitmap!!.asImageBitmap(),
            contentDescription = "User Avatar",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape)
        )
    } else if (!avatarUrl.isNullOrEmpty()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(avatarUrl)
                .crossfade(true)
                .build(),
            contentDescription = "User Avatar",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape)
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_splash),
            contentDescription = "Placeholder Avatar",
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun UserAvatar(
    modifier: Modifier = Modifier,
    user: User,
    size: Int = 40,
    enableLongPress: Boolean = false  // Disable long press by default to prevent conflicts with clickable parents
) {
    val context = LocalContext.current
    val mid = user.avatar ?: ""
    var cachedBitmap by remember(mid) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var avatarUrl by remember(key1 = user.avatar) {
        mutableStateOf(getMediaUrl(user.avatar, user.baseUrl))
    }
    LaunchedEffect(key1 = user.avatar) {
        avatarUrl = getMediaUrl(user.avatar, user.baseUrl)
        cachedBitmap = if (mid.isNotEmpty()) ImageCacheManager.getCachedImage(context, mid) else null
        if (cachedBitmap == null && mid.isNotEmpty() && !isLoading) {
            isLoading = true
            val request = ImageRequest.Builder(context)
                .data(avatarUrl)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bmp != null) {
                    ImageCacheManager.cacheImage(context, mid, bmp)
                    cachedBitmap = bmp
                }
            }
            isLoading = false
        }
    }
    if (cachedBitmap != null) {
        Image(
            bitmap = cachedBitmap!!.asImageBitmap(),
            contentDescription = "User Avatar",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape)
        )
    } else if (!avatarUrl.isNullOrEmpty()) {
        ImageViewer(
            avatarUrl!!,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape),
            imageSize = size,
            enableLongPress = enableLongPress
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_splash),
            contentDescription = "Placeholder Avatar",
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    }
}