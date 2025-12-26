package us.fireshare.tweet.profile

import android.net.Uri
import android.widget.Toast
import java.lang.System
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import us.fireshare.tweet.ActivityViewModel
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
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.appUserState
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
    // Observe appUserState to react to changes
    val user by appUserState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

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
                // Always show current user.avatar - UserAvatar will handle loading
                // Use key to force recomposition when avatar changes
                key(user.avatar) {
                    UserAvatar(user = user, size = 120)
                }
            }
            
            // Show uploading message overlay when uploading
            if (isUploading || isLoading) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.uploading_avatar),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
            
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
        
        // Show error message if upload failed (but not during upload)
        if (!isUploading && !isLoading) {
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
    val activityViewModel: ActivityViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
    val domainToShareFocusRequester = remember { FocusRequester() }
    // Observe appUserState to react to avatar changes
    val appUser by appUserState.collectAsState()
    val username by viewModel.username
    val password by viewModel.password
    val confirm = remember { mutableStateOf(password) }
    val showConfirm = remember { mutableStateOf(false) }
    val name by viewModel.name
    val profile by viewModel.profile
    val hostId by viewModel.hostId
    val domainToShare by viewModel.domainToShare
    val cloudDrivePort by viewModel.cloudDrivePort
    val isPasswordVisible by viewModel.isPasswordVisible
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedImageUri = remember { mutableStateOf<Uri?>(null) }
    val isUploading = remember { mutableStateOf(false) }
    val uploadError = remember { mutableStateOf<String?>(null) }
    val hostIdFocused = remember { mutableStateOf(false) }
    val domainToShareFocused = remember { mutableStateOf(false) }

    // Debounce state to prevent rapid button clicks (similar to iOS DebounceButton)
    val lastClickTime = remember { mutableLongStateOf(0L) }
    val debounceDuration = 1000L // 1 second cooldown, matching iOS
    
    // Simple check for unsaved changes (excluding avatar and username which can't be changed)
    // Note: name and profile are non-nullable Strings in ViewModel (initialized with ?: "")
    val hasUnsavedChanges = remember {
        derivedStateOf {
            // Compare name - both are non-nullable Strings
            name != (appUser.name ?: "") ||
            // Compare profile - both are non-nullable Strings
            profile != (appUser.profile ?: "") ||
            // Check if password has been entered
            password.isNotEmpty() ||
            // Compare cloudDrivePort - ViewModel converts 0 to "", so compare accordingly
            cloudDrivePort != (if (appUser.cloudDrivePort == 0) "" else appUser.cloudDrivePort.toString())
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
    // Sync ViewModel values with current appUser when screen opens or appUser changes
    // Use a key that includes all relevant fields to detect changes even when appUser object is mutated
    LaunchedEffect(
        appUser.name,
        appUser.profile,
        appUser.cloudDrivePort,
        appUser.mid,
        appUser.avatar
    ) {
        // Sync ViewModel values with current appUser to ensure no false unsaved changes detection
        viewModel.name.value = appUser.name ?: ""
        viewModel.profile.value = appUser.profile ?: ""
        viewModel.cloudDrivePort.value = if (appUser.cloudDrivePort == 0) "" else appUser.cloudDrivePort.toString()
    }
    
    // Watch isLoading to clear upload state when done
    LaunchedEffect(isLoading) {
        if (!isLoading && isUploading.value) {
            // Upload is complete
            isUploading.value = false
        }
    }
    
    LaunchedEffect(Unit) {
        keyboardController?.show()
        viewModel.onPasswordChange("")
        // Force sync ViewModel values with current appUser when screen opens
        viewModel.name.value = appUser.name ?: ""
        viewModel.profile.value = appUser.profile ?: ""
        viewModel.cloudDrivePort.value = if (appUser.cloudDrivePort == 0) "" else appUser.cloudDrivePort.toString()
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
            },
            onUploadStart = {
                // Set upload state when upload starts
                isUploading.value = true
                // Ensure state is observed - the spinner will animate automatically
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
                if (viewModel.usernameError.value.isNotEmpty()) {
                    Text(
                        text = viewModel.usernameError.value,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
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
                if (viewModel.passwordError.value.isNotEmpty()) {
                    Text(
                        text = viewModel.passwordError.value,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
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
                    if (viewModel.confirmPasswordError.value.isNotEmpty()) {
                        Text(
                            text = viewModel.confirmPasswordError.value,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { viewModel.onNameChange(it) },
                    label = { Text(stringResource(R.string.name)) },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true
                )
                OutlinedTextField(
                    value = profile,
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
                    label = {
                        val labelText = if (hostIdFocused.value) {
                            appUser.hostIds?.firstOrNull() ?: stringResource(R.string.host_id)
                        } else {
                            stringResource(R.string.host_id)
                        }
                        Text(labelText)
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            hostIdFocused.value = focusState.isFocused
                        },
                    singleLine = false
                )
                if (viewModel.hostIdError.value.isNotEmpty()) {
                    Text(
                        text = viewModel.hostIdError.value,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
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
                if (viewModel.cloudDrivePortError.value.isNotEmpty()) {
                    Text(
                        text = viewModel.cloudDrivePortError.value,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
                OutlinedTextField(
                    value = domainToShare,
                    onValueChange = { viewModel.onDomainToShareChange(it) },
                    label = {
                        val labelText = if (domainToShareFocused.value) {
                            activityViewModel.systemDomainToShare.value ?: ""
                        } else {
                            stringResource(R.string.domain_to_share)
                        }
                        Text(labelText)
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(domainToShareFocusRequester)
                        .onFocusChanged { focusState ->
                            domainToShareFocused.value = focusState.isFocused
                        },
                    singleLine = true
                )
            }
            Button(
                onClick = {
                    // Prevent rapid clicks (debounce mechanism like iOS DebounceButton)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastClickTime.longValue < debounceDuration) {
                        return@Button
                    }
                    lastClickTime.longValue = currentTime

                    // Additional check for loading state
                    if (isLoading) return@Button

                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        // Clear previous validation errors
                        viewModel.usernameError.value = ""
                        viewModel.passwordError.value = ""
                        viewModel.confirmPasswordError.value = ""
                        viewModel.hostIdError.value = ""
                        viewModel.cloudDrivePortError.value = ""

                        // Validate cloud drive port if provided
                        if (cloudDrivePort.isNotEmpty()) {
                            val port = cloudDrivePort.toIntOrNull()
                            if (port == null || port < 8000 || port > 65535) {
                                viewModel.cloudDrivePortError.value = "Port must be between 8000 and 65535"
                                return@launch
                            }
                        }

                        if (password.isNotEmpty() && password != confirm.value) {
                            viewModel.confirmPasswordError.value = context.getString(R.string.confirm_pwd)
                            return@launch
                        }
                        viewModel.register(context) {
                            // Callback is not used by register function - navigation is handled internally
                            // Registration success message is shown in register function for new registrations only
                            viewModel.viewModelScope.launch(Dispatchers.Main) {
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
        // Only show full-screen spinner if NOT uploading avatar (to avoid duplicate spinners)
        if (isLoading && !isUploading.value) {
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
