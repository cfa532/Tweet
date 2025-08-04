package us.fireshare.tweet.profile

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.viewmodel.UserViewModel

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
    val hostId by viewModel.hostId
    val isPasswordVisible by viewModel.isPasswordVisible
    val isLoading by viewModel.isLoading.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.updateAvatar(context, uri)
            }
        }
    }
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
    }

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
            Spacer(modifier = Modifier.height(24.dp))
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.cancel)
                )
            }
            // AppUser avatar
            if (!appUser.isGuest()) {
                AppUserAvatar(launcher, viewModel)
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

@Composable
fun AppUserAvatar(
    launcher: ManagedActivityResultLauncher<String, Uri?>,
    viewModel: UserViewModel
) {
    val user by viewModel.user.collectAsState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.size(120.dp) // Set a fixed size for the avatar area
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize() // Ensure the avatar fills the parent Box
                    .clip(CircleShape)
                    .clickable(onClick = { launcher.launch("image/*") })
            ) {
                UserAvatar(user = user, size = 120)
            }
            IconButton(
                onClick = { launcher.launch("image/*") },
                modifier = Modifier
                    .align(Alignment.TopEnd) // Align the IconButton to the bottom end
                    .offset(x = (40).dp, y = (-8).dp) // Adjust offset to position it outside the avatar
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_photo_plus),
                    contentDescription = stringResource(R.string.change_avatar),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
