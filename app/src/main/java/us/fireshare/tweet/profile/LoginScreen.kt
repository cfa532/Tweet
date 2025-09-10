package us.fireshare.tweet.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.viewmodel.UserViewModel

@Composable
fun LoginScreen(register: ()->Unit, popBack: ()->Unit) {
    val focusManager = LocalFocusManager.current
    val viewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory> { factory ->
        factory.create(appUser.mid)
    }
    val username by viewModel.username
    val password by viewModel.password
    val isPasswordVisible by viewModel.isPasswordVisible
    val isLoading by viewModel.isLoading.collectAsState()
    val loginError by viewModel.loginError
    val scrollState = rememberScrollState()
    LaunchedEffect(Unit) {
        viewModel.loginError.value = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        IconButton(
            onClick = { popBack() },
            enabled = !isLoading,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(16.dp)
        ) {
            Icon(imageVector = Icons.Default.Close,
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = stringResource(R.string.cancel))
        }

        Text(text = "Login", fontSize = 32.sp, color = Color.Black)

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = username ?: "",
            onValueChange = { viewModel.onUsernameChange(it)},
            label = { Text(stringResource(R.string.username)) },
            singleLine = true,
            enabled = !isLoading,
            textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { viewModel.onPasswordChange(it) },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            enabled = !isLoading,
            textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.primary),
            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { EyeSlashButton(viewModel, isPasswordVisible) },
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        val context = LocalContext.current
        Button(
            onClick = {
                viewModel.viewModelScope.launch(Dispatchers.IO) {
                    viewModel.login(context, {
                        popBack()
                    })
                } },
            modifier = Modifier.width(intrinsicSize = IntrinsicSize.Max),
            enabled = !isLoading    // disable Login button during loading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(text = stringResource(R.string.login))     // Login
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = loginError, color = Color.Red)
        
        // Show retry button if there's an error and not currently loading
        if (loginError.isNotEmpty() && !isLoading) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        viewModel.login(context, {
                            popBack()
                        }, maxRetries = 3)
                    }
                },
                modifier = Modifier.width(intrinsicSize = IntrinsicSize.Max),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800) // Orange color
                )
            ) {
                Text(text = "Retry Login", color = Color.White)
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))

        val annotatedText = buildAnnotatedString {
            append(stringResource(R.string.no_account))
            append(" ")
            pushStringAnnotation(tag = "REGISTER", annotation = "register")
            withStyle(style = SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline)) {
                append(stringResource(R.string.register))
            }
            pop()
        }

        Text(
            color = if (isLoading) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
            text = annotatedText,
            modifier = Modifier.clickable(enabled = !isLoading) {
                register()
            }
        )
    }
}