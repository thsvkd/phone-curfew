package com.thsvkd.curfew.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.thsvkd.curfew.R

/**
 * 성공과 실패는 강조색과 별개의 의미색이다. Material의 색 구성표에 자리가 없어 따로 나른다.
 */
data class CurfewColors(
    val success: Color,
    val failure: Color,
    val muted: Color,
    val chartGrid: Color,
)

val LocalCurfewColors: ProvidableCompositionLocal<CurfewColors> = staticCompositionLocalOf {
    error("CurfewTheme 밖에서 색을 읽었습니다")
}

private val GothicA1 = FontFamily(
    Font(R.font.gothic_a1_regular, FontWeight.Normal),
    Font(R.font.gothic_a1_bold, FontWeight.Bold),
    Font(R.font.gothic_a1_extrabold, FontWeight.ExtraBold),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6C4FE0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE8FD),
    onPrimaryContainer = Color(0xFF6C4FE0),
    background = Color(0xFFF4F2FB),
    onBackground = Color(0xFF282237),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF282237),
    surfaceVariant = Color(0xFFEDE8FD),
    onSurfaceVariant = Color(0xFF7E7695),
    outline = Color(0xFFEAE6F4),
    error = Color(0xFFE0474C),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA98DFF),
    onPrimary = Color(0xFF211B33),
    primaryContainer = Color(0xFF2A2340),
    onPrimaryContainer = Color(0xFFA98DFF),
    background = Color(0xFF141220),
    onBackground = Color(0xFFEFECF8),
    surface = Color(0xFF1F1B2D),
    onSurface = Color(0xFFEFECF8),
    surfaceVariant = Color(0xFF2A2340),
    onSurfaceVariant = Color(0xFF9E96B8),
    outline = Color(0xFF2C2740),
    error = Color(0xFFFF7A7E),
)

private val LightExtras = CurfewColors(
    success = Color(0xFF2FA96A),
    failure = Color(0xFFE0474C),
    muted = Color(0xFF7E7695),
    chartGrid = Color(0xFFEAE6F4),
)

private val DarkExtras = CurfewColors(
    success = Color(0xFF4FCB8B),
    failure = Color(0xFFFF7A7E),
    muted = Color(0xFF9E96B8),
    chartGrid = Color(0xFF2C2740),
)

private val CurfewTypography = Typography().let { base ->
    Typography(
        displaySmall = base.displaySmall.copy(fontFamily = GothicA1, fontWeight = FontWeight.ExtraBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = GothicA1, fontWeight = FontWeight.ExtraBold),
        titleLarge = TextStyle(
            fontFamily = GothicA1,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            letterSpacing = (-0.4).sp,
        ),
        titleMedium = base.titleMedium.copy(fontFamily = GothicA1, fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontFamily = GothicA1, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = GothicA1),
        bodyMedium = base.bodyMedium.copy(fontFamily = GothicA1),
        bodySmall = base.bodySmall.copy(fontFamily = GothicA1),
        labelLarge = base.labelLarge.copy(fontFamily = GothicA1, fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontFamily = GothicA1, fontWeight = FontWeight.Bold),
        labelSmall = base.labelSmall.copy(fontFamily = GothicA1),
    )
}

@Composable
fun CurfewTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCurfewColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = CurfewTypography,
            content = content,
        )
    }
}
