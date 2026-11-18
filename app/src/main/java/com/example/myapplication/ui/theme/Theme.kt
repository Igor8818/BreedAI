package com.example.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BreedAIColorScheme = lightColorScheme(
    primary = BreedVerdeEscuro,
    onPrimary = Color.White,
    primaryContainer = BreedVerdeSuave,
    onPrimaryContainer = BreedVerdeEscuro,
    secondary = BreedVerdePrincipal,
    onSecondary = Color.White,
    secondaryContainer = BreedVerdeContainerSec,
    onSecondaryContainer = BreedVerdeEscuro,
    tertiary = BreedLaranjaAlerta,
    onTertiary = Color.White,
    error = BreedVermelhoErro,
    onError = Color.White,
    background = BreedFundoSolar,
    onBackground = BreedTextoEscuro,
    surface = Color.White,
    onSurface = BreedTextoEscuro,
    surfaceVariant = BreedSuperficieVariante,
    onSurfaceVariant = BreedTextoSuave,
    outline = BreedContorno,
    outlineVariant = BreedContornoSuave
)

@Composable
fun BreedAITheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BreedAIColorScheme,
        typography = Typography,
        content = content
    )
}
