package com.fireshare.tweet.profile

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
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
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.UserViewModel

@Composable
fun LoginScreen() {
    val viewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(key = TW_CONST.GUEST_ID) {
            factory -> factory.create(TW_CONST.GUEST_ID)
    }
    val navController = LocalNavController.current
    val focusManager = LocalFocusManager.current
    val username by viewModel.username
    val password by viewModel.password
    val keyPhrase by viewModel.keyPhrase
    val isPasswordVisible by viewModel.isPasswordVisible
    val isLoading by viewModel.isLoading
    val loginError by viewModel.loginError

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        IconButton(onClick = { navController.popBackStack() },
            modifier = Modifier.align(Alignment.Start).padding(16.dp)
        ) {
            Icon(imageVector = Icons.Default.Close,
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = "Cancel")
        }

        Text(text = "Login", fontSize = 32.sp, color = Color.Black)

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = username ?: "",
            onValueChange = { viewModel.onUsernameChange(it)},
            label = { Text(stringResource(R.string.usename)) },
            singleLine = true,
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
            textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.primary),
            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                val image = if (isPasswordVisible) Icons.Default.Star else Icons.Default.Lock
                IconButton(onClick = { viewModel.onPasswordVisibilityChange() }) {
                    Icon(imageVector = image, contentDescription = null)
                }
            },
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        // no valid key phrase on record, ask for it.
        if (viewModel.preferencePhrase?.isEmpty() == true) {
            OutlinedTextField(
                value = keyPhrase ?: "",
                onValueChange = { viewModel.onKeyPhraseChange(it) },
                label = { Text(stringResource(R.string.key_phrase)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions.Default.copy(
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { if (viewModel.login() != null) {
                // refresh tweet feed view
                navController.popBackStack()
            } },
            modifier = Modifier.width(intrinsicSize = IntrinsicSize.Max),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text(text = stringResource(R.string.login))     // Login
            }
        }

        if (loginError.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = stringResource(R.string.login_failed), color = Color.Red)
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
            color = MaterialTheme.colorScheme.primary,
            text = annotatedText,
            modifier = Modifier.clickable {
                navController.navigate(NavTweet.Registration)
            }
        )
    }
}