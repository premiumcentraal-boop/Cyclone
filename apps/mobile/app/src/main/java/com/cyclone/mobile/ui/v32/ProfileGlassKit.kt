package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cyclone.mobile.ui.v32.ask.AskCalm
import com.cyclone.mobile.ui.v32.ask.AskCalmField
import com.cyclone.mobile.ui.v32.ask.AskGlass
import com.cyclone.mobile.ui.v32.ask.AskTextShadow
import com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop
import com.cyclone.mobile.ui.v32.ask.LocalAskCalm
import com.cyclone.mobile.ui.v32.ask.askGlass
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

/**
 * The profile surfaces' own small kit on the calm glass (R6): one button that never wraps, the round avatar, grouped
 * glass lists, a bottom sheet and the room meter. Every profile screen is built from these, so they read as one
 * product with Home: big words, few words, one clear action.
 */
internal object ProfileGlass {
    /** The default avatar colour when a profile has none. */
    const val DEFAULT_COLOR = 0xFF3E7BFA
    val Destructive = Color(0xFFFF6B5E)
    val Sheet = Brush.verticalGradient(listOf(Color(0xFF1D2678), Color(0xFF111754), Color(0xFF0B0F38)))
    val SheetRow = Color.White.copy(alpha = 0.08f)
}

/** True inside a [ProfileSheet]: lists there are a soft veil, not glass over a backdrop the sheet doesn't have. */
private val LocalProfileSheet = staticCompositionLocalOf { false }

internal enum class ProfileButtonStyle { PRIMARY, SECONDARY, QUIET, DESTRUCTIVE }

/**
 * The one button of the profile screens: a capsule whose label stays on one line and is never squeezed into a
 * column of letters. Primary is solid white with dark words (the one thing to do); Secondary is a white veil; Quiet is
 * words only; Destructive is red words on the veil.
 */
@Composable
internal fun ProfileButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ProfileButtonStyle = ProfileButtonStyle.SECONDARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "Profile button")
    val (fill, rim, ink) = when (style) {
        ProfileButtonStyle.PRIMARY -> Triple(Color.White, Color.Transparent, Color(0xFF0B1040))
        ProfileButtonStyle.SECONDARY -> Triple(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.22f), AskGlass.Ink)
        ProfileButtonStyle.QUIET -> Triple(Color.Transparent, Color.Transparent, AskGlass.Ink)
        ProfileButtonStyle.DESTRUCTIVE -> Triple(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.16f), ProfileGlass.Destructive)
    }
    Row(
        modifier
            .heightIn(min = when { compact -> 36.dp; style == ProfileButtonStyle.QUIET -> 44.dp; else -> 50.dp })
            .widthIn(min = if (compact) 48.dp else 64.dp)
            .clip(ContinuousCapsule)
            .background(fill.copy(alpha = fill.alpha * alpha))
            .border(0.7.dp, rim, ContinuousCapsule)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(19.dp), tint = ink.copy(alpha = ink.alpha * alpha))
            Box(Modifier.width(8.dp))
        }
        Text(
            label,
            color = ink.copy(alpha = ink.alpha * alpha),
            fontSize = if (compact) 14.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A profile's face: a soft gradient circle in its colour with its emoji or first letter. [ring] marks the one in use. */
@Composable
internal fun ProfileAvatar(
    label: String,
    emoji: String? = null,
    color: Long? = null,
    size: Dp = 64.dp,
    ring: Boolean = false,
    dimmed: Boolean = false,
    icon: ImageVector? = null,
) {
    val base = Color(color ?: ProfileGlass.DEFAULT_COLOR)
    val light = androidx.compose.ui.graphics.lerp(base, Color.White, 0.28f)
    val outer = if (ring) size + 10.dp else size
    Box(
        Modifier.size(outer).then(if (ring) Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(Brush.linearGradient(listOf(light, base)))
                .border(0.8.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                icon != null -> Icon(icon, null, Modifier.size(size * 0.46f), tint = Color.White)
                else -> Text(
                    emoji?.takeIf { it.isNotBlank() } ?: profileInitial(label),
                    color = Color.White,
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            if (dimmed) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
        }
    }
}

/** "Profile C" → "C", "Work" → "W": the letter that tells profiles apart, not the shared word "Profile". */
internal fun profileInitial(label: String): String {
    val words = label.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val word = if (words.size > 1 && words.first().equals("profile", ignoreCase = true)) words.last() else words.firstOrNull()
    return word?.take(1)?.uppercase() ?: "?"
}

/** A large page title on the calm blue, with an optional quiet line under it and a trailing control. */
@Composable
internal fun ProfileTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = AskGlass.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = AskTextShadow))
            if (subtitle != null) {
                Text(subtitle, color = AskGlass.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = AskTextShadow))
            }
        }
        trailing?.invoke()
    }
}

/** A section label on the blue, with an optional action word on the right. */
@Composable
internal fun ProfileSectionLabel(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(horizontal = 4.dp), color = AskGlass.Muted, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, style = TextStyle(shadow = AskTextShadow))
        if (action != null && onAction != null) {
            Text(
                action,
                Modifier.clip(ContinuousCapsule).clickable(role = Role.Button, onClick = onAction)
                    .heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                color = AskGlass.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                style = TextStyle(shadow = AskTextShadow),
            )
        }
    }
}

/** A grouped glass list (like a Settings group): rows with hairlines between them. */
@Composable
internal fun ProfileGroup(modifier: Modifier = Modifier, shineOffset: Float = 0.4f, content: @Composable ColumnScope.() -> Unit) {
    val shape = ContinuousRoundedRectangle(22.dp)
    val surface = if (LocalProfileSheet.current) Modifier.background(ProfileGlass.SheetRow, shape) else Modifier.askGlass(22.dp, shineOffset = shineOffset)
    Column(modifier.fillMaxWidth().then(surface).clip(shape), content = content)
}

@Composable
internal fun ProfileRowDivider(start: Dp = 60.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(0.7.dp).background(AskGlass.Hairline))
}

/** A square colour tile with a white symbol: how a row is found by colour before words (as in Settings). */
@Composable
internal fun ProfileIconTile(icon: ImageVector, color: Color, size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(ContinuousRoundedRectangle(size * 0.3f)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.58f), tint = Color.White)
    }
}

/** One row of a [ProfileGroup]: leading, a title (one line), an optional quiet line, a value and a chevron. */
@Composable
internal fun ProfileRow(
    title: String,
    onClick: (() -> Unit)?,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    destructive: Boolean = false,
    chevron: Boolean = onClick != null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = if (destructive) ProfileGlass.Destructive else AskGlass.Ink, fontSize = 16.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = AskGlass.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Text(value, color = AskGlass.Muted, fontSize = 15.sp, maxLines = 1, softWrap = false)
        }
        trailing?.invoke(this)
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = AskGlass.Faint)
    }
}

/** A small coloured dot and one word ("In use", "Working", "Needs you"). Never a pill that can be squeezed. */
@Composable
internal fun ProfileStatus(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = AskGlass.Muted, fontSize = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

internal enum class ProfileNoticeKind { WORKING, PROBLEM, INFO }

/** One calm line on glass for what is happening now or what went wrong: a spinner or a mark, then the words. */
@Composable
internal fun ProfileNotice(text: String, kind: ProfileNoticeKind, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(18.dp))
            .background(if (kind == ProfileNoticeKind.PROBLEM) ProfileGlass.Destructive.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (kind) {
            ProfileNoticeKind.WORKING -> CircularProgressIndicator(Modifier.size(18.dp), color = AskGlass.Ink, trackColor = AskGlass.Hairline, strokeWidth = 2.dp)
            ProfileNoticeKind.PROBLEM -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(19.dp), tint = ProfileGlass.Destructive)
            ProfileNoticeKind.INFO -> Icon(Icons.Rounded.Info, null, Modifier.size(19.dp), tint = AskGlass.Muted)
        }
        Text(text, color = AskGlass.Ink, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * How full the phone is, at a glance: one dot per place when there are few, a filled capsule when there are many.
 * Unknown limits draw an empty track rather than a guess.
 */
@Composable
internal fun ProfileRoomMeter(used: Int, limit: Int?, modifier: Modifier = Modifier) {
    val full = limit != null && used >= limit
    val fillColor = if (full) AskGlass.Waiting else Color.White
    if (limit != null && limit in 1..12) {
        Row(modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(limit) { i ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(ContinuousCapsule)
                    .background(if (i < used) fillColor else Color.White.copy(alpha = 0.18f)))
            }
        }
        return
    }
    val fraction = if (limit == null || limit <= 0) 0f else (used.toFloat() / limit).coerceIn(0f, 1f)
    val shown by animateFloatAsState(fraction, label = "Room meter")
    Box(modifier.fillMaxWidth().height(8.dp).clip(ContinuousCapsule).background(Color.White.copy(alpha = 0.18f))) {
        if (shown > 0f) Box(Modifier.fillMaxWidth(shown.coerceAtLeast(0.02f)).fillMaxHeight().clip(ContinuousCapsule).background(fillColor))
    }
}

/**
 * A bottom sheet in the calm blue: dims the page, rises from the bottom, a grab handle on top. Tap the dim or Back to
 * close. Content scrolls when it is taller than the screen.
 */
@Composable
internal fun ProfileSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close",
                    onClick = onDismiss,
                ),
            )
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            CompositionLocalProvider(LocalProfileSheet provides true, LocalAskBackdrop provides null) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .clip(shape).background(ProfileGlass.Sheet).border(0.8.dp, Color.White.copy(alpha = 0.16f), shape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(5.dp).clip(ContinuousCapsule)
                        .background(Color.White.copy(alpha = 0.32f)))
                    if (title != null) {
                        Text(title, Modifier.fillMaxWidth().padding(top = 4.dp), color = AskGlass.Ink, fontSize = 24.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                    content()
                }
            }
        }
    }
}

/**
 * A full-screen profile page outside the app's glass world (the setup flow, the rescue screen): it draws the calm blue
 * itself and records it, so the glass on it blurs exactly as on Home.
 */
@Composable
internal fun ProfileCalmPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize().background(AskCalm.Bottom)) {
        AskCalmField(Modifier.matchParentSize().layerBackdrop(backdrop))
        CompositionLocalProvider(LocalAskBackdrop provides backdrop, LocalAskCalm provides true) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

/** A row of up to [max] app icons, overlapping like a stack, with "+N" after them. */
@Composable
internal fun ProfileAppStack(packages: List<String>, max: Int = 4, iconSize: Dp = 22.dp) {
    if (packages.isEmpty()) return
    val shown = packages.take(max)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(iconSize + (iconSize * 0.62f) * (shown.size - 1)).height(iconSize)) {
            shown.forEachIndexed { i, pkg ->
                Box(
                    Modifier.padding(start = iconSize * 0.62f * i).size(iconSize).clip(CircleShape)
                        .background(AskCalm.Middle).border(1.2.dp, AskCalm.Upper, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { CycloneAppIcon(pkg, Modifier.size(iconSize * 0.74f)) }
            }
        }
        if (packages.size > max) Text("+${packages.size - max}", color = AskGlass.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

/** The profile's words in one place: what its status line says. */
internal object ProfileWords {
    fun apps(count: Int): String = when (count) {
        0 -> "No apps yet"
        1 -> "1 app"
        else -> "$count apps"
    }

    fun profiles(count: Int): String = if (count == 1) "1 profile" else "$count profiles"
}

/** Semantics for a tile: its name and state, read as one. */
internal fun Modifier.profileTileSemantics(text: String): Modifier = semantics(mergeDescendants = true) { contentDescription = text }

/**
 * A text field on glass: a soft capsule with large, centred words when [large] (a profile's name), or a search line
 * with a leading symbol. The placeholder shows while it is empty.
 */
@Composable
internal fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    leading: ImageVector? = null,
) {
    val style = TextStyle(
        color = AskGlass.Ink,
        fontSize = if (large) 26.sp else 16.sp,
        fontWeight = if (large) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = if (large) TextAlign.Center else TextAlign.Start,
    )
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (large) 64.dp else 50.dp).clip(ContinuousCapsule)
                    .background(Color.White.copy(alpha = 0.12f)).border(0.7.dp, Color.White.copy(alpha = 0.2f), ContinuousCapsule)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (leading != null) Icon(leading, null, Modifier.size(20.dp), tint = AskGlass.Muted)
                Box(Modifier.weight(1f), contentAlignment = if (large) Alignment.Center else Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = AskGlass.Faint), maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** A yes/no question in a sheet: what happens, in a line or two, the action in its colour, and Cancel. */
@Composable
internal fun ProfileConfirmSheet(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    cancel: String = "Cancel",
) {
    ProfileSheet(onDismiss, title) {
        Text(body, Modifier.fillMaxWidth(), color = AskGlass.Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        ProfileButton(confirm, onConfirm, Modifier.fillMaxWidth(),
            style = if (destructive) ProfileButtonStyle.DESTRUCTIVE else ProfileButtonStyle.PRIMARY)
        ProfileButton(cancel, onDismiss, Modifier.fillMaxWidth(), style = ProfileButtonStyle.QUIET)
    }
}
