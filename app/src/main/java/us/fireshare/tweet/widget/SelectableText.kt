package us.fireshare.tweet.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import us.fireshare.tweet.R
import us.fireshare.tweet.tweet.TweetExpansionCache
import us.fireshare.tweet.widget.Gadget.buildAnnotatedText

@Composable
fun SelectableText(
    modifier: Modifier = Modifier,
    text: String,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onTextClick: (() -> Unit)? = null, // Callback for when text (not username) is clicked
    // Optional id (e.g. tweet.mid) used to flag this row as currently expanded
    // in [TweetExpansionCache]. The flag tells the surrounding row to skip
    // caching its (transient) expanded height in TweetHeightCache, so when the
    // user scrolls or navigates away and back the row collapses cleanly
    // without leaving empty space behind.
    // Placed before `callback` so trailing-lambda call sites still bind the
    // lambda to `callback`.
    expansionKey: String? = null,
    callback: (String) -> Unit = {} // Callback for when username is clicked
)
{
    // Plain `remember`: when the row is disposed (scroll-away or nav-away)
    // and remounted, isExpanded resets to false — the user "moved on" and
    // the tweet should fold back to its collapsed state.
    var isExpanded by remember { mutableStateOf(false) }
    var lineCount by remember { mutableIntStateOf(0) }

    // Always clear the shared flag on disposal so a remount starts fresh
    // (and so we never leak a stale `true` after the row is gone).
    if (expansionKey != null) {
        DisposableEffect(expansionKey) {
            onDispose { TweetExpansionCache.setExpanded(expansionKey, false) }
        }
    }

    val annotatedText = buildAnnotatedText(text)
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    SelectionContainer {
        Text(
            text = annotatedText,
            maxLines = if (isExpanded) Int.MAX_VALUE else maxLines,
            onTextLayout = { textLayoutResult ->
                lineCount = textLayoutResult.lineCount
                layoutResult = textLayoutResult
            },
            style = style,
            color = color,
            modifier = modifier
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        layoutResult?.let { textLayoutResult ->
                            val position = textLayoutResult.getOffsetForPosition(offset)
                            val annotations = annotatedText.getStringAnnotations(
                                tag = "USERNAME_CLICK",
                                start = position,
                                end = position
                            )
                            if (annotations.isNotEmpty()) {
                                val username = annotations[0].item
                                callback(username)  // navigate to the user account
                            } else {
                                // Not clicking on a username, trigger text click callback if provided
                                onTextClick?.invoke()
                            }
                        } ?: run {
                            // No layout result yet, trigger text click callback if provided
                            onTextClick?.invoke()
                        }
                    }
                },
        )
    }
    if (!isExpanded && lineCount >= maxLines) {
        Text(
            text = stringResource(R.string.show_more),
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary),
            modifier = modifier.clickable {
                isExpanded = true
                if (expansionKey != null) {
                    TweetExpansionCache.setExpanded(expansionKey, true)
                }
            }
        )
    }
}