package app.setlog.capture.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SetLogColors = darkColorScheme(
    primary = Color(0xFFFF4D57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5A1118),
    onPrimaryContainer = Color(0xFFFFDADC),
    secondary = Color(0xFFF2F2F4),
    onSecondary = Color(0xFF151517),
    background = Color(0xFF050506),
    onBackground = Color(0xFFF7F7F8),
    surface = Color(0xFF121214),
    onSurface = Color(0xFFF7F7F8),
    surfaceVariant = Color(0xFF202024),
    onSurfaceVariant = Color(0xFFB8B8C0),
    outline = Color(0xFF4C4C54),
    error = Color(0xFFFF6B72),
    onError = Color.White,
)

@Composable
fun SetLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SetLogColors,
        content = content,
    )
}
