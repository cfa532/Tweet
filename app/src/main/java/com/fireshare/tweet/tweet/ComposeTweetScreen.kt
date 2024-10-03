package com.fireshare.tweet.tweet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.fireshare.tweet.R
import com.fireshare.tweet.service.SnackbarAction
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.service.SnackbarEvent
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.widget.UploadFilePreview
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeTweetScreen(
    navController: NavHostController,
    viewModel: TweetFeedViewModel = hiltViewModel(),
) {
    var tweetContent by remember { mutableStateOf("") }
    val selectedAttachments = remember { mutableStateListOf<Uri>() }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            if (selectedAttachments.find { u -> u == it } == null) {
                selectedAttachments.add(it)
            }
        }
    }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            imageUri?.let {
                val source = ImageDecoder.createSource(context.contentResolver, it)
                bitmap = ImageDecoder.decodeBitmap(source)
                selectedAttachments.add(it)
            }
        }
    }

    val takeAShot = {
        val photoFile = createImageFile(context)
        photoFile?.also {
            val photoURI: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                it
            )
            imageUri = photoURI
            cameraLauncher.launch(photoURI)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (it) {
            Toast.makeText(context, "Permission Granted", Toast.LENGTH_SHORT).show()
            takeAShot()
        } else {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
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
                    Text("Edit Tweet", fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (tweetContent.isNotEmpty() || selectedAttachments.isNotEmpty()) {
                            val event = SnackbarEvent(
                                message = "Are you sure to quit?",
                                action = SnackbarAction(name = "Quit",
                                    action = { navController.popBackStack() })
                            )
                            viewModel.viewModelScope.launch {
                                SnackbarController.sendEvent(event)
                            }
                        } else
                            navController.popBackStack()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.uploadTweet(
                                context,
                                tweetContent.trim(),
                                selectedAttachments
                            )
                            navController.popBackStack()
                        }, modifier = Modifier
                            .padding(horizontal = 16.dp) // Add padding for spacing
                            .alpha(1f) // Set opacity to 80%
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.rotate(180f)
                        )
                    }
                }
            )
        }) { innerPadding ->
        // content of scaffold, in the middle of current page.
        Surface(
            modifier = Modifier
                .padding(innerPadding)
                .imePadding(),
        ) {
            Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp))
            {
                val focusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus() // Request focus on composable launch
                }
                OutlinedTextField(
                    value = tweetContent,
                    onValueChange = { tweetContent = it },
                    label = { Text("What's happening?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp)
                        .alpha(0.7f)
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                // Optionally show the keyboard programmatically
                                keyboardController?.show()
                            }
                        },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            takeAShot()
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        } },
                        modifier = Modifier.size(40.dp)
                            .padding(top = 10.dp, end = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_camera), // Replace with your camera icon
                            contentDescription = "Open camera",
                            tint = MaterialTheme.colorScheme.surfaceTint,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier.size(48.dp)
                        ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_photo_plus),
                            contentDescription = "upload file",
                            tint = MaterialTheme.colorScheme.surfaceTint,
                        )
                    }
                }

                // Display icons for attached files
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    items(selectedAttachments.chunked(2)) { rowItems ->
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(rowItems) { uri ->
                                UploadFilePreview(uri)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Throws(IOException::class)
fun createImageFile(context: Context): File? {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    return File.createTempFile(
        "JPEG_${timeStamp}_",
        ".jpg",
        storageDir
    )
}