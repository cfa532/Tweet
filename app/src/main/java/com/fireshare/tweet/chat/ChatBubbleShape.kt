package com.fireshare.tweet.chat

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

class CustomBubbleShape : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply {
            // Define the custom bubble shape path here
            moveTo(0f, size.height)
            lineTo(0f, 0f)
            lineTo(size.width * 0.8f, 0f)
            lineTo(size.width, size.height * 0.2f)
            lineTo(size.width, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}