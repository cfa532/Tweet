package us.fireshare.tweet.profile

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.viewmodel.UserViewModel

// Helper functions moved to top to avoid forward reference issues
@Composable
fun AppUserAvatar(
    onAvatarClick: () -> Unit,
    viewModel: UserViewModel,
    selectedImageUri: Uri?,
    isUploading: Boolean,
    uploadError: String?
) {
    // Use appUser directly instead of ViewModel user data
    val user = appUser

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.size(120.dp)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .clickable(onClick = onAvatarClick)
            ) {
                // Show current avatar from appUser - key forces recomposition
                key(user.avatar) {
                    UserAvatar(user = user, size = 120)
                }
            }
            
            // No loading indicator - keep selected image visible during upload
            
            IconButton(
                onClick = onAvatarClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (40).dp, y = (-8).dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_photo_plus),
                    contentDescription = stringResource(R.string.change_avatar),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        
        // Show error message if upload failed
        uploadError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun EyeSlashButton(
    viewModel: UserViewModel,
    isPasswordVisible: Boolean
) {
    IconButton(
        onClick = { viewModel.onPasswordVisibilityChange() },
        modifier = Modifier.size(ButtonDefaults.IconSize)
    ) {
        Icon(painter = painterResource(
            if (isPasswordVisible) R.drawable.eyes else R.drawable.eye_slash
        ), contentDescription = null)
    }
}

/**
 * Register and edit user profile.
 * */
@Composable
fun EditProfileScreen(
    navController: NavHostController,
    viewModel: UserViewModel
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val username by viewModel.username
    val password by viewModel.password
    val confirm = remember { mutableStateOf(password) }
    val showConfirm = remember { mutableStateOf(false) }
    val name by viewModel.name
    val profile by viewModel.profile
    val domainToShare by viewModel.domainToShare
    val hostId by viewModel.hostId
    val cloudDrivePort by viewModel.cloudDrivePort
    val isPasswordVisible by viewModel.isPasswordVisible
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedImageUri = remember { mutableStateOf<Uri?>(null) }
    val isUploading = remember { mutableStateOf(false) }
    val uploadError = remember { mutableStateOf<String?>(null) }
    val defaultDomainPlaceholder = remember { mutableStateOf<String?>(null) }
    
    // Simple check for unsaved changes (excluding avatar and username which can't be changed)
    val hasUnsavedChanges = remember {
        derivedStateOf {
            name != appUser.name || 
            profile != appUser.profile || 
            password.isNotEmpty() ||
            domainToShare != (appUser.domainToShare ?: "") ||
            cloudDrivePort != appUser.cloudDrivePort.toString()
        }
    }
    
    // Dialog state for unsaved changes warning
    val showUnsavedChangesDialog = remember { mutableStateOf(false) }
    
    // State for avatar cropping
    val showCropScreen = remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(password) {
        showConfirm.value = password.isNotEmpty()
    }
    LaunchedEffect(Unit) {
        keyboardController?.show()
        viewModel.onPasswordChange("")
        // get hostId if not exists
        if (hostId.isEmpty()) {
            viewModel.getHostId()
        }
        // Get domain from check_upgrade for placeholder text
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val upgradeInfo = HproseInstance.checkUpgrade()
            upgradeInfo?.get("domain")?.let { domain ->
                defaultDomainPlaceholder.value = domain
            }
        }
    }

    // Show cropping screen if requested
    if (showCropScreen.value) {
        AvatarCropScreen(
            viewModel = viewModel,
            onNavigateBack = { 
                showCropScreen.value = false
            },
            onCropComplete = {
                showCropScreen.value = false
                // Avatar is updated in the cropping screen
            }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp)
                .imePadding()
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            IconButton(
                onClick = { 
                    if (hasUnsavedChanges.value) {
                        showUnsavedChangesDialog.value = true
                    } else {
                        navController.popBackStack()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.cancel)
                )
            }
            // AppUser avatar
            if (!appUser.isGuest()) {
                AppUserAvatar(
                    onAvatarClick = { showCropScreen.value = true },
                    viewModel = viewModel,
                    selectedImageUri = selectedImageUri.value,
                    isUploading = isUploading.value,
                    uploadError = uploadError.value
                )
            } else {
                Text(
                    text = stringResource(R.string.register),
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 24.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Column {
                OutlinedTextField(
                    value = username ?: "",
                    onValueChange = { viewModel.onUsernameChange(it) },
                    label = { Text(stringResource(R.string.username)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    enabled = appUser.isGuest()  // register new user
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { viewModel.onPasswordChange(it) },
                    label = {
                        Text(
                            if (appUser.isGuest()) stringResource(R.string.password)
                            else stringResource(R.string.use_current_pswd)
                        )
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { EyeSlashButton(viewModel, isPasswordVisible) },
                    singleLine = true
                )
                if (showConfirm.value) {
                    OutlinedTextField(
                        value = confirm.value,
                        onValueChange = { confirm.value = it },
                        label = { Text(stringResource(R.string.confirm_pwd)) },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { EyeSlashButton(viewModel, isPasswordVisible) },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = name ?: "",
                    onValueChange = { viewModel.onNameChange(it) },
                    label = { Text(stringResource(R.string.name)) },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true
                )
                OutlinedTextField(
                    value = profile ?: "",
                    onValueChange = { viewModel.onProfileChange(it) },
                    label = { Text(stringResource(R.string.profile)) },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = false
                )
                OutlinedTextField(
                    value = hostId,
                    onValueChange = { viewModel.onNodeIdChange(it) },
                    label = { Text(stringResource(R.string.host_id)) },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = false
                )
                OutlinedTextField(
                    value = cloudDrivePort,
                    onValueChange = { viewModel.onCloudDrivePortChange(it) },
                    label = { Text(stringResource(R.string.cloud_port)) },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true
                )
                OutlinedTextField(
                    value = domainToShare,
                    onValueChange = { viewModel.onDomainToShareChange(it) },
                    label = { Text(stringResource(R.string.domain_to_share)) },
                    placeholder = defaultDomainPlaceholder.value?.let { { Text(it) } } ?: null,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true
                )
            }
            Button(
                onClick = {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        if (password.isNotEmpty() && password != confirm.value) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.confirm_pwd),
                                Toast.LENGTH_SHORT
                            ).show()
                            viewModel.isLoading.value = false
                            return@launch
                        }
                        viewModel.register(context) {
                            viewModel.viewModelScope.launch(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.registration_ok),
                                    Toast.LENGTH_LONG
                                ).show()
                                navController.popBackStack()
                            }
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .width(intrinsicSize = IntrinsicSize.Max)
                    .align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.save))
            }
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
    
    // Unsaved changes warning dialog
    if (showUnsavedChangesDialog.value) {
        AlertDialog(
            onDismissRequest = { showUnsavedChangesDialog.value = false },
            title = { 
                Text(
                    text = stringResource(R.string.save_changes_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                ) 
            },
            text = { 
                Text(
                    text = stringResource(R.string.unsaved_changes_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ) 
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedChangesDialog.value = false
                        navController.popBackStack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text(stringResource(R.string.dont_save))
                }
            },
            dismissButton = {
                Button(
                    onClick = { showUnsavedChangesDialog.value = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.save_and_continue))
                }
            }
        )
    }
}

@Composable
fun SelectedImageDisplay(uri: Uri) {
    val context = LocalContext.current
    val bitmap = remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            bitmap.value = inputStream?.let { 
                // Read the entire stream into a byte array to handle mark/reset issues
                val byteArray = inputStream.readBytes()
                val byteArrayInputStream = java.io.ByteArrayInputStream(byteArray)
                
                // Use the private method directly since we need EXIF orientation handling
                val options = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                val bitmap = android.graphics.BitmapFactory.decodeStream(byteArrayInputStream, null, options)
                if (bitmap != null) {
                    // Apply EXIF orientation correction
                    try {
                        // Create a temporary file to use with ExifInterface
                        val tempFile = java.io.File.createTempFile("exif_temp", ".jpg")
                        tempFile.writeBytes(byteArray)
                        
                        val exif = androidx.exifinterface.media.ExifInterface(tempFile.absolutePath)
                        val orientation = exif.getAttributeInt(
                            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                        )
                        
                        // Clean up temp file
                        tempFile.delete()
                        
                        val matrix = android.graphics.Matrix()
                        when (orientation) {
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                                matrix.postRotate(90f)
                                matrix.postScale(-1f, 1f)
                            }
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                                matrix.postRotate(270f)
                                matrix.postScale(-1f, 1f)
                            }
                            else -> return@let bitmap
                        }
                        
                        android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } catch (e: Exception) {
                        bitmap
                    }
                } else null
            }
            inputStream?.close()
        } catch (e: Exception) {
            // Handle error
        }
    }
    
    if (bitmap.value != null) {
        Image(
            bitmap = bitmap.value!!.asImageBitmap(),
            contentDescription = stringResource(R.string.user_avatar),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        // Show placeholder while loading
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

}
