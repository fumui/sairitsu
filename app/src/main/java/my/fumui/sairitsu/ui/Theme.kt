package my.fumui.sairitsu.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SairitsuColors = darkColorScheme(
    primary = Color(0xFF7FD3FF),
    onPrimary = Color(0xFF00344A),
    secondaryContainer = Color(0xFF233A4A),
    onSecondaryContainer = Color(0xFFD2E9FA),
    background = Color(0xFF0E1419),
    surface = Color(0xFF0E1419),
    surfaceVariant = Color(0xFF1C252D),
    onSurfaceVariant = Color(0xFFA9B6C1),
)

@Composable
fun SairitsuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SairitsuColors, content = content)
}

/** Ratings display as whole numbers when they are whole. */
fun formatRating(rating: Double): String =
    if (rating % 1.0 == 0.0) rating.toLong().toString() else rating.toString()
