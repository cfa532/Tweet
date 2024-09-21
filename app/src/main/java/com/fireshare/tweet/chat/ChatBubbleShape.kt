package com.fireshare.tweet.chat

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun ChatBubbleShape(flipHorizontally: Boolean = false): GenericShape {
    val cornerRadius = 8.dp
    val tailWidth = 12.dp
    val tailHeight = 12.dp
    val padding = 2.dp // Add padding inside the bubble
    val shiftLeft = 4.dp // Shift the shape to the left
    val expandWidth = 2.dp // Expand the rectangle by 2.dp on each side
    val density = LocalDensity.current

    return GenericShape { size, _ ->
        with(density) {
            val cornerRadiusPx = cornerRadius.toPx()
            val tailWidthPx = tailWidth.toPx()
            val tailHeightPx = tailHeight.toPx()
            val paddingPx = padding.toPx()
            val shiftLeftPx = shiftLeft.toPx()
            val expandWidthPx = expandWidth.toPx()

            if (flipHorizontally) {
                // Draw the main rounded rectangle with padding, flipped horizontally
                addRoundRect(
                    RoundRect(
                        left = -tailWidthPx / 2 + paddingPx + shiftLeftPx - expandWidthPx, // Adjust left to account for the tail, shift, and expansion
                        top = paddingPx,
                        right = size.width - paddingPx + shiftLeftPx + expandWidthPx, // Adjust right to account for the expansion
                        bottom = size.height - 2 * paddingPx,
                        topLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        topRightCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        bottomLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        bottomRightCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                    )
                )

                // Draw the tail at the right side, pointing outward
                moveTo(size.width - paddingPx - shiftLeftPx, size.height / 2 + tailHeightPx / 2)
                lineTo(size.width + shiftLeftPx, size.height / 2)
                lineTo(size.width - paddingPx - shiftLeftPx, size.height / 2 + tailHeightPx / 2)
                close()
            } else {
                // Draw the main rounded rectangle with padding
                addRoundRect(
                    RoundRect(
                        left = tailWidthPx / 2 + paddingPx - shiftLeftPx - expandWidthPx, // Adjust left to account for the tail, shift, and expansion
                        top = paddingPx,
                        right = size.width + paddingPx - shiftLeftPx + expandWidthPx, // Adjust right to account for the expansion
                        bottom = size.height - 2 * paddingPx,
                        topLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        topRightCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        bottomLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        bottomRightCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                    )
                )

                // Draw the tail at the left side, pointing outward
                moveTo(paddingPx + shiftLeftPx, size.height / 2 - tailHeightPx / 2)
                lineTo(-shiftLeftPx, size.height / 2)
                lineTo(paddingPx + shiftLeftPx, size.height / 2 + tailHeightPx / 2)
                close()
            }
        }
    }
}