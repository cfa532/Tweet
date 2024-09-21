package com.fireshare.tweet.chat

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun ChatBubbleShape(): GenericShape {
    val cornerRadius = 8.dp
    val tailWidth = 12.dp
    val tailHeight = 8.dp
    val padding = 2.dp // Add padding inside the bubble
    val density = LocalDensity.current

    return GenericShape { size, _ ->
        with(density) {
            val cornerRadiusPx = cornerRadius.toPx()
            val tailWidthPx = tailWidth.toPx()
            val tailHeightPx = tailHeight.toPx()
            val paddingPx = padding.toPx()

            // Draw the main rounded rectangle with padding
            addRoundRect(
                RoundRect(
                    left = paddingPx,
                    top = paddingPx,
                    right = size.width - paddingPx,
                    bottom = size.height - tailHeightPx + paddingPx,
                    topLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    topRightCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    bottomLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    bottomRightCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )
            )

            // Draw the tail pointing outward from the bottom left corner
            moveTo(paddingPx + cornerRadiusPx, size.height - tailHeightPx - paddingPx)
            lineTo(paddingPx + cornerRadiusPx + tailWidthPx / 2, size.height - paddingPx)
            lineTo(paddingPx + cornerRadiusPx + tailWidthPx, size.height - tailHeightPx - paddingPx)
            close()
        }
    }
}