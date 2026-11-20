package us.fireshare.tweet.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import us.fireshare.tweet.navigation.NavTweet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUserState
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.ui.theme.ThemeManager
import us.fireshare.tweet.viewmodel.UserViewModel
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.widget.SelectableText
import us.fireshare.tweet.widget.VideoManager

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemSettings(navController: NavController, appUserViewModel: UserViewModel) {
    val appUser by appUserState.collectAsState()
    val context = LocalContext.current
    var isDeletingAccount by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    title = {
                        Text(stringResource(R.string.settings))
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!navController.popBackStack()) {
                                navController.navigate(NavTweet.TweetFeed) {
                                    launchSingleTop = true
                                    popUpTo(NavTweet.Settings) {
                                        inclusive = true
                                    }
                                }
                            }
                        })
                        {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                Text(
                    text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            },
        ) { innerPadding ->
            var currentThemeMode by remember { mutableStateOf(HproseInstance.preferenceHelper.getThemeMode()) }
            var showDialog by remember { mutableStateOf(false) }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item {
                    var showThemeDialog by remember { mutableStateOf(false) }

                    SettingsRow(
                        title = stringResource(R.string.settings_theme),
                        icon = Icons.Outlined.Palette,
                        supportingText = when (currentThemeMode) {
                            "system" -> stringResource(R.string.theme_system)
                            "dark" -> stringResource(R.string.theme_dark)
                            else -> stringResource(R.string.theme_light)
                        },
                        trailingIcon = Icons.Outlined.ExpandMore,
                        onClick = { showThemeDialog = true }
                    )

                    if (showThemeDialog) {
                        AlertDialog(
                            onDismissRequest = { showThemeDialog = false },
                            title = { Text(stringResource(R.string.theme_mode)) },
                            text = {
                                Column(Modifier.selectableGroup()) {
                                    listOf(
                                        "system" to R.string.theme_system,
                                        "light" to R.string.theme_light,
                                        "dark" to R.string.theme_dark
                                    ).forEach { (mode, label) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .selectable(
                                                    selected = currentThemeMode == mode,
                                                    role = Role.RadioButton,
                                                    onClick = {
                                                        currentThemeMode = mode
                                                        HproseInstance.preferenceHelper.setThemeMode(mode)
                                                        ThemeManager.updateThemeMode(mode)
                                                        showThemeDialog = false
                                                    }
                                                )
                                                .heightIn(min = 48.dp)
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = currentThemeMode == mode,
                                                onClick = null
                                            )
                                            Spacer(Modifier.width(16.dp))
                                            Text(stringResource(label))
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showThemeDialog = false }) {
                                    Text(stringResource(R.string.close))
                                }
                            }
                        )
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }

                item {
                    var showCacheInfo by remember { mutableStateOf(false) }
                    var isCachedCleared by remember { mutableStateOf(false) }

                    // Keep cache disclosure, statistics, and cleanup on one shared surface.
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        SettingsRow(
                            title = stringResource(R.string.cache_information),
                            icon = Icons.Outlined.Storage,
                            trailingIcon = if (showCacheInfo) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            onClickLabel = stringResource(
                                if (showCacheInfo) R.string.settings_hide_cache_info else R.string.settings_show_cache_info
                            ),
                            onClick = { showCacheInfo = !showCacheInfo }
                        )

                        if (showCacheInfo) {
                            var tweetCacheStats by remember {
                                mutableStateOf(TweetCacheManager.CacheStats(0, 0, 0))
                            }
                            var userCacheStats by remember {
                                mutableStateOf(TweetCacheManager.UserCacheStats(0, 0, 0, 0))
                            }
                            var videoCacheBytes by remember { mutableStateOf(0L) }
                            var activePlayers by remember { mutableStateOf(0) }
                            var appMemoryBytes by remember { mutableStateOf(0L) }
                            var imageCacheBytes by remember { mutableStateOf(0L) }

                            LaunchedEffect(isCachedCleared) {
                                tweetCacheStats = TweetCacheManager.getCacheStats()
                                userCacheStats = TweetCacheManager.getUserCacheStats()
                                videoCacheBytes = VideoManager.getCache(context).cacheSpace
                                activePlayers = VideoManager.getActiveVideoCount()
                                val runtime = Runtime.getRuntime()
                                appMemoryBytes = runtime.totalMemory() - runtime.freeMemory()
                                imageCacheBytes = ImageCacheManager.getMemoryCacheUsageBytes()
                            }

                            Column(
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                            ) {
                                CacheStatRow(
                                    stringResource(R.string.settings_cached_tweets),
                                    stringResource(
                                        R.string.settings_tweet_counts,
                                        tweetCacheStats.memoryCacheSize,
                                        tweetCacheStats.databaseCacheSize
                                    )
                                )
                                CacheStatRow(
                                    stringResource(R.string.settings_cached_users),
                                    stringResource(
                                        R.string.settings_user_counts,
                                        userCacheStats.totalUsers,
                                        userCacheStats.validUsers
                                    )
                                )
                                CacheStatRow(
                                    stringResource(R.string.settings_video_cache),
                                    stringResource(R.string.settings_size_mb, videoCacheBytes / (1024 * 1024))
                                )
                                CacheStatRow(
                                    stringResource(R.string.settings_active_players),
                                    activePlayers.toString()
                                )
                                CacheStatRow(
                                    stringResource(R.string.settings_app_memory),
                                    stringResource(R.string.settings_size_mb, appMemoryBytes / (1024 * 1024))
                                )
                                CacheStatRow(
                                    stringResource(R.string.settings_image_memory),
                                    stringResource(R.string.settings_size_mb, imageCacheBytes / (1024 * 1024))
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )

                        if (isCachedCleared) {
                            Text(
                                stringResource(R.string.cache_cleared_success),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            )
                        }

                        TextButton(
                            onClick = {
                                if (isCachedCleared) {
                                    isCachedCleared = false
                                } else {
                                    appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                                        TweetCacheManager.clearAllCachedTweets()
                                        VideoManager.clearVideoCache(context)
                                        TweetCacheManager.clearAllCachedUsers()
                                        Tweet.clearAllInstances()
                                        withContext(Dispatchers.Main) {
                                            VideoManager.releaseAllVideos()
                                        }
                                        ImageCacheManager.clearAllCachedImages(context)
                                        isCachedCleared = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(horizontal = 8.dp)
                                .heightIn(min = 48.dp)
                        ) {
                            Text(
                                stringResource(
                                    if (isCachedCleared) R.string.close else R.string.settings_clear_cache
                                )
                            )
                        }
                    }
                }

                item {
                    var showLogoutDialog by remember { mutableStateOf(false) }
                    var showDeleteDialog by remember { mutableStateOf(false) }

                    if (appUser.isGuest()) {
                        SettingsRow(
                            title = stringResource(R.string.login),
                            icon = Icons.AutoMirrored.Outlined.Login,
                            onClick = { navController.navigate(NavTweet.Login) }
                        )
                    } else {
                        SettingsRow(
                            title = stringResource(R.string.settings_logout),
                            icon = Icons.AutoMirrored.Outlined.Logout,
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = { showLogoutDialog = true }
                        )
                        SettingsRow(
                            title = stringResource(R.string.settings_delete_account),
                            icon = Icons.Outlined.DeleteOutline,
                            contentColor = MaterialTheme.colorScheme.primary,
                            onClick = { showDeleteDialog = true }
                        )
                    }

                    if (showLogoutDialog) {
                        AlertDialog(
                            onDismissRequest = { showLogoutDialog = false },
                            title = { Text(stringResource(R.string.logout)) },
                            text = { Text(stringResource(R.string.logout_confirmation)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showLogoutDialog = false
                                    appUserViewModel.viewModelScope.launch {
                                        appUserViewModel.logout {
                                            navController.navigate(NavTweet.TweetFeed)
                                        }
                                    }
                                }) { Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showLogoutDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }

                    if (showDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            title = { Text(stringResource(R.string.delete_account)) },
                            text = { Text(stringResource(R.string.delete_account_confirmation)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showDeleteDialog = false
                                    isDeletingAccount = true
                                    appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                                        appUserViewModel.deleteAccount(
                                            onDone = {
                                                appUserViewModel.viewModelScope.launch(Dispatchers.Main) {
                                                    isDeletingAccount = false
                                                    navController.navigate(NavTweet.TweetFeed)
                                                }
                                            },
                                            onError = { isDeletingAccount = false }
                                        )
                                    }
                                }) { Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }
                }

                item {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    SettingsRow(
                        title = stringResource(R.string.privacy_policy),
                        icon = Icons.Outlined.Shield,
                        trailingIcon = Icons.Outlined.ChevronRight,
                        onClick = { showDialog = true }
                    )
                    if (appUser.isGuest()) {
                        SelectableText(
                            text = appUser.baseUrl ?: "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 8.dp)
                        )
                    }
                }
            }

            if (showDialog) {
                BasicAlertDialog(
                    onDismissRequest = { showDialog = false }
                ) {
                    ConstraintLayout(
                        modifier = Modifier
                            .width(500.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White),
                    ) {
                        val (_, button) = createRefs()
                        LazyColumn(
                            modifier = Modifier
                                .padding(8.dp)
                                .height(800.dp)
                        ) {
                            item {
                                Text(
                                    stringResource(R.string.privacy_policy_body),
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                TextButton(
                                    onClick = { showDialog = false },
                                    modifier = Modifier.constrainAs(button) {
                                        bottom.linkTo(parent.bottom)
                                        centerHorizontallyTo(parent)
                                    },
                                ) {
                                    Text(
                                        stringResource(R.string.confirm),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Full-screen spinner overlay while deleting account
        if (isDeletingAccount) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .pointerInput(Unit) { /* consume all touch events */ },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.deleting_account),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    supportingText: String? = null,
    trailingIcon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClickLabel: String? = null
) {
    ListItem(
        modifier = Modifier.clickable(
            role = Role.Button,
            onClickLabel = onClickLabel,
            onClick = onClick
        ),
        headlineContent = { Text(title) },
        supportingContent = if (supportingText != null) {
            { Text(supportingText) }
        } else null,
        leadingContent = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        },
        trailingContent = if (trailingIcon != null) {
            { Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(24.dp)) }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = contentColor,
            leadingIconColor = if (contentColor != MaterialTheme.colorScheme.onSurface) {
                contentColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun CacheStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}
