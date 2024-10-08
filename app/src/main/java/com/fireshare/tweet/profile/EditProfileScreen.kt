@file:JvmName("EditProfileScreenKt")

package com.fireshare.tweet.profile

import PreferencesHelper
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.network.HproseInstance
import com.fireshare.tweet.network.HproseInstance.appUser
import com.fireshare.tweet.network.HproseInstance.getMediaUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditProfileScreen(
    navController: NavHostController,
    preferencesHelper: PreferencesHelper,
) {
    var username by rememberSaveable { mutableStateOf(preferencesHelper.getUsername() ?: "NoOne") }
    var name by rememberSaveable { mutableStateOf(preferencesHelper.getName() ?: "No One") }
    var profile by rememberSaveable { mutableStateOf(preferencesHelper.getProfile() ?: "My cool profile") }
    var avatar by rememberSaveable { mutableStateOf(appUser.avatar) }
    val user by remember { mutableStateOf<User?>(appUser) }

//    appUser.avatar?.let { avatar = it }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(it)?.let { stream ->
                            val mimeiId = HproseInstance.uploadToIPFS(stream)
                            avatar = mimeiId
                            user?.let { user ->
                                user.avatar = mimeiId
                                HproseInstance.setUserData(user)
                            }
                        }
                    } catch (e: Exception) {
                        // Handle error
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        ProfileTopAppBar(navController)
        Spacer(modifier = Modifier.height(16.dp))
        AvatarSection(avatar, launcher)
        Spacer(modifier = Modifier.height(16.dp))
        PreferencesForm(
            username = username,
            name = name,
            profile = profile,
            onUsernameChange = { newUsername -> username = newUsername },
            onNameChange = { newName -> name = newName },
            onProfileChange = { newProfile -> profile = newProfile}
        )
        SaveButton(preferencesHelper, username, name, profile, user, coroutineScope)
    }
}

@Composable
fun AvatarSection(avatar: MimeiId?, launcher: ManagedActivityResultLauncher<String, Uri?>) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(100.dp) // Adjust the size as needed
                .clip(CircleShape)
                .clickable(onClick = { launcher.launch("image/*") })
        ) {
            Image(
                painter = rememberAsyncImagePainter(appUser.baseUrl?.let { getMediaUrl(
                    avatar, it) }),
                contentDescription = null,
                contentScale = ContentScale.Crop, // Ensure the image area is as large as possible within the round button
                modifier = Modifier.size(100.dp) // Ensure the image fits the button size
            )
        }
    }
}

@Composable
fun PreferencesForm(
    username: String,
    name: String,
    profile: String,
    onUsernameChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onProfileChange: (String) -> Unit,
) {
    Column {
        TextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = profile,
            onValueChange = onProfileChange,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SaveButton(
    preferencesHelper: PreferencesHelper,
    username: String,
    name: String,
    profile: String,
    user: User?,
    coroutineScope: CoroutineScope
) {
    Button(
        onClick = {
            preferencesHelper.saveUsername(username)
            preferencesHelper.saveName(name)
            preferencesHelper.saveProfile(profile)
            user?.let {
                it.username = username
                it.name = name
                it.profile = profile
                coroutineScope.launch(Dispatchers.Default) {
                    HproseInstance.setUserData(it)
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .width(intrinsicSize = IntrinsicSize.Min)
    ) {
        Text("Save")
    }
}