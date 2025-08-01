package us.fireshare.tweet.chat

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun ChatBubbleShape(isFromCurrentUser: Boolean = false): GenericShape {
    val cornerRadius = 12.dp
    val density = LocalDensity.current

    return GenericShape { size, _ ->
        with(density) {
            val cornerRadiusPx = cornerRadius.toPx()
            val width = size.width
            val height = size.height

            if (isFromCurrentUser) {
                // Right-aligned bubble (sent by current user) - rounded except bottom-right
                // Bottom edge
                moveTo(width - cornerRadiusPx, height)
                lineTo(cornerRadiusPx, height)
                
                // Bottom-left corner curve
                quadraticTo(0f, height, 0f, height - cornerRadiusPx)
                
                // Left edge
                lineTo(0f, cornerRadiusPx)
                
                // Top-left corner curve
                quadraticTo(0f, 0f, cornerRadiusPx, 0f)
                
                // Top edge
                lineTo(width - cornerRadiusPx, 0f)
                
                // Top-right corner curve
                quadraticTo(width, 0f, width, cornerRadiusPx)
                
                // Right edge
                lineTo(width, height)
                
                close()
            } else {
                // Left-aligned bubble (received from other user) - rounded except bottom-left
                // Bottom edge
                moveTo(cornerRadiusPx, height)
                lineTo(width - cornerRadiusPx, height)
                
                // Bottom-right corner curve
                quadraticTo(width, height, width, height - cornerRadiusPx)
                
                // Right edge
                lineTo(width, cornerRadiusPx)
                
                // Top-right corner curve
                quadraticTo(width, 0f, width - cornerRadiusPx, 0f)
                
                // Top edge
                lineTo(cornerRadiusPx, 0f)
                
                // Top-left corner curve
                quadraticTo(0f, 0f, 0f, cornerRadiusPx)
                
                // Left edge
                lineTo(0f, height)
                
                close()
            }
        }
    }
}

@Composable
fun RegularChatBubbleShape(): RoundedCornerShape {
    return RoundedCornerShape(12.dp)
}