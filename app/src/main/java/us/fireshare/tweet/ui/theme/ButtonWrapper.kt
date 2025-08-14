package us.fireshare.tweet.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

/**
 * A reusable button wrapper that prevents rapid re-submission and shows animation when tapped.
 * This wrapper preserves the original button's appearance by not applying any default styling.
 * 
 * @param text The text to display on the button
 * @param onClick The action to perform when the button is clicked
 * @param enabled Whether the button is enabled (default: true)
 * @param debounceTimeMs Time in milliseconds to prevent rapid re-submission (default: 1000ms)
 * @param textColor The color of the text (caller must specify)
 * @param textStyle The text style to apply (caller must specify)
 * @param modifier Additional modifier to apply to the button (caller must specify)
 */
@Composable
fun DebouncedButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    debounceTimeMs: Long = 1000,
    textColor: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    var isClickable by remember { mutableStateOf(true) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Animation for button press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "button_scale"
    )
    
    // Debounce logic
    LaunchedEffect(isClickable) {
        if (!isClickable) {
            delay(debounceTimeMs)
            isClickable = true
        }
    }
    
    Text(
        text = text,
        color = if (enabled && isClickable) textColor else textColor.copy(alpha = 0.6f),
        style = textStyle,
        modifier = modifier
            .scale(scale)
            .clickable(
                enabled = enabled && isClickable,
                interactionSource = interactionSource,
                indication = null // Remove ripple effect for custom animation
            ) {
                if (enabled && isClickable) {
                    isClickable = false
                    onClick()
                }
            }
    )
}

/**
 * A specialized version of DebouncedButton for icon-based navigation buttons.
 * 
 * @param onClick The action to perform when the button is clicked
 * @param enabled Whether the button is enabled (default: true)
 * @param debounceTimeMs Time in milliseconds to prevent rapid re-submission (default: 1000ms)
 * @param modifier Modifier for the component
 * @param icon The icon to display
 */
@Composable
fun DebouncedIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    debounceTimeMs: Long = 1000,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    var isClickable by remember { mutableStateOf(true) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Animation for button press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "icon_button_scale"
    )
    
    // Debounce logic
    LaunchedEffect(isClickable) {
        if (!isClickable) {
            delay(debounceTimeMs)
            isClickable = true
        }
    }
    
    androidx.compose.material3.IconButton(
        onClick = {
            if (enabled && isClickable) {
                isClickable = false
                onClick()
            }
        },
        enabled = enabled && isClickable,
        modifier = modifier.scale(scale),
        interactionSource = interactionSource
    ) {
        icon()
    }
}
