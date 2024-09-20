package com.fireshare.tweet.chat

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Path

@Composable
fun ChatBubbleShape(): GenericShape {
    val cornerRadius = 16.dp
    val tailWidth = 8.dp
    val tailHeight = 12.dp
    val density = LocalDensity.current

    return GenericShape { size, _ ->
        with(density) {
            val cornerRadiusPx = cornerRadius.toPx()
            val tailWidthPx = tailWidth.toPx()
            val tailHeightPx = tailHeight.toPx()

            // Draw the main rounded rectangle
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width - tailWidthPx,
                    bottom = size.height,
                    topLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    topRightCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    bottomLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    bottomRightCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )
            )

            // Draw the tail
            moveTo(size.width - tailWidthPx, size.height / 2 - tailHeightPx / 2)
            lineTo(size.width, size.height / 2)
            lineTo(size.width - tailWidthPx, size.height / 2 + tailHeightPx / 2)
            close()
        }
    }
}