package us.fireshare.tweet.widget

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Runs once per visible appearance, including return from the background. */
@Composable
internal fun Modifier.onAttachmentVisible(attachmentKey: Any, onVisible: () -> Unit): Modifier {
    val view = LocalView.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var inViewport by remember(attachmentKey) { mutableStateOf(false) }
    val visibleToUser = inViewport && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // A failure while still visible must not itself trigger another retry.
    LaunchedEffect(attachmentKey, visibleToUser) {
        if (visibleToUser) onVisible()
    }

    return onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val frame = Rect()
        view.getWindowVisibleDisplayFrame(frame)
        inViewport = bounds.width > 0f && bounds.height > 0f &&
            bounds.right > frame.left && bounds.left < frame.right &&
            bounds.bottom > frame.top && bounds.top < frame.bottom
    }
}
