package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.cyclone.mobile.R

/**
 * Cyclone's AI environment. The photo remains visible; contrast is created with a zoned tonal scrim
 * instead of washing the entire scene almost white.
 */
@Composable
internal fun CycloneAlpineBackdrop(content: @Composable BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(background)) {
        Image(
            painter = painterResource(if (dark) R.drawable.cyclone_alpine_night else R.drawable.cyclone_alpine_day),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0.00f to background.copy(alpha = if (dark) .42f else .34f),
                    0.24f to background.copy(alpha = if (dark) .24f else .20f),
                    0.50f to background.copy(alpha = if (dark) .10f else .07f),
                    0.76f to background.copy(alpha = if (dark) .13f else .09f),
                    1.00f to background.copy(alpha = if (dark) .26f else .18f),
                ),
            ),
        )
        content()
    }
}
