package us.fireshare.tweet.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance.dao
import us.fireshare.tweet.viewmodel.UserViewModel
import us.fireshare.tweet.widget.SelectableText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemSettings(navController: NavController, appUserViewModel: UserViewModel) {
    val appUser by appUserViewModel.user.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Column {
                        UserAvatar(user = appUser, size = 36)
                        Text(
                            text = appUser.name ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp, bottom = 0.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() })
                    {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxWidth()
            .padding(innerPadding)
            .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
//            var appUrl by remember { mutableStateOf(HproseInstance.preferenceHelper.getAppUrls() ?: "") }
//            val focusRequester = remember { FocusRequester() }
//            OutlinedTextField(
//                value = appUrl ?: "",
//                onValueChange = { appUrl = it },
//                label = { Text("App Url") },
//                modifier = Modifier
//                    .padding(top = 8.dp)
//                    .fillMaxWidth()
//                    .focusRequester(focusRequester),
//                singleLine = true,
//            )
//            Button(onClick = {
//                HproseInstance.preferenceHelper.setAppUrls(
//                    appUrl ?: HproseInstance.preferenceHelper.getAppUrls()!!
//                ) },
//                modifier = Modifier
//                    .padding(top = 16.dp)
//                    .width(intrinsicSize = IntrinsicSize.Max)
//                    .align(Alignment.CenterHorizontally)
//            ) {
//                Text(stringResource(R.string.save))
//            }
            var isCachedCleared by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Clear all cached data")
                Button(onClick = {
                    appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                        dao.clearAllCachedTweets()
                        isCachedCleared = true
                    } },
                    enabled = !isCachedCleared
                ) {
                    Text("Clear")
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("Privacy policy",
                modifier = Modifier
                    .clickable { showDialog = true }
                    .background(MaterialTheme.colorScheme.onTertiary,
                        shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .width(intrinsicSize = IntrinsicSize.Max),
                color = MaterialTheme.colorScheme.primary
            )
            Text("Version: ${BuildConfig.VERSION_NAME}",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
            SelectableText(
                modifier = Modifier.padding(top = 8.dp),
                text = appUser.mid,
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (showDialog) {
            BasicAlertDialog(
                onDismissRequest = { showDialog = false }
            ) {
                ConstraintLayout(
                    modifier = Modifier
                        .width(500.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                ) {
                    val (_, button) = createRefs()
                    LazyColumn(
                        modifier = Modifier
                            .padding(8.dp)
                            .height(800.dp)
                    ) {
                        item {
                            Text("\nPrivacy Policy\n" +
                                        "\n" +
                                        "We operate the Tweet mobile application (the \"App\"). This page informs you of our policies regarding the collection, use, and disclosure of Personal Information when you use our App.\n" +
                                        "\n" +
                                        "Information Collection and Use\n" +
                                        "\n" +
                                        "We collect several types of information for various purposes to provide and improve our App for you.\n" +
                                        "\n" +
                                        "Types of Data Collected\n" +
                                        "\n" +
                                        "Personal Data: While using our App, we may ask you to provide us with certain personally identifiable information, such as your name, email address.\n" +
                                        "\n" +
                                        "Usage Data: We may collect information on how the App is accessed and used, such as your device's Internet Protocol address (e.g., IP address), browser type, browser version, the pages of our App that you visit, the time and date of your visit, and other diagnostic data.\n" +
                                        "\n" +
                                        "Cookies and Tracking Technologies: We use cookies and similar tracking technologies to track the activity on our App and hold certain information.\n" +
                                        "\n" +
                                        "Use of Data\n" +
                                        "\n" +
                                        "We use the collected data for various purposes:\n" +
                                        "\n" +
                                        "To provide and maintain our App\n" +
                                        "To notify you about changes to our App\n" +
                                        "To allow you to participate in interactive features of our App when you choose to do so\n" +
                                        "To provide customer support\n" +
                                        "To gather analysis or valuable information so that we can improve our App\n" +
                                        "To monitor the usage of our App\n" +
                                        "To detect, prevent, and address technical issues\n" +
                                        "Data Security\n" +
                                        "\n" +
                                        "The security of your data is important to us, but remember that no method of transmission over the Internet is 100% secure. While we try our best to protect you data, there is always potential leakholes. Do not disclose sensitive personal information on this App.",
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            TextButton(
                                onClick = { showDialog = false },
                                modifier = Modifier.constrainAs(button) {
                                    bottom.linkTo(parent.bottom)
                                    centerHorizontallyTo(parent)
                                },
                            ) {
                                Text("Confirm",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}