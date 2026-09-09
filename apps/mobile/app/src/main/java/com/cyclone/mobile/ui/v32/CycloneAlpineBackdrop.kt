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

/** Supplied day/night photographs; the scrim keeps foreground copy legible. */
@Composable
internal fun CycloneAlpineBackdrop(content: @Composable BoxScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(background)) {
        Image(painterResource(if (isSystemInDarkTheme()) R.drawable.cyclone_alpine_night else R.drawable.cyclone_alpine_day),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            background.copy(alpha = .92f), background.copy(alpha = .80f), background.copy(alpha = .55f),
        ))))
        content()
    }
}
