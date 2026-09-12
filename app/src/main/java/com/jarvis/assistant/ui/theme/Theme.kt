package com.jarvis.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val JarvisCyan = Color(0xFF4CC9F0)
private val JarvisCyanDim = Color(0xFF2A9CB8)
private val JarvisBg = Color(0xFF0B0F14)
private val JarvisSurface = Color(0xFF121826)
private val JarvisSurfaceHigh = Color(0xFF1A2233)
private val JarvisOnSurface = Color(0xFFE8EEF7)
private val JarvisMuted = Color(0xFF8B97AB)
private val JarvisError = Color(0xFFFF6B7A)

private val JarvisColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = Color(0xFF041018),
    primaryContainer = JarvisCyanDim,
    onPrimaryContainer = JarvisOnSurface,
    secondary = Color(0xFF7BDFF2),
    onSecondary = Color(0xFF041018),
    background = JarvisBg,
    onBackground = JarvisOnSurface,
    surface = JarvisSurface,
    onSurface = JarvisOnSurface,
    surfaceVariant = JarvisSurfaceHigh,
    onSurfaceVariant = JarvisMuted,
    error = JarvisError,
    onError = Color(0xFF1A0508),
    outline = Color(0xFF2E3A4F)
)

val PanelShape = RoundedCornerShape(28.dp)
val ButtonShape = RoundedCornerShape(16.dp)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        typography = MaterialTheme.typography.copy(
            displaySmall = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                letterSpacing = 1.2.sp
            ),
            headlineSmall = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp,
                letterSpacing = 0.4.sp
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 26.sp
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = 0.3.sp
            )
        ),
        content = content
    )
}
