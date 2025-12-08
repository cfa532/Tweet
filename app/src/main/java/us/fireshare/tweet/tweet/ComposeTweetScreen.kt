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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance.appUser
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
fun ComposeTweetScreen(
    navController: NavHostController
) {
    val context = LocalContext.current
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    // Capture string resources at composable level to avoid Android Studio warnings
    val filesTooLargeText = stringResource(R.string.files_too_large)

    // Set context for notifications
    LaunchedEffect(Unit) {
        tweetFeedViewModel.setNotificationContext(context)
    }

    // State management
    var tweetContent by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var showNodeRequiredDialog by remember { mutableStateOf(false) }
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
                    Toast.makeText(context, filesTooLargeText, Toast.LENGTH_LONG).show()
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
        if (tweetContent.trim().isNotEmpty() || selectedAttachments.isNotEmpty()) {
            // Check full version: if non-guest user has > 10 tweets and no cloudDrivePort, require node setup
            if (!BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 10 && appUser.cloudDrivePort == 0) {
                showNodeRequiredDialog = true
            }
            else {
                // Store content before clearing
                val contentToUpload = tweetContent.trim()
                val attachmentsToUpload = selectedAttachments.toList()
                
                // Clear UI immediately for better UX
                selectedAttachments.clear()
                tweetContent = ""
                
                // Upload tweet using TweetFeedViewModel
                tweetFeedViewModel.uploadTweet(
                    context,
                    contentToUpload,
                    attachmentsToUpload,
                    isPrivate
                )
                
                // Navigate back
                navController.popBackStack()
            }
        }
        Unit
    }

    // Handle back action
    val onBackClick = {
        if (tweetContent.trim().isNotEmpty() || selectedAttachments.isNotEmpty()) {
            showExitConfirmation = true
        } else {
            navController.popBackStack()
        }
        Unit
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
                title = stringResource(R.string.edit),
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
                    maxLines = 12
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

                // Action buttons row
                ActionButtonsRow(
                    isChecked = isPrivate,
                    onCheckedChange = { isPrivate = it },
                    checkboxLabel = stringResource(R.string.private_tweet),
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
        onConfirm = { navController.popBackStack() }
    )

    // Camera handler
    CameraHandler(
        showCamera = showCamera,
        onImageCaptured = onImageCaptured,
        onVideoRecorded = onVideoRecorded,
        onDismiss = { showCamera = false },
        modifier = Modifier.fillMaxSize()
    )
    
    // Node required dialog (full version, > 10 tweets, no cloudDrivePort)
    if (showNodeRequiredDialog) {
        AlertDialog(
            onDismissRequest = { showNodeRequiredDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.node_required_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.node_required_message)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showNodeRequiredDialog = false }
                ) {
                    Text(stringResource(R.string.node_required_ok))
                }
            }
        )
    }
}