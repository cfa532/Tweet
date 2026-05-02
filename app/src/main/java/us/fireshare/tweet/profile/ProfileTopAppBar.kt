package us.fireshare.tweet.profile

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUserState
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.UserViewModel
import us.fireshare.tweet.widget.AdvancedImageViewer
import us.fireshare.tweet.widget.SelectableText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTopAppBar(viewModel: UserViewModel,
                     navController: NavHostController,
                     scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val user by viewModel.user.collectAsState()
    // Observe appUser changes via StateFlow
    val appUser by appUserState.collectAsState()
    val scrollFraction = scrollBehavior?.state?.collapsedFraction ?: 0f
    var showDialog by remember { mutableStateOf(false) }    // show full Avatar image

    // manually prevent fast continuous click of a button
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val debounceTime = 500L

    LargeTopAppBar(
        title = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showDialog) {
                        ImageModalDialog(user,
                            onDismiss = { showDialog = false })
                    }
                    UserAvatar(
                        user = user,
                        size = (80 - (scrollFraction * 20)).toInt(),
                        onClick = { showDialog = true }
                    )
                    Column(
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    ) {
                        Text(
                            text = user.name ?: "No one",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "@" + (user.username ?: "NoOne"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 0.dp)
                        )
                        // Show registration date
                        val date = Date(user.timestamp)
                        val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                        Text(
                            text = "${stringResource(R.string.joined)} ${dateFormat.format(date)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 0.dp, top = 2.dp)
                        )
                    }
                }
                ProfileTopBarButton(viewModel, navController, scrollBehavior)
            }
        },
        navigationIcon = {
            IconButton(onClick = {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastClickTime > debounceTime) {
                    // Navigate back to tweet feed to prevent navigation issues
                    try {
                        // First try to pop back stack
                        if (!navController.popBackStack()) {
                            // If popBackStack returns false, navigate to tweet feed
                            navController.navigate(NavTweet.TweetFeed) {
                                // Clear the back stack to prevent multiple back navigation
                                popUpTo(NavTweet.TweetFeed) { inclusive = true }
                            }
                        }
                    } catch (_: Exception) {
                        // Fallback: navigate to tweet feed
                        navController.navigate(NavTweet.TweetFeed) {
                            popUpTo(NavTweet.TweetFeed) { inclusive = true }
                        }
                    }
                }
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            if (!appUser.isGuest() && appUser.mid != user.mid) {
                IconButton(onClick = {
                    navController.navigate(NavTweet.ChatBox(user.mid))
                }) {
                    Icon(
                        imageVector = Icons.Default.MailOutline,
                        contentDescription = stringResource(R.string.message)
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

/**
 * Display large image of user avatar in a Modal Dialog.
 * */
@Composable
fun ImageModalDialog(
    user: User,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    // Track baseUrl in Compose state so the URL passed to AdvancedImageViewer
    // re-keys (and re-loads) once the fresh IP arrives. user.baseUrl is a plain
    // mutable field — Compose can't observe direct mutations.
    var currentBaseUrl by remember(user.mid) { mutableStateOf(user.baseUrl) }

    // On open, force a fresh IP resolution. Stale IPs keep handing out an
    // unreachable node, and fetchUser/forceRefresh won't re-resolve when the
    // user already has a cached baseUrl. getProviderIP health-checks every
    // returned IP and picks a live one.
    LaunchedEffect(user.mid) {
        try {
            val freshIp = HproseInstance.getProviderIP(user.mid)
            if (!freshIp.isNullOrEmpty()) {
                val newBaseUrl = if (freshIp.startsWith("http://")) freshIp else "http://$freshIp"
                if (newBaseUrl != currentBaseUrl) {
                    user.baseUrl = newBaseUrl
                    user.clearHproseService()
                    currentBaseUrl = newBaseUrl
                    Timber.tag("ImageModalDialog").d("Refreshed baseUrl for ${user.mid}: $newBaseUrl")
                }
            }
        } catch (e: Exception) {
            Timber.tag("ImageModalDialog").w(e, "Failed to refresh baseUrl for ${user.mid}")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                SelectableText(
                    modifier = Modifier.padding(start = 8.dp, end = 56.dp, top = 8.dp, bottom = 8.dp),
                    text = user.mid + "\n" + user.hostIds?.first() + "\n" + currentBaseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                getMediaUrl(user.avatar, currentBaseUrl)?.let {
                    AdvancedImageViewer(it)
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }
        }
    }
}