package com.cyclone.mobile.ui.v32

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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF182038)
private val InkMuted = Color(0xFF69708A)
private val Periwinkle = Color(0xFF6675E8)
private val PeriwinkleSoft = Color(0xFFDDE3FF)
private val WarmIce = Color(0xFFF5F6FC)
private val SoftSurface = Color(0xFFEEF0FA)

private val CycloneV32LightColors = lightColorScheme(
    primary = Periwinkle,
    onPrimary = Color.White,
    primaryContainer = PeriwinkleSoft,
    onPrimaryContainer = Color(0xFF20295E),
    secondary = Color(0xFF397B67),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF4DF),
    onSecondaryContainer = Color(0xFF153B30),
    tertiary = Color(0xFF805B15),
    tertiaryContainer = Color(0xFFFFE6A7),
    onTertiaryContainer = Color(0xFF3B2B08),
    background = WarmIce,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SoftSurface,
    onSurfaceVariant = InkMuted,
    outline = Color(0xFFB8BDD0),
    outlineVariant = Color(0xFFDDE0EB),
    error = Color(0xFFBA4052),
    errorContainer = Color(0xFFFFD9DE),
    onErrorContainer = Color(0xFF5B1420),
)

private val CycloneV32DarkColors = darkColorScheme(
    primary = Color(0xFF9EA9FF),
    onPrimary = Color(0xFF182052),
    primaryContainer = Color(0xFF303C70),
    onPrimaryContainer = Color(0xFFE3E6FF),
    secondary = Color(0xFF8AD8BE),
    secondaryContainer = Color(0xFF264E43),
    onSecondaryContainer = Color(0xFFD8FFEF),
    tertiary = Color(0xFFFFD178),
    tertiaryContainer = Color(0xFF53552E),
    onTertiaryContainer = Color(0xFFFFF3B7),
    background = Color(0xFF11172B),
    onBackground = Color(0xFFF5F6FF),
    surface = Color(0xFF1A223A),
    onSurface = Color(0xFFF5F6FF),
    surfaceVariant = Color(0xFF242D49),
    onSurfaceVariant = Color(0xFFB9C0D9),
    outline = Color(0xFF7C849D),
    outlineVariant = Color(0xFF38425F),
    error = Color(0xFFFFB2BD),
    errorContainer = Color(0xFF652D3A),
    onErrorContainer = Color(0xFFFFD9DE),
)

private val CycloneV32Shapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun CycloneV32Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) CycloneV32DarkColors else CycloneV32LightColors,
        shapes = CycloneV32Shapes,
        content = content,
    )
}

enum class CyclonePastel { PRIMARY, LILAC, MINT, LEMON, PEACH, SKY }

@Immutable
data class CyclonePastelColors(val container: Color, val content: Color)

@Composable
fun cyclonePastel(tone: CyclonePastel): CyclonePastelColors {
    val dark = isSystemInDarkTheme()
    return when (tone) {
        CyclonePastel.PRIMARY -> CyclonePastelColors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        CyclonePastel.LILAC -> if (dark) CyclonePastelColors(Color(0xFF563D69), Color(0xFFF8DCFF)) else CyclonePastelColors(Color(0xFFEFCBFF), Color(0xFF3A1848))
        CyclonePastel.MINT -> if (dark) CyclonePastelColors(Color(0xFF264E43), Color(0xFFD8FFEF)) else CyclonePastelColors(Color(0xFFCFF4DF), Color(0xFF153B30))
        CyclonePastel.LEMON -> if (dark) CyclonePastelColors(Color(0xFF53552E), Color(0xFFFFF8C5)) else CyclonePastelColors(Color(0xFFF4F7B2), Color(0xFF353708))
        CyclonePastel.PEACH -> if (dark) CyclonePastelColors(Color(0xFF5B4034), Color(0xFFFFE2D0)) else CyclonePastelColors(Color(0xFFFFD8BC), Color(0xFF482411))
        CyclonePastel.SKY -> if (dark) CyclonePastelColors(Color(0xFF294D64), Color(0xFFD5F0FF)) else CyclonePastelColors(Color(0xFFCDEAFF), Color(0xFF14364B))
    }
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
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = colors.content.copy(alpha = 0.12f), contentColor = colors.content) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size(27.dp)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.content.copy(alpha = 0.78f))
            }
            action?.invoke()
        }
    }
}

@Composable
fun CycloneSectionTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        action?.invoke()
    }
}

@Composable
fun CycloneStatusPill(label: String, positive: Boolean = true) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CycloneSimpleCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun CyclonePageIntro(eyebrow: String, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
    }
}
