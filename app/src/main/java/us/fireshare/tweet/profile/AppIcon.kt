package us.fireshare.tweet.profile

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.TweetApplication.Companion.foregroundImageRoutes
import us.fireshare.tweet.datamodel.BlackList
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.widget.ImageCacheManager

/**
 * AppIcon displays the app logo (singleton painter resource)
 */
@Composable
fun AppIcon() {
    Image(
        painter = painterResource(R.drawable.ic_splash),
                        contentDescription = stringResource(R.string.app_icon),
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}

/**
 * UserAvatar displays user avatar with fallback to initials
 * @param user User object containing avatar information
 * @param size Size of the avatar in dp
 * @param onClick Optional click handler
 */
@Composable
fun UserAvatar(
    user: User,
    size: Int = 40,
    onClick: (() -> Unit)? = null,
    useOriginalColors: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val isOnline by HproseInstance.isOnline.collectAsState()
    val imageRoutes by foregroundImageRoutes.collectAsState()
    val canDownload = lifecycleState.isAtLeast(Lifecycle.State.STARTED) && isOnline && imageRoutes != null
    val ownerId = user.mid
    val avatarMid = user.avatar
    val avatarBaseUrl = user.baseUrl
    val avatarCacheVersion by remember(avatarMid) {
        ImageCacheManager.getImageCacheVersionFlow(avatarMid.orEmpty())
    }.collectAsState()

    var bitmap by remember(avatarMid) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(ownerId, avatarMid, avatarBaseUrl, avatarCacheVersion, canDownload, imageRoutes) {
        if (avatarMid.isNullOrEmpty() || bitmap != null) return@LaunchedEffect

        // The full-size original is reserved for the profile's avatar dialog.
        ImageCacheManager.getCachedImage(context, avatarMid)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        if (!canDownload) return@LaunchedEffect

        try {
            BlackList.resetSessionFailures(avatarMid)
            bitmap = ImageCacheManager.downloadAndCacheImage(
                context = context,
                imageUrl = getMediaUrl(avatarMid, avatarBaseUrl?.takeIf { it.isNotBlank() }),
                mid = avatarMid,
                isAvatar = true,
                ownerId = ownerId
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("UserAvatar").e(e, "Could not load avatar $avatarMid")
        }
    }

    val modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .let { mod ->
            if (onClick != null) {
                mod.clickable { onClick() }
            } else {
                mod
            }
        }

    val avatar = bitmap
    if (avatar != null) {
        Image(
            bitmap = avatar.asImageBitmap(),
            contentDescription = stringResource(R.string.user_avatar),
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Image(
            painter = painterResource(R.drawable.manyone),
            contentDescription = stringResource(R.string.user_avatar),
            contentScale = ContentScale.Crop,
            colorFilter = if (useOriginalColors) null else ColorFilter.tint(Color(0xFFB0B0B0), BlendMode.Screen),
            modifier = modifier
        )
    }
}
