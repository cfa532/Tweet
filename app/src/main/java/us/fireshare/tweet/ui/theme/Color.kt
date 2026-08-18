package us.fireshare.tweet.ui.theme

import androidx.compose.ui.graphics.Color

val XBlue = Color(0xFF1D9BF0)
val XRed = Color(0xFFF4212E)

val XLightBackground = Color(0xFFFFFFFF)
val XLightSurfaceVariant = Color(0xFFEFF3F4)
val XLightText = Color(0xFF0F1419)
val XLightSecondaryText = Color(0xFF536471)
val XLightBorder = Color(0xFFCFD9DE)
/** Media-grid outline: a step darker than [XLightBorder] so the grid reads as its own card. */
val XLightMediaGridBorder = Color(0xFFB0BAC0)

val XDarkBackground = Color(0xFF000000)
val XDarkSurfaceVariant = Color(0xFF16181C)
val XDarkText = Color(0xFFE7E9EA)
val XDarkSecondaryText = Color(0xFF71767B)
val XDarkBorder = Color(0xFF2F3336)
/**
 * Media-grid outline in dark mode. Slightly *lighter* than [XDarkBorder], not darker:
 * going darker would sink the border into the background, losing the same emphasis
 * [XLightMediaGridBorder] adds in light mode.
 */
val XDarkMediaGridBorder = Color(0xFF42474B)
