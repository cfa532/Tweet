package com.fireshare.tweet.share

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.tweet.guestWarning
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.launch

@Composable
fun ShareScreenshotButton(viewModel: TweetViewModel) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    var showBottomSheet by remember { mutableStateOf(false) }
    var contentToShare by rememberSaveable { mutableStateOf("") }

    if (showBottomSheet) {
        ShareBottomSheet(
            onDismiss = { showBottomSheet = false },
            viewModel = viewModel,
            contentToShare = contentToShare
        )
    }
    IconButton(onClick = {
        if (appUser.mid == TW_CONST.GUEST_ID) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController)
            }
        } else {
            viewModel.viewModelScope.launch {
                val map = HproseInstance.checkUpgrade() ?: return@launch
                contentToShare = "http://${map["domain"]}/tweet/${viewModel.tweetState.value.mid}"
                showBottomSheet = true
            }
        }
    }) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

class ShareOption(val title: String, val imageVector: ImageVector, val action: () -> Unit)

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
    val showIntentOption by remember { mutableStateOf(false) }

    val shareIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, contentToShare)
        type = "text/plain" // Adjust the MIME type based on the content
    }
    val chooserIntent = Intent.createChooser(shareIntent, "Share via")
    val packageManager = LocalContext.current.packageManager
    val shareableApps =
        packageManager.queryIntentActivities(shareIntent, PackageManager.MATCH_DEFAULT_ONLY)

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            selectedApplication = if (result.resultCode == Activity.RESULT_OK) {
                // Handle the selected application (optional)
                val app = result.data?.component?.packageName
                println("selected application: $app")
                app
            } else {
                // Handle the case where the user canceled the share
                null
            }
        }

    val shareOptions = listOf(
        ShareOption("Share screenshot", Icons.Default.MailOutline) {
            captureScreenshot(context as Activity) {
                if (it != null) {
                    val file = saveBitmapToFile(context, it, "screenshot.png")
                    shareImage(context, file)
                }
            }
        },
        ShareOption("Share link", Icons.Default.Email) {
        },
        ShareOption("Share via Social Media", Icons.Default.AccountCircle) {
        },
    )

    ModalBottomSheet(
        sheetState = bottomSheetState,
        onDismissRequest = onDismiss,
    ) {
        // Custom options
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            shareOptions.forEach { option ->
                Column(
                    modifier = Modifier.clickable { option.action() }
                        .padding(8.dp), // Removed redundant clickable
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = option.imageVector,
                        contentDescription = option.title,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = option.title, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            items(shareableApps) { appInfo ->
                Column(
                    modifier = Modifier.clickable {
                        // Set the component of the intent to the selected application
//                        val componentName = ComponentName(appInfo.activityInfo.packageName, appInfo.activityInfo.name)
//                        sendIntent.component = componentName

                        // Launch the intent directly to the selected application
                        launcher.launch(chooserIntent)
                    }.padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val drawable = remember { appInfo.loadIcon(packageManager) }
                    Image(
                        painter = rememberDrawablePainter(drawable),
                        contentDescription = appInfo.loadLabel(packageManager).toString(),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = appInfo.loadLabel(packageManager).toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

//        if (selectedApplication != null) {
//            Text(
//                text = "Selected application: $selectedApplication",
//                modifier = Modifier.padding(16.dp),
//                style = MaterialTheme.typography.bodyLarge
//            )
//        } else if (showIntentOption) {
//            Text(
//                text = "Share canceled",
//                modifier = Modifier.padding(16.dp),
//                style = MaterialTheme.typography.bodyLarge
//            )
//        }
    }
}
