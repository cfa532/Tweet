package us.fireshare.tweet.chat

import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.ChatViewModel
import us.fireshare.tweet.widget.FullScreenVideoPlayer
import us.fireshare.tweet.widget.Gadget.buildAnnotatedText
import us.fireshare.tweet.widget.VideoManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.R)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val navController = LocalNavController.current
    val receipt by viewModel.receipt.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    
    // Use visibility-based video loading with debouncing
    val visibleMessages by remember(listState, chatMessages) {
        derivedStateOf {
            val currentVisibleIndex = listState.firstVisibleItemIndex
            val visibleItemCount = listState.layoutInfo.visibleItemsInfo.size
            chatMessages.filterIndexed { index, _ ->
                index >= currentVisibleIndex && index < currentVisibleIndex + visibleItemCount
            }
        }
    }

    LaunchedEffect(key1 = Unit) {
        // get unread messages from network
        viewModel.fetchNewMessage()
    }
    
    // Preload videos for visible messages with shorter debouncing for LAN
    LaunchedEffect(visibleMessages, chatMessages.size) {
        delay(100) // 0.1 second debounce for faster LAN response
        visibleMessages.forEach { message ->
            message.attachments?.forEach { attachment ->
                if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                    val mediaUrl = us.fireshare.tweet.HproseInstance.getMediaUrl(attachment.mid, appUser.baseUrl).toString()
                    VideoManager.preloadVideo(context, attachment.mid, mediaUrl, attachment.type)
                }
            }
        }
    }
    val shouldScrollToBottom by viewModel.shouldScrollToBottom.collectAsState()
    
    // Pull-to-refresh state
    val isLoadingOlderMessages by viewModel.isLoadingOlderMessages.collectAsState()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsState()
    
    // Load older messages when scrolling to top
    LaunchedEffect(remember { derivedStateOf { listState.firstVisibleItemIndex } }) {
        if (listState.firstVisibleItemIndex <= 2 && hasMoreMessages && !isLoadingOlderMessages) {
            coroutineScope.launch {
                viewModel.loadOlderMessages()
            }
        }
    }
    
    // Observe toast messages
    val toastMessage by viewModel.toastMessage.collectAsState()
    LaunchedEffect(toastMessage) {
        toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearToastMessage()
        }
    }
    val focusManager = LocalFocusManager.current
    
    // Global state to control full screen display
    var showFullScreen by remember { mutableStateOf(false) }
    var fullScreenAttachment by remember { mutableStateOf<us.fireshare.tweet.datamodel.MimeiFileType?>(null) }
    var fullScreenBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var fullScreenMessage by remember { mutableStateOf<ChatMessage?>(null) } // Store message to access all attachments

    var pendingScrollJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            pendingScrollJob?.cancel()
            pendingScrollJob = null
        }
    }

    fun scrollToBottom() {
        if (chatMessages.isNotEmpty()) {
            pendingScrollJob?.cancel()
            val job = coroutineScope.launch {
                try {
                    listState.scrollToItem(chatMessages.size - 1)
                } finally {
                    pendingScrollJob = null
                }
            }
            pendingScrollJob = job
        }
    }

    LaunchedEffect(Unit) {
        // Assume user read new message when opening this chat screen.
        // Upon opening ChatBox, set new message flag to false in chatSession list.
        viewModel.chatListViewModel?.updateSession(null,
            hasNews = false, receiptId = viewModel.receiptId)
        
        // Add a small delay before starting periodic fetching to avoid immediate recompositions
        delay(1000)
        
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    viewModel.fetchNewMessage()
                }
            } catch (e: Exception) {
                // Log error but don't let it break the periodic fetching
                Timber.e("ChatScreen - Error fetching new messages: ${e.message}")
                // Add longer delay on error to prevent rapid retries
                delay(5000)
            }
            delay(15_000)
        }
    }

    // Scroll to bottom when messages are first loaded
    LaunchedEffect(chatMessages.isNotEmpty()) {
        if (chatMessages.isNotEmpty()) {
            // Add a small delay to ensure the LazyColumn is properly laid out
            delay(100)
            scrollToBottom()
        }
    }
    
    // Scroll to bottom when current user sends a message
    LaunchedEffect(shouldScrollToBottom) {
        if (shouldScrollToBottom && chatMessages.isNotEmpty()) {
            // Add a small delay to ensure the new message is rendered
            delay(50)
            scrollToBottom()
            // Reset the flag
            viewModel.resetScrollToBottomFlag()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(user = receipt, size = 40)
                        Text(
                            text = "${receipt.name} @${receipt.username}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Spacer(modifier = Modifier.fillMaxWidth())
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() })
                    {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
//        bottomBar = { BottomNavigationBar(navController, 1) }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // Hide keyboard when tapping outside
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                    // Removed .pullRefresh(pullRefreshState)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 80.dp), // Increased padding for new input design
                        state = listState
                    ) {
                        // Show loading indicator at the top when loading older messages
                        if (isLoadingOlderMessages) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.loading_older_messages),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }

                        // Show "No more messages" indicator at the top if no more messages
                        if (!hasMoreMessages && chatMessages.isNotEmpty() && !isLoadingOlderMessages) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.no_more_messages),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }

                        itemsIndexed(chatMessages) { index, msg ->
                            // Add time divider if more than 1 hour difference from previous message
                            if (index > 0) {
                                val previousMessage = chatMessages[index - 1]
                                val timeDifference = msg.timestamp - previousMessage.timestamp
                                val oneHourInMillis = 60L * 60L * 1000L // 1 hour in milliseconds

                                if (timeDifference > oneHourInMillis) {
                                    // Time divider
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 0.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            HorizontalDivider(
                                                modifier = Modifier
                                                    .padding(start = 8.dp)
                                                    .weight(1f),
                                                thickness = 1.dp,
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                            )
                                            Text(
                                                text = formatTimestamp(msg.timestamp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(
                                                    alpha = 0.6f
                                                ),
                                                modifier = Modifier.padding(horizontal = 12.dp)
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier
                                                    .padding(end = 8.dp)
                                                    .weight(1f),
                                                thickness = 1.dp,
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                            )
                                        }
                                    }
                                }
                            }

                            ChatItem(
                                viewModel = viewModel,
                                message = msg,
                                messages = chatMessages,
                                onImageClick = { attachment, bitmap ->
                                    fullScreenAttachment = attachment
                                    fullScreenBitmap = bitmap
                                    fullScreenMessage = msg // Store message for navigation
                                    showFullScreen = true
                                },
                                onVideoClick = { attachment ->
                                    fullScreenAttachment = attachment
                                    fullScreenMessage = msg // Store message for navigation
                                    showFullScreen = true
                                }
                            )
                        }
                    }

                    // Removed PullRefreshIndicator
                }
                ChatInput(
                    viewModel = viewModel,
                    onFocusGained = { scrollToBottom() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
    }
    
    // Full screen overlays - rendered at the top level (same as MediaItemView)
    if (showFullScreen && fullScreenAttachment != null) {
        val attachment = fullScreenAttachment!!
        val mediaUrl = us.fireshare.tweet.HproseInstance.getMediaUrl(attachment.mid, appUser.baseUrl).toString()
        
        when (attachment.type) {
            MediaType.Image -> {
                // Get all image attachments from the message for navigation
                val message = fullScreenMessage
                val allAttachments = message?.attachments ?: emptyList()
                val imageAttachments = allAttachments.mapIndexedNotNull { idx, item ->
                    val inferredType = us.fireshare.tweet.widget.inferMediaTypeFromAttachment(item)
                    if (inferredType == MediaType.Image) {
                        idx to us.fireshare.tweet.HproseInstance.getMediaUrl(item.mid, appUser.baseUrl).toString()
                    } else {
                        null
                    }
                }
                
                // Find current image index
                val currentImageIndexInList = imageAttachments.indexOfFirst { (idx, _) ->
                    allAttachments[idx].mid == attachment.mid
                }
                
                // Get list of image URLs
                val imageUrls = imageAttachments.map { (_, url) -> url }
                
                Dialog(
                    onDismissRequest = { 
                        showFullScreen = false
                        fullScreenAttachment = null
                        fullScreenBitmap = null
                        fullScreenMessage = null
                    },
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
                        us.fireshare.tweet.widget.AdvancedImageViewer(
                            imageUrl = mediaUrl,
                            enableLongPress = true,
                            initialBitmap = fullScreenBitmap,
                            onClose = { 
                                showFullScreen = false
                                fullScreenAttachment = null
                                fullScreenBitmap = null
                                fullScreenMessage = null
                            },
                            modifier = Modifier.fillMaxSize(),
                            imageUrls = if (imageUrls.size > 1) imageUrls else null,
                            onNextImage = {
                                if (imageAttachments.isNotEmpty() && message != null) {
                                    // Wrap around: if at last image, go to first
                                    val nextIndex = if (currentImageIndexInList >= imageAttachments.size - 1) {
                                        0 // Wrap to first image
                                    } else {
                                        currentImageIndexInList + 1
                                    }
                                    val (nextMediaIndex, _) = imageAttachments[nextIndex]
                                    fullScreenAttachment = message.attachments!![nextMediaIndex]
                                    fullScreenBitmap = null // Reset bitmap to load new image
                                }
                            },
                            onPreviousImage = {
                                if (imageAttachments.isNotEmpty() && message != null) {
                                    // Wrap around: if at first image, go to last
                                    val prevIndex = if (currentImageIndexInList <= 0) {
                                        imageAttachments.size - 1 // Wrap to last image
                                    } else {
                                        currentImageIndexInList - 1
                                    }
                                    val (prevMediaIndex, _) = imageAttachments[prevIndex]
                                    fullScreenAttachment = message.attachments!![prevMediaIndex]
                                    fullScreenBitmap = null // Reset bitmap to load new image
                                }
                            }
                        )
                    }
                }
            }
            MediaType.Video, MediaType.HLS_VIDEO -> {
                // Try to get existing player for seamless transition
                val existingPlayer = VideoManager.transferToFullScreen(attachment.mid)
                
                if (existingPlayer != null) {
                    // Use existing player for seamless transition - create a simple full-screen wrapper
                    FullScreenVideoPlayer(
                        existingPlayer = existingPlayer,
                        videoItem = attachment,
                        onClose = {
                            // Return player back to VideoManager when closed
                            VideoManager.returnFromFullScreen(attachment.mid)
                            showFullScreen = false
                            fullScreenAttachment = null
                            fullScreenBitmap = null
                            fullScreenMessage = null
                        },
                        enableImmersiveMode = true
                    )
                } else {
                    // Fallback to regular full-screen player
                    FullScreenVideoPlayer(
                        videoUrl = mediaUrl,
                        onClose = { 
                            showFullScreen = false
                            fullScreenAttachment = null
                            fullScreenBitmap = null
                            fullScreenMessage = null
                        },
                        enableImmersiveMode = true,
                        autoReplay = true
                    )
                }
            }
            else -> {
                // For other file types, do nothing
            }
        }
    }
}

@Composable
fun ChatItem(
    viewModel: ChatViewModel,
    message: ChatMessage,
    messages: List<ChatMessage>,
    onImageClick: (us.fireshare.tweet.datamodel.MimeiFileType, android.graphics.Bitmap?) -> Unit,
    onVideoClick: (us.fireshare.tweet.datamodel.MimeiFileType) -> Unit
) {
    // Capture string resources at composable level to avoid Android Studio warnings
    val errorUnknownErrorText = stringResource(R.string.error_unknown_error)
    val errorMessageFailedFormat = stringResource(R.string.error_message_failed)
    val context = LocalContext.current
    val isSentByCurrentUser = message.authorId == appUser.mid
    val receipt by viewModel.receipt.collectAsState()
    
    // Check if this message is being sent (has placeholder content and no attachments yet, but it's from current user)
    val isSending = isSentByCurrentUser && 
                   (message.content == "sending_attachment" || (message.content.isNullOrBlank() && message.attachments.isNullOrEmpty()))
    
    // Determine if this is the last message from this party
    val currentMessageIndex = messages.indexOf(message)
    val isLastMessageFromParty = if (isSentByCurrentUser) {
        // Check if this is the last message from current user
        currentMessageIndex == messages.lastIndex || 
        (currentMessageIndex < messages.lastIndex && 
         messages[currentMessageIndex + 1].authorId != appUser.mid)
    } else {
        // Check if this is the last message from other user
        currentMessageIndex == messages.lastIndex || 
        (currentMessageIndex < messages.lastIndex && 
         messages[currentMessageIndex + 1].authorId == appUser.mid)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp),
        horizontalAlignment = if (isSentByCurrentUser) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isSentByCurrentUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isSentByCurrentUser) {
                UserAvatar(user = receipt, size = 32)
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            // Show failure icon for failed messages sent by current user
            if (isSentByCurrentUser && !message.success) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = stringResource(R.string.message_failed_send_tap_error),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.CenterVertically)
                        .clickable {
                            val errorMessage = message.errorMsg ?: errorUnknownErrorText
                            Toast.makeText(context, errorMessageFailedFormat.format(errorMessage), Toast.LENGTH_LONG).show()
                        }
                )
            }
            
            // Message content in a column with two rows
            Column(
                horizontalAlignment = if (isSentByCurrentUser) Alignment.End else Alignment.Start
            ) {
                // First row: Text content (if any) - hide placeholder text for attachments
                if (message.content?.isNotBlank() == true && message.content != "sending_attachment") {
                    Surface(
                        color = if (isSending) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        shape = if (isLastMessageFromParty) {
                            ChatBubbleShape(isSentByCurrentUser)
                        } else {
                            regularChatBubbleShape()
                        },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        SelectionContainer {
                            BasicText(
                                text = buildAnnotatedText(message.content),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
                
                // Second row: Media preview grid (if attachments exist)
                message.attachments?.let { attachments ->
                    if (attachments.isNotEmpty()) {
                        Surface(
                            color = if (isSending) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            shape = if (isLastMessageFromParty) {
                                ChatBubbleShape(isSentByCurrentUser)
                            } else {
                                regularChatBubbleShape()
                            },
                            modifier = Modifier.padding(4.dp)
                        ) {
                            // Simple media preview for chat messages
                            ChatMediaPreview(
                                attachments = attachments,
                                onImageClick = { bitmap -> onImageClick(attachments.first(), bitmap) },
                                onVideoClick = { onVideoClick(attachments.first()) }
                            )
                        }
                    }
                }
                
                // Show loading placeholder when sending attachment
                if (isSending && message.attachments.isNullOrEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = if (isLastMessageFromParty) {
                            ChatBubbleShape(isSentByCurrentUser)
                        } else {
                            regularChatBubbleShape()
                        },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.sending_attachment_background),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.uploading_attachment),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            
            if (isSentByCurrentUser) {
                Spacer(modifier = Modifier.width(8.dp))
                UserAvatar(user = appUser, size = 32)
                
                // Show failure icon for failed messages sent by current user
                if (!message.success) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = stringResource(R.string.message_failed_send_tap_error),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                val errorMessage = message.errorMsg ?: errorUnknownErrorText
                                Toast.makeText(context, errorMessageFailedFormat.format(errorMessage), Toast.LENGTH_LONG).show()
                            }
                    )
                }
            }
        }
        
        // Show timestamp for last message from each party
        if (isLastMessageFromParty) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(
                    start = if (isSentByCurrentUser) 0.dp else 40.dp,
                    end = if (isSentByCurrentUser) 40.dp else 0.dp
                )
            )
        }
    }
}

@Composable
fun ChatInput(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    onFocusGained: () -> Unit = {},
) {
    val textState by viewModel.message
    val selectedAttachment by viewModel.selectedAttachment
    val hasInput = textState.isNotBlank() || selectedAttachment != null
    val isSending = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    
    // Track focus state
    var isFocused by remember { mutableStateOf(false) }
    
    // Call onFocusGained when focus is gained
    LaunchedEffect(isFocused) {
        if (isFocused) {
            // Add a small delay to allow keyboard to start appearing
            delay(100)
            onFocusGained()
        }
    }
    
    // Function to send message while maintaining focus
    fun sendMessage() {
        // Check if there's input and we're not already sending
        val currentText = textState
        val currentAttachment = selectedAttachment
        val hasCurrentInput = currentText.isNotBlank() || currentAttachment != null
        
        if (hasCurrentInput && !isSending.value) {
            isSending.value = true
            
            // Clear input immediately to show it's been sent
            viewModel.message.value = ""
            viewModel.selectedAttachment.value = null
            
            // Send message in background
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.sendMessage(currentText, currentAttachment, context)
                isSending.value = false
                // Ensure focus is maintained after sending
                withContext(Dispatchers.Main) {
                    focusRequester.requestFocus()
                }
            }
        }
    }
    
    // Function to check if file is within size limits
    fun isFileSizeValid(uri: Uri): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val fileSize = inputStream?.available() ?: 0
            inputStream?.close()
            fileSize <= TW_CONST.MAX_FILE_SIZE
        } catch (_: Exception) {
            false
        }
    }
    
    // File picker launcher for single file selection
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            if (isFileSizeValid(it)) {
                viewModel.selectedAttachment.value = it
            } else {
                Toast.makeText(context, context.getString(R.string.video_file_too_large), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Function to get filename from URI
    fun getFileName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex) ?: context.getString(R.string.unknown_file)
            } ?: context.getString(R.string.unknown_file)
        } catch (_: Exception) {
            context.getString(R.string.unknown_file)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Top shadow as divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
//                    shape = RoundedCornerShape(0.dp)
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attachment button
            IconButton(
                onClick = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.size(40.dp),
                enabled = !isSending.value
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = stringResource(R.string.attached_file),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Text input field with rounded corners
            OutlinedTextField(
                value = textState,
                onValueChange = { viewModel.message.value = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 120.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                    },
                placeholder = {
                    Text(
                        text = stringResource(R.string.type_message),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                },
                keyboardOptions = KeyboardOptions.Default.copy(
                    imeAction = ImeAction.Default,
                    keyboardType = KeyboardType.Text
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        sendMessage()
                    }
                ),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                singleLine = false,
                maxLines = 5,
                enabled = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send button without background decoration
            IconButton(
                onClick = {
                    sendMessage()
                },
                modifier = Modifier.size(40.dp),
                enabled = hasInput
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send),
                    tint = if (hasInput)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(24.dp)
                        .scale(scaleX = -1f, scaleY = 1f)
                )
            }
        }
        
        // Display selected attachment filename with remove button
        selectedAttachment?.let { uri ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Remove button
                IconButton(
                    onClick = {
                        viewModel.selectedAttachment.value = null
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.remove_attachment),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Filename
                Text(
                    text = getFileName(uri),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val calendar = java.util.Calendar.getInstance()
    val messageCalendar = java.util.Calendar.getInstance().apply { time = date }
    
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    
    return when {
        // Same day
        calendar.get(java.util.Calendar.YEAR) == messageCalendar.get(java.util.Calendar.YEAR) &&
        calendar.get(java.util.Calendar.DAY_OF_YEAR) == messageCalendar.get(java.util.Calendar.DAY_OF_YEAR) -> {
            timeFormat.format(date)
        }
        // Yesterday
        calendar.get(java.util.Calendar.YEAR) == messageCalendar.get(java.util.Calendar.YEAR) &&
        calendar.get(java.util.Calendar.DAY_OF_YEAR) == messageCalendar.get(java.util.Calendar.DAY_OF_YEAR) + 1 -> {
            "${stringResource(R.string.yesterday)} ${timeFormat.format(date)}"
        }
        // Other days
        else -> {
            dateFormat.format(date)
        }
    }
}

@Composable
fun ChatMediaPreview(
    attachments: List<us.fireshare.tweet.datamodel.MimeiFileType>,
    onImageClick: ((android.graphics.Bitmap?) -> Unit)? = null,
    onVideoClick: (() -> Unit)? = null
) {
    if (attachments.isEmpty()) return

    // For chat messages, we'll show a simple preview of the first attachment
    val attachment = attachments.first()
    val mediaUrl =
        us.fireshare.tweet.HproseInstance.getMediaUrl(attachment.mid, appUser.baseUrl).toString()

    // Video preloading is handled by the main ChatScreen component with debouncing

    // Helper function to apply aspect ratio rule: use 0.8 if aspect ratio is smaller than 0.8, otherwise use original value
    fun applyAspectRatioRule(originalAspectRatio: Float?): Float {
        return when {
            originalAspectRatio == null -> 16f / 9f // Default aspect ratio
            originalAspectRatio < 0.8f -> 0.8f // Use 0.8 if smaller than 0.8
            else -> originalAspectRatio // Use original value if >= 0.8
        }
    }

    val adjustedAspectRatio = applyAspectRatioRule(attachment.aspectRatio)

    Box(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .aspectRatio(adjustedAspectRatio)
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        when (attachment.type) {
            MediaType.Image -> {
                // Track visibility for priority-based loading (same as MediaItemView)
                var isVisible by remember { mutableStateOf(true) }
                var currentBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                
                // Use a Box with clickable modifier to handle image clicks (same as MediaItemView)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .onGloballyPositioned { layoutCoordinates ->
                            // Update visibility based on actual layout - if item has size, it's visible
                            val hasSize = layoutCoordinates.size.width > 0 && layoutCoordinates.size.height > 0
                            isVisible = hasSize
                        }
                        .clickable { onImageClick?.invoke(currentBitmap) }
                ) {
                    us.fireshare.tweet.widget.ImageViewer(
                        imageUrl = mediaUrl,
                        modifier = Modifier.fillMaxSize(),
                        enableLongPress = false, // Disable long press to allow clickable to work
                        inPreviewGrid = true, // Use preview grid mode like MediaCell
                        isVisible = isVisible, // Track visibility for priority loading
                        loadOriginalImage = false, // Load compressed images first for preview (same as MediaCell)
                        onBitmapLoaded = { bitmap -> currentBitmap = bitmap }
                    )
                }
            }

            MediaType.Video, MediaType.HLS_VIDEO -> {
                // Use a completely stable approach with key (same as MediaCell)
                val videoMid = attachment.mid

                // Use key with stable identifier to prevent recreation (same as MediaItemView)
                key("chat_video_${videoMid}_0") {
                    us.fireshare.tweet.widget.VideoPreview(
                        url = mediaUrl,
                        modifier = Modifier.fillMaxSize(),
                        index = 0,
                        autoPlay = true,
                        inPreviewGrid = true,
                        callback = { _ ->
                            // Open full-screen with the same video player
                            onVideoClick?.invoke()
                        },
                        videoMid = videoMid,
                        videoType = attachment.type
                    )
                }
            }

            MediaType.Audio -> {
                us.fireshare.tweet.widget.AudioPlayer(
                    attachments,
                    0,
                )
            }

            else -> {
                // For other file types, show a file icon with filename
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.file_attachment),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = attachment.fileName ?: stringResource(R.string.unknown_file),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}