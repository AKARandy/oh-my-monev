package id.ohmymonev.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Calm, high-contrast base: near-white grouped background, white surfaces,
// one accent, hairline outlines. Color carries meaning, not decoration.
private val Colors = lightColorScheme(
    primary = Color(0xFF0A6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0FF),
    onPrimaryContainer = Color(0xFF0A3D91),
    secondary = Color(0xFF3C4043),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EBEF),
    onSecondaryContainer = Color(0xFF101215),
    tertiary = Color(0xFF3C4043),
    onTertiary = Color.White,
    background = Color(0xFFF3F4F7),
    onBackground = Color(0xFF101215),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101215),
    surfaceVariant = Color(0xFFF6F7F9),
    onSurfaceVariant = Color(0xFF5F6570),
    outline = Color(0xFFC9CDD4),
    outlineVariant = Color(0xFFE6E8EC),
    error = Color(0xFFC5221F),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF7A1210),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.6).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
        bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

@Composable
fun OhMyMonevTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
