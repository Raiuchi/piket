package net.raiuchi.piket

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PiketRed = Color(0xFFE71938)
val PiketRedDark = Color(0xFF9F0F25)
val PiketGraphite = Color(0xFF11151B)
val PiketPanel = Color(0xFF191E26)
val PiketBlue = Color(0xFF8ED7FF)
val PiketYellow = Color(0xFFFFB92E)

private val colors = darkColorScheme(
    primary = PiketRed,
    onPrimary = Color.White,
    secondary = PiketBlue,
    tertiary = PiketYellow,
    background = Color(0xFF05070A),
    surface = PiketGraphite,
    surfaceVariant = PiketPanel,
    onBackground = Color(0xFFF5F7FA),
    onSurface = Color(0xFFF5F7FA)
)

@Composable
fun PiketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

