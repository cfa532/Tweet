package us.fireshare.tweet.widget

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Own the dim layer in Compose so the feed stays visible during a downward exit. */
@Composable
internal fun FullscreenMediaWindow(): Window {
    val window = (LocalView.current.parent as DialogWindowProvider).window
    DisposableEffect(window) {
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
        onDispose { }
    }
    return window
}

/** Match Tweet-iOS: black lightens to 80% opacity over 35% of screen height. */
internal fun Modifier.fullscreenDismissBackdrop(downwardOffset: () -> Float): Modifier = drawBehind {
    val progress = (downwardOffset().coerceAtLeast(0f) / (size.height * 0.35f).coerceAtLeast(1f)).coerceAtMost(1f)
    drawRect(Color.Black.copy(alpha = 1f - 0.2f * progress))
}
