package dev.catarina.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Cream = Color(0xFFFFF6EC)
private val Rose = Color(0xFFE5527E)
private val Sky = Color(0xFF5FA8DC)
private val DeepPlum = Color(0xFF3A2233)

private val LightColors = lightColorScheme(
    primary = Rose,
    secondary = Sky,
    background = Cream,
    surface = Cream,
    surfaceVariant = Color(0xFFF6E3D7),
    onPrimary = Color.White,
    onBackground = DeepPlum,
    onSurface = DeepPlum,
    onSurfaceVariant = Color(0xFF6B5147),
)

private val DarkColors = darkColorScheme(
    primary = Rose,
    secondary = Sky,
    background = Color(0xFF1C1418),
    surface = Color(0xFF1C1418),
    surfaceVariant = Color(0xFF2A1E26),
    onPrimary = Color.White,
    onBackground = Color(0xFFF3E4DC),
    onSurface = Color(0xFFF3E4DC),
    onSurfaceVariant = Color(0xFFCBB3A6),
)

/** Tema mínimo do app — paleta quente combinando com a mascote. */
@Composable
fun CatarinaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
