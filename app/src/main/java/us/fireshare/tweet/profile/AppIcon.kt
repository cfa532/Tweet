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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.widget.ImageCacheManager

/**
 * AppIcon displays the app logo (singleton painter resource)
 */
@Composable
fun AppIcon() {
    Image(
        painter = painterResource(R.drawable.ic_app_logo),
        contentDescription = "App Icon",
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
    enableLongPress: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val mid = user.avatar ?: ""
    var cachedBitmap by remember(mid) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var avatarUrl by remember(key1 = user.avatar) {
        mutableStateOf(getMediaUrl(user.avatar, user.baseUrl))
    }
    
    LaunchedEffect(key1 = user.avatar) {
        avatarUrl = getMediaUrl(user.avatar, user.baseUrl)
        
        if (mid.isNotEmpty()) {
            try {
                isLoading = true
                loadError = false
                
                // Try to load from cache first
                cachedBitmap = ImageCacheManager.getCachedImage(context, mid)
                
                // If not cached, download and cache
                if (cachedBitmap == null && !avatarUrl.isNullOrEmpty()) {
                    Timber.d("UserAvatar - Loading avatar from URL: $avatarUrl")
                    cachedBitmap = ImageCacheManager.loadImage(context, avatarUrl!!, mid)
                    
                    if (cachedBitmap == null) {
                        loadError = true
                        Timber.e("UserAvatar - Failed to load avatar: $avatarUrl")
                    }
                }
            } catch (e: Exception) {
                loadError = true
                Timber.e("UserAvatar - Error loading avatar: $e")
            } finally {
                isLoading = false
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

    if (cachedBitmap != null) {
        // Show cached avatar image
        Image(
            bitmap = cachedBitmap!!.asImageBitmap(),
            contentDescription = "User Avatar",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else if (!avatarUrl.isNullOrEmpty() && !loadError) {
        // Show loading state or try to load from URL
        if (isLoading) {
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

