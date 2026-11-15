package us.fireshare.tweet.tweet

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUserState
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
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeTweetScreen(
    navController: NavHostController,
    draftWorkId: String?
) {
    // Observe appUser changes via StateFlow
    val appUser by appUserState.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current as ComponentActivity
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val genericPublishError = stringResource(R.string.tweet_failed)
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>(viewModelStoreOwner = activity)
    // Capture string resources at composable level to avoid Android Studio warnings
    val filesTooLargeText = stringResource(R.string.files_too_large)

    // Set context for notifications
    LaunchedEffect(Unit) {
        tweetFeedViewModel.setNotificationContext(context)
    }

    // State management
    var tweetContent by remember(appUser.mid) { mutableStateOf("") }
    var isPrivate by remember(appUser.mid) { mutableStateOf(false) }
    var draft by remember(appUser.mid) { mutableStateOf<HproseInstance.IncompleteUpload?>(null) }
    var enqueuing by remember { mutableStateOf(false) }
    var restoringDraft by remember(appUser.mid) { mutableStateOf(true) }
    var publishError by remember(appUser.mid) { mutableStateOf<String?>(null) }
    val isBusy = restoringDraft || enqueuing
    var showCamera by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var showNodeRequiredDialog by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    val selectedAttachments = remember(appUser.mid) { mutableStateListOf<Uri>() }

    LaunchedEffect(appUser.mid, draftWorkId) {
        val saved = HproseInstance.getIncompleteUploads(context)
            .filter {
                !appUser.isGuest() && (it.authorId == null || it.authorId == appUser.mid) &&
                    (draftWorkId == null || it.workId == draftWorkId)
            }
            .sortedByDescending { it.timestamp }
        for (upload in saved) {
            val id = runCatching { UUID.fromString(upload.workId) }.getOrNull()
            val info = id?.let { workManager.getWorkInfoByIdFlow(it).first() }
            if (info?.state == WorkInfo.State.SUCCEEDED) {
                HproseInstance.removeIncompleteUpload(context, upload.workId)
                continue
            }
            if (info?.state?.isFinished == false) continue
            draft = upload
            tweetContent = upload.tweetContent
            isPrivate = upload.isPrivate
            selectedAttachments.addAll(upload.attachmentUris.map(String::toUri))
            break
        }
        restoringDraft = false
    }

    var fileSizeWarnings by remember { mutableStateOf<List<String>>(emptyList()) }

    // Update file size warnings when attachments change
    LaunchedEffect(selectedAttachments.toList()) {
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

    val onSendClick: () -> Unit = {
        if (!enqueuing && !restoringDraft && (tweetContent.isNotBlank() || selectedAttachments.isNotEmpty())) {
            if (!appUser.isGuest() && appUser.tweetCount > 10 && appUser.cloudDrivePort == 0) {
                showNodeRequiredDialog = true
            } else {
                focusManager.clearFocus()
                enqueuing = true
                scope.launch {
                    try {
                        tweetFeedViewModel.uploadTweet(
                            context, tweetContent.trim(), selectedAttachments.toList(), isPrivate, draft
                        )
                        Toast.makeText(
                            context, context.getString(R.string.tweet_uploading_background), Toast.LENGTH_SHORT
                        ).show()
                        navController.popBackStack()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag("ComposeTweetScreen").e(e, "Could not queue tweet upload")
                        publishError = genericPublishError
                        enqueuing = false
                    }
                }
            }
        }
    }

    val onBackClick: () -> Unit = {
        if (!isBusy) {
            if (tweetContent.isNotBlank() || selectedAttachments.isNotEmpty()) {
                showExitConfirmation = true
            } else {
                draft?.let { HproseInstance.removeIncompleteUpload(context, it.workId) }
                navController.popBackStack()
            }
        }
    }
    BackHandler(onBack = onBackClick)

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
                hasContent = hasContent && !isBusy
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
                    onTextChange = { if (!isBusy) tweetContent = it },
                    onMentionSearch = onMentionSearch,
                    suggestions = suggestions,
                    onSuggestionSelected = onSuggestionSelected,
                    onClearSuggestions = { suggestions = emptyList() },
                    maxLines = 12
                )

                // Attachment preview row
                AttachmentPreviewRow(
                    attachments = selectedAttachments,
                    onRemoveAttachment = { if (!isBusy) selectedAttachments.remove(it) }
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

                if (!restoringDraft) {
                    ActionButtonsRow(
                        isChecked = isPrivate,
                        onCheckedChange = { isPrivate = it },
                        checkboxLabel = stringResource(R.string.private_tweet),
                        onCameraClick = onCameraClick,
                        onFilePickerClick = onFilePickerClick,
                        onSendClick = onSendClick,
                        isLoading = enqueuing
                    )
                }
            }
        }
    }

    // Exit confirmation dialog
    ExitConfirmationDialog(
        showDialog = showExitConfirmation,
        onDismiss = { showExitConfirmation = false },
        onConfirm = {
            draft?.let { HproseInstance.removeIncompleteUpload(context, it.workId) }
            navController.popBackStack()
        }
    )

    publishError?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            text = { Text(message) },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            confirmButton = {
                TextButton(onClick = {
                    publishError = null
                }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

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
