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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.TweetApplication
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.service.SnackbarAction
import com.fireshare.tweet.service.SnackbarEvent
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun EditProfileScreen(
    navController: NavHostController,
) {
    val viewModel =
        hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(key = appUser.mid) { factory ->
            factory.create(appUser.mid)
        }
    val focusManager = LocalFocusManager.current
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
            viewModel.updateAvatar(context, appUser.mid, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        ProfileTopAppBar(navController)
        Spacer(modifier = Modifier.height(16.dp))
        AvatarSection(viewModel, launcher)
        Spacer(modifier = Modifier.height(16.dp))
        Column {
            OutlinedTextField(
                value = username ?: "",
                onValueChange = { viewModel.onUsernameChange(it) },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { viewModel.onPasswordChange(it) },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
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
                label = { Text("Key phrase") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = name ?: "",
                onValueChange = { viewModel.onNameChange(it) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = profile ?: "",
                onValueChange = { viewModel.onProfileChange(it) },
                label = { Text("Profile") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                viewModel.register()
                navController.popBackStack()
                      },
            enabled = !isLoading,
            modifier = Modifier
                .width(intrinsicSize = IntrinsicSize.Max)
                .align(Alignment.CenterHorizontally)
        ) {
            Text("     Save     ")
        }
    }
}

@Composable
fun AvatarSection( viewModel: UserViewModel,
    launcher: ManagedActivityResultLauncher<String, Uri?>) {
    val user by viewModel.user.collectAsState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = { launcher.launch("image/*") })
        ) {
            UserAvatar(user, 200)
        }
    }
}
