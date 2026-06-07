package com.aigena.messenger

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Aigena dark theme — Material 3 color scheme.
 *
 * All UI elements reference MaterialTheme.colorScheme.*
 * No hardcoded Color() values in composable code.
 */
object AigenaColors {
    // Canvas
    val bgDark = Color(0xFF121418)
    val surfaceDark = Color(0xFF1E222A)
    val accentBlue = Color(0xFF3F8AE0)
    val textMain = Color(0xFFE3E6EB)
    val textSecondary = Color(0xFF8A93A2)

    // Status indicator overrides (not part of Material spec)
    val success = Color(0xFF4CAF50)
    val error = Color(0xFFF44336)

    // Custom bubble colors for chat (semantic, not in standard scheme)
    val userBubble = Color(0xFF2C3E50)
    val aiBubble = Color(0xFF3A3F4A)

    // Border accent for input fields
    val inputBorder = Color(0xFF2A2D35)
    val dividerLine = Color(0xFF2A2D35)
}

val AigenaColorScheme = darkColorScheme(
    primary = Color(0xFF3F8AE0),
    onPrimary = Color(0xFFE3E6EB),
    primaryContainer = Color(0xFF1A2D4A),
    onPrimaryContainer = Color(0xFFE3E6EB),
    secondary = Color(0xFF5B9CE4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1A2D4A),
    onSecondaryContainer = Color(0xFFE3E6EB),
    tertiary = Color(0xFF1E222A),
    onTertiary = Color(0xFFE3E6EB),
    background = Color(0xFF121418),
    onBackground = Color(0xFFE3E6EB),
    surface = Color(0xFF1E222A),
    onSurface = Color(0xFFE3E6EB),
    surfaceVariant = Color(0xFF1E222A),
    onSurfaceVariant = Color(0xFF8A93A2),
    outline = Color(0xFF2A2D35),
    outlineVariant = Color(0xFF2A2D35),
    error = Color(0xFFF44336),
    onError = Color.White,
    errorContainer = Color(0xFFF44336).copy(alpha = 0.15f),
    onErrorContainer = Color(0xFFF44336),
)
