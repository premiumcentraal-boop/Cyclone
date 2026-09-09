package com.cyclone.mobile.ui.v32

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF0A1830)
private val InkMuted = Color(0xFF5F7188)
private val Accent = Color(0xFF1A73FF)
private val AccentSoft = Color(0xFFE7F1FF)
private val Canvas = Color(0xFFF5F9FE)
private val SoftSurface = Color(0xFFEEF4FB)

private val CycloneV32LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Color(0xFF0B3C7A),
    secondary = Color(0xFF168653),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1F5EA),
    onSecondaryContainer = Color(0xFF123A2E),
    tertiary = Color(0xFF8A6418),
    tertiaryContainer = Color(0xFFFFEFC3),
    onTertiaryContainer = Color(0xFF493606),
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SoftSurface,
    onSurfaceVariant = InkMuted,
    outline = Color(0xFFB8C7D9),
    outlineVariant = Color(0xFFDDE8F4),
    error = Color(0xFFB73C4D),
    errorContainer = Color(0xFFFFE4E8),
    onErrorContainer = Color(0xFF5B1721),
)

private val CycloneV32DarkColors = darkColorScheme(
    primary = Color(0xFF78ADFF),
    onPrimary = Color(0xFF002F67),
    primaryContainer = Color(0xFF123D72),
    onPrimaryContainer = Color(0xFFD9E9FF),
    secondary = Color(0xFF8AD8B7),
    secondaryContainer = Color(0xFF173F31),
    onSecondaryContainer = Color(0xFFD8FFED),
    tertiary = Color(0xFFFFD78A),
    tertiaryContainer = Color(0xFF4B3C20),
    onTertiaryContainer = Color(0xFFFFEFC7),
    background = Color(0xFF07101F),
    onBackground = Color(0xFFF2F7FF),
    surface = Color(0xFF0E1A2B),
    onSurface = Color(0xFFF2F7FF),
    surfaceVariant = Color(0xFF15263A),
    onSurfaceVariant = Color(0xFFAABBD0),
    outline = Color(0xFF6D8098),
    outlineVariant = Color(0xFF253A52),
    error = Color(0xFFFFB5BF),
    errorContainer = Color(0xFF5C2731),
    onErrorContainer = Color(0xFFFFE0E4),
)

private val CycloneV32Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val CycloneTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun CycloneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) CycloneV32DarkColors else CycloneV32LightColors,
        shapes = CycloneV32Shapes,
        typography = CycloneTypography,
        content = content,
    )
}

enum class CyclonePastel { PRIMARY, LILAC, MINT, LEMON, PEACH, SKY }

@Immutable
data class CyclonePastelColors(val container: Color, val content: Color)

@Composable
fun cyclonePastel(tone: CyclonePastel): CyclonePastelColors {
    return CyclonePastelColors(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
}

@Composable
fun CycloneHeroCard(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: CyclonePastel = CyclonePastel.PRIMARY,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = cyclonePastel(tone)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(22.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
    }
}

@Composable
fun CycloneSectionTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun CycloneStatusPill(label: String, positive: Boolean = true) {
    val container = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    val dot = if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    Surface(shape = RoundedCornerShape(999.dp), color = container, contentColor = content) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(modifier = Modifier.size(6.dp), shape = CircleShape, color = dot) {}
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun CycloneSimpleCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun CyclonePageIntro(eyebrow: String, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun CycloneV32Theme(content: @Composable () -> Unit) = CycloneTheme(content)

object CycloneColors {
    val Blue = Accent
    val Cyan = Color(0xFF4FCBFF)
    val Success = Color(0xFF168653)
}

object CycloneSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Content = 16.dp
    val Page = 20.dp
    val Section = 28.dp
    val ComposerLift = 30.dp
}

@Composable
fun CycloneSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        content = content,
    )
}

/**
 * Translucent glass is rendered directly into a clipped layer. Material shadow/elevation on
 * transparent Surfaces can rasterize as a rectangular band on some Android renderers.
 */
@Composable
fun CycloneGlassSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .animateContentSize()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .88f)),
    ) {
        content()
    }
}

@Composable
fun CycloneStatus(label: String, positive: Boolean = true) = CycloneStatusPill(label, positive)