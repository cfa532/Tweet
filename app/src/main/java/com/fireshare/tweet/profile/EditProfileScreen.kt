package com.fireshare.tweet.profile

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.R
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.UserAvatar

@Composable
fun EditProfileScreen(
    navController: NavHostController,
    viewModel: UserViewModel
) {
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val username by viewModel.username
    val password by viewModel.password
    val keyPhrase by viewModel.keyPhrase
    val name by viewModel.name
    val profile by viewModel.profile
    val isPasswordVisible by viewModel.isPasswordVisible
    val isLoading by viewModel.isLoading

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.updateAvatar(context, uri)
        }
    }
    val scrollState = rememberScrollState()
    val showDialog = remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
//        focusRequester.requestFocus()
        if (!viewModel.isLoggedIn())
            viewModel.hidePhrase()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .imePadding()
            .verticalScroll(scrollState)
//            .nestedScroll(scrollState)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        IconButton(onClick = { navController.popBackStack() })
        {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = "Cancel"
            )
        }
        // show and edit user avatar
        AvatarSection(launcher)

        Spacer(modifier = Modifier.height(16.dp))
        Column {
            OutlinedTextField(
                value = username ?: "",
                onValueChange = { viewModel.onUsernameChange(it) },
                label = { Text(stringResource(R.string.usename)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { viewModel.onPasswordChange(it) },
                label = { Text(stringResource(R.string.password)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    val image = if (isPasswordVisible) Icons.Default.Star else Icons.Default.Lock
                    IconButton(onClick = { viewModel.onPasswordVisibilityChange() }) {
                        Icon(imageVector = image, contentDescription = null)
                    }
                },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = keyPhrase ?: "",
                onValueChange = { viewModel.onKeyPhraseChange(it) },
                label = { Text(stringResource(R.string.key_phrase)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && keyPhrase.isNullOrBlank()) {
                            showDialog.value = true
                        }
                    },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = name ?: "",
                onValueChange = { viewModel.onNameChange(it) },
                label = { Text(stringResource(R.string.name)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = profile ?: "",
                onValueChange = { viewModel.onProfileChange(it) },
                label = { Text(stringResource(R.string.profile)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = false
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                viewModel.register(context) {
                    navController.popBackStack()
                }
            },
            enabled = !isLoading,
            modifier = Modifier
                .width(intrinsicSize = IntrinsicSize.Max)
                .align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.save))
        }
    }
    if (showDialog.value) {
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            confirmButton = {
                TextButton(onClick = { showDialog.value = false }) {
                    Text("OK")
                }
            },
            text = { Text(stringResource(R.string.key_phrase_warning)) }
        )
    }
}

@Composable
fun AvatarSection( launcher: ManagedActivityResultLauncher<String, Uri?>
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = { launcher.launch("image/*") })
        ) {
            UserAvatar(appUser, 120)
        }
    }
}
