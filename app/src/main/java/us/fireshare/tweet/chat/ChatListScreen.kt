package us.fireshare.tweet.chat

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.ChatSession
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.service.BadgeStateManager
import us.fireshare.tweet.service.SystemNotificationManager
import us.fireshare.tweet.viewmodel.ChatListViewModel
import us.fireshare.tweet.viewmodel.ChatViewModel
import us.fireshare.tweet.viewmodel.UserViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel
) {
    val chatSessions by viewModel.chatSessions.collectAsState()
    val navController = LocalNavController.current
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val debounceTime = 500L
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // State for showing the followings dialog
    var showFollowingsDialog by remember { mutableStateOf(false) }
    
    // UserViewModel for getting followings
    val userViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory> { 
        it.create(appUser.mid) 
    }
    val followings by userViewModel.followings.collectAsState()

    LaunchedEffect(Unit) {
        // Clear badge when entering chat list
        BadgeStateManager.clearBadge()
        
        // Clear system notifications when entering chat list
        SystemNotificationManager.clearNotification(context, 1001)
        
        // Set up callback to update badge when new messages are found
        viewModel.setOnNewMessageCallback { count ->
            BadgeStateManager.updateBadgeCount(count)
        }
        
        // Load followings for the dialog
        withContext(Dispatchers.IO) {
            userViewModel.refreshFollowingsAndFans()
        }
        
        // Initial message preview
        withContext(Dispatchers.IO) {
            viewModel.previewMessages()
        }
        
        // Add a small delay before starting periodic fetching to avoid immediate recompositions
        delay(2000)
        
        while (true) {
            try {
                delay(60_000)
                withContext(Dispatchers.IO) {
                    viewModel.previewMessages()
                }
            } catch (e: Exception) {
                // Log error but don't let it break the periodic fetching
                Timber.e("ChatListScreen - Error in periodic message preview: ${e.message}")
                // Add longer delay on error to prevent rapid retries
                delay(30_000)
            }
        }
    }

    // Filter out chat sessions with no messages
    val filteredChatSessions = remember(chatSessions) {
        chatSessions.filter { session ->
            val message = session.lastMessage
            // Include session if it has content or attachments (exclude empty/placeholder messages)
            val hasContent = !message.content.isNullOrBlank()
            val hasAttachments = !message.attachments.isNullOrEmpty()
            // Filter out placeholder messages like "New chat started"
            val isPlaceholder = message.content == "New chat started"
            (hasContent || hasAttachments) && !isPlaceholder
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    UserAvatar(user = appUser, size = 40)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // manually prevent fast continuous click of a button
                        val currentTime = SystemClock.elapsedRealtime()
                        if (currentTime - lastClickTime > debounceTime) {
                            navController.popBackStack()
                        }
                    } ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            // manually prevent fast continuous click of a button
                            val currentTime = SystemClock.elapsedRealtime()
                            if (currentTime - lastClickTime > debounceTime) {
                                showFollowingsDialog = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.add_chat),
                        )
                    }
                },
            )
        },
        bottomBar = { BottomNavigationBar(navController = navController, selectedIndex = 1) }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize() // Ensure Surface fills the available space
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.Top
            ) {
                items(filteredChatSessions, key = {it.id}) { chatSession ->
                    ChatSession(chatSession, navController, viewModel)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 0.8.dp).alpha(0.7f),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                item {
                    if (filteredChatSessions.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.no_chat),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = { showFollowingsDialog = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.add_chat),
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(text = stringResource(R.string.start_new_chat))
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Followings selection dialog
    if (showFollowingsDialog) {
        FollowingsSelectionDialog(
            followings = followings,
            onUserSelected = { selectedUserId ->
                // Create a new in-memory chat session
                viewModel.createInMemoryChatSession(selectedUserId)
                // Navigate to the chat screen
                navController.navigate(NavTweet.ChatBox(selectedUserId))
                // Close the dialog
                showFollowingsDialog = false
            },
            onDismiss = { 
                showFollowingsDialog = false
            }
        )
    }
}

@Composable
fun FollowingsSelectionDialog(
    followings: List<String>,
    onUserSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.select_user_to_chat))
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                items(followings) { userId ->
                    ChatUserItem(
                        userId = userId,
                        onUserSelected = onUserSelected
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ChatUserItem(
    userId: String,
    onUserSelected: (String) -> Unit
) {
    val userViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
        key = userId
    ) { factory ->
        factory.create(userId)
    }
    val user by userViewModel.user.collectAsState()
    
    // Proactively load user data for every user ID
    LaunchedEffect(userId) {
        withContext(Dispatchers.IO) {
            userViewModel.getUser()
        }
    }
    
    // Don't show invalid users (guests or users without username)
    if (user.isGuest() || user.username.isNullOrBlank()) {
        return
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserSelected(userId) }
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(user = user, size = 40)
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
        ) {
            Text(
                text = user.name ?: "No One",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "@${user.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun ChatSession(
    chatSession: ChatSession,
    navController: NavController,
    chatListViewModel: ChatListViewModel,
    viewModel: ChatViewModel = hiltViewModel<ChatViewModel, ChatViewModel.ChatViewModelFactory>(
        key = chatSession.receiptId
    ) {factory -> factory.create(chatSession.receiptId)}
) {
    val chatMessage = chatSession.lastMessage
    val user by viewModel.receipt.collectAsState()
    var showDeleteButton by remember { mutableStateOf(false) }

    // Auto-hide delete button after 3 seconds
    LaunchedEffect(showDeleteButton) {
        if (showDeleteButton) {
            delay(3000)
            showDeleteButton = false
        }
    }

    Row(
        modifier = Modifier
            .padding(8.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (showDeleteButton) {
                            showDeleteButton = false
                        } else {
                            user.mid.let { navController.navigate(NavTweet.ChatBox(it)) }
                        }
                    },
                    onLongPress = {
                        showDeleteButton = true
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = {
                    user.mid.let {
                        navController.navigate(NavTweet.UserProfile(it))
                    }
                })
        ) {
            UserAvatar(user = user)

            // show badge of new incoming message
            if (chatSession.hasNews) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = (6).dp, y = (-4).dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Email, contentDescription = stringResource(R.string.mail),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .padding(start = 4.dp)
                .weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${user.name}@${user.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(
                        Date(
                            chatMessage.timestamp
                        )
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Text(
                text = if (chatMessage.content.isNullOrBlank() && !chatMessage.attachments.isNullOrEmpty()) {
                    if (chatMessage.authorId == appUser.mid) {
                        stringResource(R.string.attachment_sent)
                    } else {
                        stringResource(R.string.attachment_received)
                    }
                } else {
                    chatMessage.content ?: ""
                },
                style = MaterialTheme.typography.bodyLarge
            )
        }
        
        // Delete button that appears on long press
        if (showDeleteButton) {
            Box(
                modifier = Modifier
                    .clickable {
                        chatListViewModel.deleteChatSession(chatSession.receiptId)
                        showDeleteButton = false
                    }
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_chat),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}