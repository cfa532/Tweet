package us.fireshare.tweet.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * UI utility functions for common UI behaviors across the app.
 */

private data object BottomBarTransparency {
    const val VISIBLE = 0.98f
    const val INVISIBLE = 0.3f
}

/**
 * Creates a delayed transparency state for bottom bar visibility based on scrolling state.
 * 
 * @param isScrolling Whether the user is currently scrolling
 * @return State<Float> representing the transparency value
 */
@Composable
fun rememberDelayedBottomBarTransparency(isScrolling: Boolean): State<Float> {
    val transparency = remember { mutableFloatStateOf(
        if (isScrolling) BottomBarTransparency.INVISIBLE else BottomBarTransparency.VISIBLE) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Use a LaunchedEffect to manage the coroutine and delay
    LaunchedEffect(isScrolling) {
        if (!isScrolling) {
            // If not scrolling, start the delay and update transparency
            delay(2000) // Wait for 2 seconds
            transparency.floatValue = BottomBarTransparency.VISIBLE
        } else {
            // If scrolling, immediately set transparency to 0.3f
            transparency.floatValue = BottomBarTransparency.INVISIBLE
        }
    }

    // Reset transparency when the composable is first created
    // and when the lifecycle is resumed. This ensures the bottom bar is
    // visible when the app is first launched or when returning from the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                transparency.floatValue = BottomBarTransparency.VISIBLE
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return transparency
} 