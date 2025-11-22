package us.fireshare.tweet.tweet

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.ui.components.ActionButtonsRow
import us.fireshare.tweet.ui.components.AttachmentPreviewRow
import us.fireshare.tweet.ui.components.CameraHandler
import us.fireshare.tweet.ui.components.ComposeTextField
import us.fireshare.tweet.ui.components.ComposeTopAppBar
import us.fireshare.tweet.ui.components.ExitConfirmationDialog
import us.fireshare.tweet.utils.FileSizeUtils
import us.fireshare.tweet.viewmodel.TweetFeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeCommentScreen(
    popBack: () -> Unit,
) {
    val context = LocalContext.current
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val tweetViewModel = sharedViewModel.tweetViewModel
    val isCheckedToTweet by tweetViewModel.isCheckedToTweet
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    // Start listening to tweet and comment notifications
    LaunchedEffect(Unit) {
        tweetViewModel.startListeningToNotifications(context)
        tweetFeedViewModel.setNotificationContext(context)
    }

    // State management
    var tweetContent by remember { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    val selectedAttachments = remember { mutableStateListOf<Uri>() }
    var fileSizeWarnings by remember { mutableStateOf<List<String>>(emptyList()) }

    // Update file size warnings when attachments change
    LaunchedEffect(selectedAttachments) {
        fileSizeWarnings = selectedAttachments.map { uri ->
            val fileSize = FileSizeUtils.getFileSize(context, uri)
            FileSizeUtils.getFileSizeWarningMessage(context, fileSize)
        }
    }

    // File picker launcher
    val filesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            if (selectedAttachments.find { u -> u == uri } == null) {
                val fileSize = FileSizeUtils.getFileSize(context, uri)
                if (FileSizeUtils.isFileSizeValid(fileSize)) {
                    selectedAttachments.add(uri)
                } else {
                    Toast.makeText(context, context.getString(R.string.files_too_large), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Handle image capture from CameraX
    val onImageCaptured = { uri: Uri ->
        Timber.tag("CameraX").d("Image captured: $uri")
        selectedAttachments.add(uri)
        showCamera = false
    }

    // Handle video recording from CameraX
    val onVideoRecorded = { uri: Uri ->
        Timber.tag("CameraX").d("Video recorded: $uri")
        selectedAttachments.add(uri)
        showCamera = false
    }

    // Handle mention search
    val onMentionSearch = { query: String ->
        sharedViewModel.appUserViewModel.viewModelScope.launch {
            suggestions = sharedViewModel.appUserViewModel.getSuggestions(query)
        }
        Unit
    }

    // Handle suggestion selection
    val onSuggestionSelected = { suggestion: String ->
        tweetContent = tweetContent.substringBeforeLast("@") + "@$suggestion "
        suggestions = emptyList()
    }

    // Handle send action
    val onSendClick = {
        if (tweetContent.isNotEmpty() || selectedAttachments.isNotEmpty()) {
            // Store content before clearing
            val contentToUpload = tweetContent.trim()
            val attachmentsToUpload = selectedAttachments.toList()

            // Clear UI immediately for better UX
            selectedAttachments.clear()
            tweetContent = ""

            // Upload comment using TweetViewModel
            tweetViewModel.uploadComment(
                context,
                contentToUpload,
                attachmentsToUpload,
            )

            // Navigate back
            popBack()
        }
    }

    // Handle back action
    val onBackClick = {
        if (tweetContent.isNotEmpty() || selectedAttachments.isNotEmpty()) {
            showExitConfirmation = true
        } else {
            popBack()
        }
    }

    // Handle camera click
    val onCameraClick = {
        showCamera = true
    }

    // Handle file picker click
    val onFilePickerClick = {
        filesPickerLauncher.launch(arrayOf("*/*"))
    }

    // Check if there's content to send
    val hasContent = tweetContent.trim().isNotEmpty() || selectedAttachments.isNotEmpty()

    Scaffold(
        topBar = {
            ComposeTopAppBar(
                title = "Comment",
                showCamera = showCamera,
                onBackClick = onBackClick,
                onSendClick = onSendClick,
                hasContent = hasContent
            )
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .padding(innerPadding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp)
            ) {
                // Text input field with mention suggestions
                ComposeTextField(
                    text = tweetContent,
                    onTextChange = { tweetContent = it },
                    onMentionSearch = onMentionSearch,
                    suggestions = suggestions,
                    onSuggestionSelected = onSuggestionSelected,
                    onClearSuggestions = { suggestions = emptyList() },
                    maxLines = 10
                )

                // Attachment preview row
                AttachmentPreviewRow(
                    attachments = selectedAttachments,
                    onRemoveAttachment = { selectedAttachments.remove(it) }
                )

                // File size warnings
                if (fileSizeWarnings.isNotEmpty()) {
                    fileSizeWarnings.forEach { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (warning.contains("120MB")) {
                                MaterialTheme.colorScheme.error
                            } else if (warning.contains("Large file") || warning.contains("Very large")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons row (with "Quote Original" checkbox)
                ActionButtonsRow(
                    isChecked = isCheckedToTweet,
                    onCheckedChange = { tweetViewModel.onCheckedChange(it) },
                    checkboxLabel = "Quote Original",
                    onCameraClick = onCameraClick,
                    onFilePickerClick = onFilePickerClick,
                    onSendClick = onSendClick,
                    isLoading = false
                )
            }
        }
    }

    // Exit confirmation dialog
    ExitConfirmationDialog(
        showDialog = showExitConfirmation,
        onDismiss = { },
        onConfirm = { popBack() },
        title = "Discard Comment"
    )

    // Camera handler
    CameraHandler(
        showCamera = showCamera,
        onImageCaptured = onImageCaptured,
        onVideoRecorded = onVideoRecorded,
        onDismiss = { showCamera = false },
        modifier = Modifier.fillMaxSize()
    )
}