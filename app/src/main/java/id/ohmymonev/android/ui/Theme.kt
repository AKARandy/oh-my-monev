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
    secondary = Color(0xFF0A3D91),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7F0FF),
    onSecondaryContainer = Color(0xFF101215),
    tertiary = Color(0xFFE88D67),
    onTertiary = Color(0xFF0A3D91),
    tertiaryContainer = Color(0xFFFFF3E0),
    onTertiaryContainer = Color(0xFF5C3200),
    background = Color(0xFFF5F8FF),
    onBackground = Color(0xFF101215),
    surface = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFF0A6BFF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F8FF),
    surfaceContainer = Color(0xFFF5F8FF),
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE7F0FF),
    onSurface = Color(0xFF101215),
    surfaceVariant = Color(0xFFF6F7F9),
    onSurfaceVariant = Color(0xFF5F6570),
    outline = Color(0xFFC9CDD4),
    outlineVariant = Color(0xFFE6E8EC),
    error = Color(0xFFDB162F),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF7A1210),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            color = Color(0xFF0A3D91),
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.6).sp,
        ),
        titleLarge = base.titleLarge.copy(
            color = Color(0xFF0A3D91),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        titleSmall = base.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = base.bodySmall.copy(fontSize = 15.sp, lineHeight = 22.sp),
        labelSmall = base.labelSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    )
}

@Composable
fun OhMyMonevTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
