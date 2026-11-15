package us.fireshare.tweet.tweet

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import us.fireshare.tweet.ui.components.ExitConfirmationDialog
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
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>(viewModelStoreOwner = activity)

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
            if (showCamera) {
                showCamera = false
            } else if (tweetContent.isNotBlank() || selectedAttachments.isNotEmpty()) {
                showExitConfirmation = true
            } else {
                draft?.let { HproseInstance.removeIncompleteUpload(context, it.workId) }
                navController.popBackStack()
            }
        }
    }
    BackHandler(onBack = onBackClick)

    TweetEditor(
        title = stringResource(R.string.edit),
        content = tweetContent,
        onContentChange = { tweetContent = it },
        isPrivate = isPrivate,
        onPrivacyChange = { isPrivate = it },
        attachments = selectedAttachments,
        showCamera = showCamera,
        onShowCameraChange = { showCamera = it },
        onClose = onBackClick,
        onSubmit = onSendClick,
        canSubmit = tweetContent.isNotBlank() || selectedAttachments.isNotEmpty(),
        isBusy = isBusy,
        error = publishError,
        onClearError = { publishError = null },
        showActions = !restoringDraft
    )

    // Exit confirmation dialog
    ExitConfirmationDialog(
        showDialog = showExitConfirmation,
        onDismiss = { showExitConfirmation = false },
        onConfirm = {
            draft?.let { HproseInstance.removeIncompleteUpload(context, it.workId) }
            navController.popBackStack()
        }
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
