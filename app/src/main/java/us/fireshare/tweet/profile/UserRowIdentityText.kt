package us.fireshare.tweet.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

@Composable
fun UserRowIdentityText(
    displayName: String,
    username: String?,
    modifier: Modifier = Modifier
) {
    val handle = username
        ?.takeIf { it.isNotBlank() }
        ?.let { "@$it" }

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                append(displayName)
            }
            if (handle != null) {
                append(" ")
                withStyle(SpanStyle(color = Color.Gray, fontSize = 15.sp)) {
                    append(handle)
                }
            }
        },
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium
    )
}
