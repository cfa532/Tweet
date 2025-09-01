package us.fireshare.tweet.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.R
import us.fireshare.tweet.TweetApplication.Companion.applicationScope
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.viewmodel.UserViewModel
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.widget.SelectableText
import us.fireshare.tweet.widget.VideoManager
import us.fireshare.tweet.ui.theme.ThemeManager

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemSettings(navController: NavController, appUserViewModel: UserViewModel) {
    val appUser by appUserViewModel.user.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Column {
                        UserAvatar(user = appUser, size = 36)
                        Text(
                            text = appUser.name ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp, bottom = 0.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { 
                        // Navigate back to the start destination (TweetFeed) to ensure proper navigation
                        navController.popBackStack(navController.graph.startDestinationId, false)
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
    ) { innerPadding ->
        // Shared state variables for save functionality
        var currentThemeMode by remember { mutableStateOf(HproseInstance.preferenceHelper.getThemeMode()) }
        var cloudPort by remember { mutableStateOf(HproseInstance.preferenceHelper.getCloudPort()) }
        var showDialog by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    // Theme settings section
                    var expanded by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(16.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.theme_settings),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it }
                        ) {
                            OutlinedTextField(
                                value = when (currentThemeMode) {
                                    "system" -> stringResource(R.string.theme_system)
                                    "light" -> stringResource(R.string.theme_light)
                                    "dark" -> stringResource(R.string.theme_dark)
                                    else -> stringResource(R.string.theme_light)
                                },
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                shape = RoundedCornerShape(12.dp)
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.theme_system)) },
                                    onClick = {
                                        currentThemeMode = "system"
                                        expanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.theme_light)) },
                                    onClick = {
                                        currentThemeMode = "light"
                                        expanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.theme_dark)) },
                                    onClick = {
                                        currentThemeMode = "dark"
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    // Cloud port section
                    val focusRequester = remember { FocusRequester() }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(16.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cloud_port),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        OutlinedTextField(
                            value = cloudPort ?: "",
                            onValueChange = { cloudPort = it },
                            placeholder = { Text("8010") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                item {
                    // Cache information section
                    var showCacheInfo by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(16.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.cache_information),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            androidx.compose.material3.Switch(
                                checked = showCacheInfo,
                                onCheckedChange = { showCacheInfo = it }
                            )
                        }

                        // Show cache information when expanded
                        if (showCacheInfo) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                thickness = DividerDefaults.Thickness,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )

                            var tweetCacheStats by remember {
                                mutableStateOf(
                                    TweetCacheManager.CacheStats(
                                        0,
                                        0,
                                        0
                                    )
                                )
                            }
                            var userCacheStats by remember {
                                mutableStateOf(
                                    TweetCacheManager.UserCacheStats(
                                        0,
                                        0,
                                        0,
                                        0
                                    )
                                )
                            }
                            var videoCacheStats by remember { mutableStateOf("") }
                            var videoManagerStats by remember { mutableStateOf("") }
                            var videoMemoryStats by remember { mutableStateOf("") }

                            LaunchedEffect(Unit) {
                                tweetCacheStats = TweetCacheManager.getCacheStats()
                                userCacheStats = TweetCacheManager.getUserCacheStats()
                                videoCacheStats = VideoManager.getCacheStats(context)
                                videoManagerStats = VideoManager.getCacheStats()
                                videoMemoryStats = VideoManager.getMemoryStats()
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    stringResource(
                                        R.string.tweet_cache_stats,
                                        tweetCacheStats.memoryCacheSize,
                                        tweetCacheStats.databaseCacheSize
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(
                                        R.string.user_cache_stats,
                                        userCacheStats.totalUsers,
                                        userCacheStats.validUsers
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(R.string.video_cache_stats, videoCacheStats),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(R.string.video_players_stats, videoManagerStats),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(R.string.video_memory_stats, videoMemoryStats),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(
                                        R.string.image_cache_stats,
                                        ImageCacheManager.getMemoryCacheStats()
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                item {
                    // Clear cache section
                    var isCachedCleared by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(16.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(16.dp)
                    ) {
                        if (isCachedCleared) {
                            Text(
                                stringResource(R.string.cache_cleared_success),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }

                        Button(
                            onClick = {
                                if (isCachedCleared) {
                                    isCachedCleared = false
                                } else {
                                    appUserViewModel.viewModelScope.launch(Dispatchers.IO)
                                    {
                                        // Clear all tweet cache (memory + database)
                                        TweetCacheManager.clearAllCachedTweets()
                                        // Clear video cache
                                        VideoManager.clearVideoCache(context)

                                        // Clear all user cache
                                        TweetCacheManager.clearAllCachedUsers()

                                        // Clear tweet instances from memory
                                        Tweet.clearAllInstances()

                                        // Release all video players on main thread
                                        withContext(Dispatchers.Main) {
                                            VideoManager.releaseAllVideos()
                                        }

                                        // Clear image cache
                                        ImageCacheManager.clearAllCachedImages(context)

                                        @Suppress("UnsafeOptInUsageError")
                                        isCachedCleared = true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = if (isCachedCleared) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                contentColor = if (isCachedCleared) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text(
                                if (isCachedCleared) stringResource(R.string.close) else stringResource(
                                    R.string.clear_all_cached_data
                                ),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                item {
                    // Save button section
                    Button(
                        onClick = {
                            // Save theme mode
                            HproseInstance.preferenceHelper.setThemeMode(currentThemeMode)
                            ThemeManager.updateThemeMode(currentThemeMode)

                            // Save cloud port
                            HproseInstance.preferenceHelper.setCloudPort(cloudPort)
                            if (!appUser.isGuest()) {
                                try {
                                    appUser.cloudDrivePort = cloudPort?.toInt() ?: 8010
                                    applicationScope.launch(Dispatchers.IO) {
                                        HproseInstance.setUserData(appUser)
                                    }
                                } catch (e: NumberFormatException) {
                                    Timber.tag("SystemSettings")
                                        .e("Invalid cloudPort value: $cloudPort - ${e.message}")
                                } catch (e: Exception) {
                                    Timber.tag("SystemSettings")
                                        .e("An unexpected error occurred: $e")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            stringResource(R.string.save),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Bottom section with version, user ID, and privacy policy
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(
                    onClick = { showDialog = true },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Privacy Policy")
                }
                SelectableText(
                    text = appUser.mid,
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Version: ${BuildConfig.VERSION_NAME}",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
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
                                "\nPrivacy Policy\n" +
                                        "\n" +
                                        "We operate the Tweet mobile application (the \"App\"). This page informs you of our policies regarding the collection, use, and disclosure of Personal Information when you use our App.\n" +
                                        "\n" +
                                        "Information Collection and Use\n" +
                                        "\n" +
                                        "We collect several types of information for various purposes to provide and improve our App for you.\n" +
                                        "\n" +
                                        "Types of Data Collected\n" +
                                        "\n" +
                                        "Personal Data: While using our App, we may ask you to provide us with certain personally identifiable information, such as your name, email address.\n" +
                                        "\n" +
                                        "Usage Data: We may collect information on how the App is accessed and used, such as your device's Internet Protocol address (e.g., IP address), browser type, browser version, the pages of our App that you visit, the time and date of your visit, and other diagnostic data.\n" +
                                        "\n" +
                                        "Cookies and Tracking Technologies: We use cookies and similar tracking technologies to track the activity on our App and hold certain information.\n" +
                                        "\n" +
                                        "Use of Data\n" +
                                        "\n" +
                                        "We use the collected data for various purposes:\n" +
                                        "\n" +
                                        "To provide and maintain our App\n" +
                                        "To notify you about changes to our App\n" +
                                        "To allow you to participate in interactive features of our App when you choose to do so\n" +
                                        "To provide customer support\n" +
                                        "To gather analysis or valuable information so that we can improve our App\n" +
                                        "To monitor the usage of our App\n" +
                                        "To detect, prevent, and address technical issues\n" +
                                        "Data Security\n" +
                                        "\n" +
                                        "The security of your data is important to us, but remember that no method of transmission over the Internet is 100% secure. While we try our best to protect you data, there is always potential leakholes. Do not disclose sensitive personal information on this App.",
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
                                    "Confirm",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}