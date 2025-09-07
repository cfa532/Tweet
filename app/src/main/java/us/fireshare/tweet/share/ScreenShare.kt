package us.fireshare.tweet.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.getString
import androidx.core.graphics.scale
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.tweet.guestWarning
import us.fireshare.tweet.viewmodel.TweetViewModel
import kotlin.random.Random

@Composable
fun ShareScreenshotButton(viewModel: TweetViewModel) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    var showBottomSheet by remember { mutableStateOf(false) }
    var contentToShare by rememberSaveable { mutableStateOf("") }
    val tweet by viewModel.tweetState.collectAsState()

    if (showBottomSheet) {
        ShareBottomSheet(
            onDismiss = { showBottomSheet = false },
            viewModel = viewModel,
            contentToShare = contentToShare
        )
    }
    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController)
            }
        } else {
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                contentToShare = "${appUser.baseUrl}/entry?mid=${BuildConfig.APP_ID_HASH}" +
                        "&ver=last&r=${Random.nextFloat()}#/tweet/${tweet.mid}/${tweet.authorId}"
                showBottomSheet = true
            }
        }
    }) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_share),
                contentDescription = stringResource(R.string.share),
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

class ShareOption(val title: String, val painter: Painter, val action: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareBottomSheet(
    onDismiss: () -> Unit,
    viewModel: TweetViewModel,
    contentToShare: String
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedApplication by remember { mutableStateOf<String?>(null) } // Store selected application
    val bottomSheetState = rememberModalBottomSheetState()
    val shareText = remember { mutableStateOf(contentToShare) }
    val tweet by viewModel.tweetState.collectAsState()

    val shareIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, contentToShare)
        type = "text/plain" // Adjust the MIME type based on the content
    }
            val chooserIntent = Intent.createChooser(shareIntent, context.getString(R.string.share_via))

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            selectedApplication = if (result.resultCode == Activity.RESULT_OK) {
                // Handle the selected application (optional)
                result.data?.component?.packageName
            } else {
                // Handle the case where the user canceled the share
                null
            }
        }

    val shareOptions = listOf(
        ShareOption(getString(context, R.string.screenshot), painterResource(R.drawable.ic_camera_2)) {
            captureScreenshot(context as Activity) {bitmap ->
                if (bitmap == null) return@captureScreenshot
                val newBitmap = bitmap.scale(bitmap.width / 2, bitmap.height / 2, false)
                val fileName = "screenshot${System.currentTimeMillis()}.png"
                val file = saveBitmapToFile(context, newBitmap, fileName)
                viewModel.viewModelScope.launch {
                    val map = HproseInstance.checkUpgrade() ?: return@launch
                    HproseInstance.uploadToIPFS(context, Uri.fromFile(file))?.let {
                        it.type = MediaType.Image
                        HproseInstance.uploadTweet(
                            Tweet(mid = it.mid,
                                authorId = appUser.mid,
                                content = "http://${map["domain"]}/tweet/${tweet.mid}/${tweet.authorId}",
                                attachments = listOf(it))
                        )?.let { newTweet ->
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            shareText.value = "http://${map["domain"]}/tweet/${newTweet.mid}/${newTweet.authorId}"
                            val clip = ClipData.newPlainText("Shared Text", shareText.value)
                            clipboardManager.setPrimaryClip(clip)
                        }
                    }
//                    shareImage(context, file)
//                    onDismiss()
                }
            }
        },
        ShareOption(getString(context, R.string.share_link), painterResource(R.drawable.ic_share)) {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Shared Text", shareText.value)
            clipboardManager.setPrimaryClip(clip)
            scope.launch {
                Toast.makeText(context, context.getString(R.string.clipboard_copy), Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        },
        ShareOption(getString(context, R.string.social_media), painterResource(R.drawable.ic_apps)) {
            launcher.launch(chooserIntent)
        },
    )

    ModalBottomSheet(
        sheetState = bottomSheetState,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal =16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = stringResource(R.string.content_to_share),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = shareText.value,
                    onValueChange = { shareText.value = it },
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            shareOptions.forEach { option ->
                Column(
                    modifier = Modifier
                        .clickable { option.action() }
                        .padding(8.dp), // Removed redundant clickable
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = option.painter,
                        contentDescription = option.title,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = option.title, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
