package us.fireshare.tweet.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import us.fireshare.tweet.widget.Gadget.buildAnnotatedText

@Composable
fun SelectableText(
    modifier: Modifier = Modifier,
    text: String,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    callback: (String) -> Unit = {}
)
{
    // fold text content up to 10 lines. Open it upon user click.
    var isExpanded by remember { mutableStateOf(false) }
    var lineCount by remember { mutableIntStateOf(0) }

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
                            }
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
            }
        )
    }
}