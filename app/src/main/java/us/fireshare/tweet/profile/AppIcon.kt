package us.fireshare.tweet.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.widget.ImageCacheManager

/**
 * State object for avatar loading to reduce recomposition
 */
data class AvatarLoadState(
    val bitmap: android.graphics.Bitmap? = null,
    val avatarUrl: String? = null,
    val isLoading: Boolean = false,
    val hasError: Boolean = false
)

/**
 * AppIcon displays the app logo (singleton painter resource)
 */
@Composable
fun AppIcon() {
    Image(
        painter = painterResource(R.drawable.ic_splash),
                        contentDescription = stringResource(R.string.app_icon),
        modifier = Modifier
            .size(36.dp)
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
    val avatarMid = user.avatar

    // Watch user.avatar for changes - reset state when avatarMid changes
    var loadState by remember(avatarMid) { mutableStateOf(AvatarLoadState()) }

    // React to avatar changes - reset state immediately when avatarMid changes
    LaunchedEffect(avatarMid) {
        // Reset state immediately when avatar changes to clear old bitmap
        loadState = AvatarLoadState()
        
        if (!avatarMid.isNullOrEmpty()) {
            // Check cache for original image (loadOriginalImageWithScope uses "${mid}_original" key)
            val originalMid = "${avatarMid}_original"
            val cachedBitmap = withContext(Dispatchers.IO) {
                // Try original cache key first, then fallback to regular key
                ImageCacheManager.getCachedImage(context, originalMid) 
                    ?: ImageCacheManager.getCachedImage(context, avatarMid)
            }
            if (cachedBitmap != null) {
                // Use cached avatar immediately
                loadState = AvatarLoadState(bitmap = cachedBitmap, isLoading = false, hasError = false)
            } else {
                // Not cached, need to load it
                Timber.tag("UserAvatar").d("Avatar not cached, loading from server: $avatarMid")
                val avatarUrl = getMediaUrl(avatarMid, user.baseUrl)
                loadState = AvatarLoadState(avatarUrl = avatarUrl, bitmap = null, isLoading = true, hasError = false)

                try {
                    ImageCacheManager.loadOriginalImageWithScope(
                        context = context,
                        imageUrl = avatarUrl ?: "",
                        mid = avatarMid
                    ) { bitmap ->
                        loadState = if (bitmap != null && !bitmap.isRecycled) {
                            AvatarLoadState(avatarUrl = avatarUrl, bitmap = bitmap, isLoading = false, hasError = false)
                        } else {
                            AvatarLoadState(isLoading = false, hasError = true)
                        }
                    }
                } catch (e: Exception) {
                    loadState = AvatarLoadState(isLoading = false, hasError = true)
                    Timber.e("UserAvatar - Error loading avatar: $e")
                }
            }
        } else {
            // No avatar
            loadState = AvatarLoadState()
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

    if (loadState.bitmap != null) {
        // Show cached avatar image
        Image(
            bitmap = loadState.bitmap!!.asImageBitmap(),
            contentDescription = stringResource(R.string.user_avatar),
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else if (!loadState.avatarUrl.isNullOrEmpty() && !loadState.hasError) {
        // Show loading state or try to load from URL
        if (loadState.isLoading) {
            // Show manyone.png icon while loading
            Image(
                painter = painterResource(id = R.drawable.manyone),
                contentDescription = stringResource(R.string.user_avatar),
                contentScale = ContentScale.Crop,
                colorFilter = if (useOriginalColors) null else androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFB0B0B0), blendMode = androidx.compose.ui.graphics.BlendMode.Screen), // Light gray and white or original colors
                modifier = modifier
            )
        } else {
            // Fallback to manyone.png icon
            Image(
                painter = painterResource(id = R.drawable.manyone),
                contentDescription = stringResource(R.string.user_avatar),
                contentScale = ContentScale.Crop,
                colorFilter = if (useOriginalColors) null else androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFB0B0B0), blendMode = androidx.compose.ui.graphics.BlendMode.Screen), // Light gray and white or original colors
                modifier = modifier
            )
        }
    } else {
        // Show default system icon with 50% gray color when avatar is null or empty
        if (user.avatar.isNullOrEmpty() || avatarMid.isNullOrEmpty()) {
            // Use manyone.png as default system icon
            Image(
                painter = painterResource(id = R.drawable.manyone),
                contentDescription = stringResource(R.string.user_avatar),
                contentScale = ContentScale.Crop,
                colorFilter = if (useOriginalColors) null else androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFB0B0B0), blendMode = androidx.compose.ui.graphics.BlendMode.Screen), // Light gray and white or original colors
                modifier = modifier
            )
        } else {
            // Show fallback manyone.png icon for other cases (like loading errors)
            Image(
                painter = painterResource(id = R.drawable.manyone),
                contentDescription = stringResource(R.string.user_avatar),
                contentScale = ContentScale.Crop,
                colorFilter = if (useOriginalColors) null else androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFB0B0B0), blendMode = androidx.compose.ui.graphics.BlendMode.Screen), // Light gray and white or original colors
                modifier = modifier
            )
        }
    }
}

