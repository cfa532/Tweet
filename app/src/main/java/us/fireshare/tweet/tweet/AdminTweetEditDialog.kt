package us.fireshare.tweet.tweet

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.ui.components.ExitConfirmationDialog

@Composable
internal fun AdminTweetEditDialog(tweet: Tweet, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adminId = remember(tweet.mid) { HproseInstance.appUser.mid }
    val originalContent = remember(tweet.mid) { tweet.content.orEmpty() }
    val originalPrivacy = remember(tweet.mid) { tweet.isPrivate }
    val originalAttachments = remember(tweet.mid) { tweet.attachments.orEmpty().toList() }
    var content by rememberSaveable(tweet.mid) { mutableStateOf(originalContent) }
    var isPrivate by rememberSaveable(tweet.mid) { mutableStateOf(originalPrivacy) }
    val retained = remember(tweet.mid) { mutableStateListOf(*originalAttachments.toTypedArray()) }
    val selected = remember(tweet.mid) { mutableStateListOf<Uri>() }
    val uploaded = remember(tweet.mid) { mutableMapOf<Uri, MimeiFileType>() }
    var saving by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val retainedAttachments = retained.toList()
    val filesToUpload = selected.toList()
    val attachmentsChanged = retainedAttachments != originalAttachments || filesToUpload.isNotEmpty()
    val privacyChange = isPrivate.takeIf { it != originalPrivacy }
    val hasChanges = content != originalContent || attachmentsChanged || privacyChange != null
    val hasContent = content.isNotBlank() || retainedAttachments.isNotEmpty() ||
        filesToUpload.isNotEmpty() || !tweet.originalTweetId.isNullOrBlank()
    val canSave = hasChanges && hasContent
    val closeEditor = {
        if (!saving) {
            when {
                showCamera -> showCamera = false
                hasChanges -> showDiscard = true
                else -> onDismiss()
            }
        }
    }

    fun saveEdit() {
        if (saving || !canSave) return
        saving = true
        error = null
        val editedContent = content
        scope.launch {
            try {
                check(HproseInstance.appUser.mid == adminId)
                val attachments = if (attachmentsChanged) {
                    val newAttachments = if (filesToUpload.isEmpty()) emptyList()
                    else withContext(Dispatchers.IO) {
                        val author = tweet.author?.takeIf { !it.hostIds.isNullOrEmpty() }
                            ?: HproseInstance.fetchUser(tweet.authorId)
                            ?: throw IllegalStateException("Tweet author is unavailable")
                        filesToUpload.map { uri ->
                            // Reuse files already uploaded before a failed save.
                            uploaded[uri] ?: checkNotNull(
                                HproseInstance.uploadToIPFS(uri, uploadUser = author)
                            ) {
                                context.getString(R.string.attachment_upload_failed)
                            }.also { uploaded[uri] = it }
                        }
                    }
                    retainedAttachments + newAttachments
                } else null
                check(HproseInstance.appUser.mid == adminId)
                HproseInstance.editTweetAsAdmin(tweet, editedContent, attachments, privacyChange)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.edit_tweet_failed)
            } finally {
                saving = false
            }
        }
    }

    Dialog(
        onDismissRequest = closeEditor,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        val focusManager = LocalFocusManager.current
        TweetEditor(
            title = stringResource(R.string.edit_by_admin),
            content = content,
            onContentChange = { content = it },
            isPrivate = isPrivate,
            onPrivacyChange = { isPrivate = it },
            attachments = selected,
            showCamera = showCamera,
            onShowCameraChange = { showCamera = it },
            onClose = closeEditor,
            onSubmit = {
                focusManager.clearFocus()
                saveEdit()
            },
            canSubmit = canSave,
            isBusy = saving,
            error = error,
            onClearError = { error = null },
            showProgress = saving,
            maxVisibleLines = 10,
            retainedAttachments = retainedAttachments,
            retainedAttachmentBaseUrl = tweet.readNodeUrl ?: tweet.author?.baseUrl,
            onRemoveRetainedAttachment = { retained.remove(it) },
            submitDescription = stringResource(if (saving) R.string.saving else R.string.save)
        )
        ExitConfirmationDialog(
            showDialog = showDiscard,
            onDismiss = { showDiscard = false },
            onConfirm = onDismiss
        )
    }
}
