package us.fireshare.tweet.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        painter = painterResource(R.drawable.ic_app_logo),
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
 * @param enableLongPress Whether to enable long press functionality
 * @param onClick Optional click handler
 */
@Composable
fun UserAvatar(
    user: User,
    size: Int = 40,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val mid = user.avatar ?: ""
    var loadState by remember(mid) { mutableStateOf(AvatarLoadState()) }
    
    LaunchedEffect(key1 = user.avatar) {
        val newAvatarUrl = getMediaUrl(user.avatar, user.baseUrl)
        loadState = loadState.copy(avatarUrl = newAvatarUrl)
        
        if (mid.isNotEmpty()) {
            try {
                loadState = loadState.copy(isLoading = true, hasError = false)
                
                // Try to load from cache first
                val cachedBitmap = ImageCacheManager.getCachedImage(context, mid)
                
                // If not cached, download and cache
                if (cachedBitmap == null && !newAvatarUrl.isNullOrEmpty()) {
                    val downloadedBitmap = ImageCacheManager.loadImage(context, newAvatarUrl, mid)
                    
                    if (downloadedBitmap == null) {
                        loadState = loadState.copy(isLoading = false, hasError = true)
                        Timber.e("UserAvatar - Failed to load avatar: $newAvatarUrl")
                    } else {
                        loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false)
                    }
                } else {
                    loadState = loadState.copy(bitmap = cachedBitmap, isLoading = false)
                }
            } catch (e: Exception) {
                loadState = loadState.copy(isLoading = false, hasError = true)
                Timber.e("UserAvatar - Error loading avatar: $e")
            }
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
            Box(
                modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Fallback to initials
            Box(
                modifier = modifier
                    .background(MaterialTheme.colorScheme.primary)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.name?.firstOrNull()?.uppercase() ?: "U",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    } else {
        // Show fallback with user initials
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.primary)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user.name?.firstOrNull()?.uppercase() ?: "U",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

