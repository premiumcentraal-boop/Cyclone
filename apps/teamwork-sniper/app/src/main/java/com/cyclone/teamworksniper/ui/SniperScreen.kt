package com.cyclone.teamworksniper.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.teamworksniper.BuildConfig
import com.cyclone.teamworksniper.UiState
import com.cyclone.teamworksniper.data.ActivityEntry
import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.data.ShiftRule
import com.cyclone.teamworksniper.data.SniperSettings
import com.cyclone.teamworksniper.rules.TargetSelectionRules
import com.cyclone.teamworksniper.teamwork.ShiftTemplateProvider
import com.cyclone.teamworksniper.ui.overlay.ShiftTemplate
import com.cyclone.teamworksniper.ui.overlay.TemplateProvenance
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val SniperOrange = Color(0xFFFF6500)
private val SniperOrangeSoft = Color(0xFFFFF3EB)
private val AppBackground = Color(0xFFF7F8FA)
private val CardSurface = Color.White
private val Ink = Color(0xFF121826)
private val Muted = Color(0xFF697386)
private val Line = Color(0xFFE4E7EC)
private val Assigned = Color(0xFFF0F2F5)
private val Success = Color(0xFF159455)
private val SuccessSoft = Color(0xFFEAF8F0)
private val Watching = Color(0xFF3478F6)
private val WatchingSoft = Color(0xFFEAF2FF)

private val RoundedLarge = RoundedCornerShape(22.dp)
private val RoundedMedium = RoundedCornerShape(16.dp)
private val RoundedSmall = RoundedCornerShape(12.dp)

private val DayFormat = DateTimeFormatter.ofPattern("EEEE d MMM")
private val RangeFormat = DateTimeFormatter.ofPattern("d MMM")
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val ActivityTimeFormat = DateTimeFormatter.ofPattern("HH:mm")

private enum class AppTab { SCHEDULE, ACTIVITY, SETTINGS }
private enum class ShiftVisualState { AVAILABLE, SELECTED, CLAIMED }
private enum class SettingsPage { ROOT, TEMPLATES, OVERLAY, DIAGNOSTICS }

@Composable
fun SniperScreen(
    state: UiState,
    onSettings: (SniperSettings) -> Unit,
    onRules: (List<ShiftRule>) -> Unit,
    onNotification: () -> Unit,
    onAccessibility: () -> Unit,
    onOnboardingComplete: () -> Unit,
    onOpenTeamwork: () -> Unit,
) {
    MaterialTheme(colorScheme = sniperPalette()) {
        Surface(Modifier.fillMaxSize(), color = AppBackground) {
            if (state.onboardingComplete) {
                MainSniperApp(state, onSettings, onRules, onNotification, onAccessibility, onOpenTeamwork)
            } else {
                OnboardingFlow(state, onRules, onNotification, onAccessibility, onOnboardingComplete, onOpenTeamwork)
            }
        }
    }
}

@Composable
private fun OnboardingFlow(
    state: UiState,
    onRules: (List<ShiftRule>) -> Unit,
    onNotification: () -> Unit,
    onAccessibility: () -> Unit,
    onComplete: () -> Unit,
    onOpenTeamwork: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    when (step) {
        0 -> WelcomePage { step = 1 }
        1 -> QuickSetupPage(state, onNotification, onAccessibility, { step = 0 }) { step = 2 }
        2 -> ChooseShiftsOnboarding(state, onRules, { step = 1 }) { step = 3 }
        else -> ReadyPage(
            state = state,
            onBack = { step = 2 },
            onFinish = onComplete,
            onOpenTeamwork = {
                onComplete()
                onOpenTeamwork()
            },
        )
    }
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.55f))
        Surface(modifier = Modifier.size(148.dp), color = SniperOrangeSoft, shape = RoundedLarge) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CalendarMonth, null, tint = Ink.copy(alpha = 0.18f), modifier = Modifier.size(82.dp))
                TargetMark(76.dp)
            }
        }
        Spacer(Modifier.height(34.dp))
        Text("Teamwork\nSniper", style = MaterialTheme.typography.displaySmall, color = Ink, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Never miss the shifts you want", style = MaterialTheme.typography.titleMedium, color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(0.7f))
        PrimaryButton("Get Started", onClick = onNext)
        Spacer(Modifier.height(16.dp))
        Text("Safe. Private. On-device.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun QuickSetupPage(
    state: UiState,
    onNotification: () -> Unit,
    onAccessibility: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val ready = state.permissions.notificationAccess && state.permissions.accessibilityAccess
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BackHeader("Quick Setup", "Two permissions let Sniper watch openings and act for you.", onBack) }
        item { PermissionCard(Icons.Outlined.Notifications, "Notification Access", "Detect new Teamwork openings in real time.", state.permissions.notificationAccess, onNotification) }
        item { PermissionCard(Icons.Outlined.Accessibility, "Accessibility Access", "Read Teamwork semantically and claim selected shifts.", state.permissions.accessibilityAccess, onAccessibility) }
        item {
            Surface(color = SniperOrangeSoft, shape = RoundedMedium) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, null, tint = SniperOrange)
                    Spacer(Modifier.width(10.dp))
                    Text("Sniper does not store your Teamwork login. These permissions stay on your phone.", color = Ink, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { PrimaryButton(if (ready) "Continue" else "Enable both to continue", onNext, enabled = ready) }
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onOpen: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedLarge) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (enabled) SuccessSoft else SniperOrangeSoft, shape = CircleShape, modifier = Modifier.size(46.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (enabled) Success else SniperOrange, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = Ink)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                StatePill(if (enabled) "Enabled" else "Required", if (enabled) SuccessSoft else Assigned, if (enabled) Success else Muted)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onOpen, shape = RoundedSmall, border = BorderStroke(1.dp, if (enabled) Line else SniperOrange)) {
                Text(if (enabled) "Manage" else "Enable", color = if (enabled) Ink else SniperOrange)
            }
        }
    }
}

@Composable
private fun ChooseShiftsOnboarding(state: UiState, onRules: (List<ShiftRule>) -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val selectedCount = targetCount(state.rules)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = Ink) }
            Column(Modifier.weight(1f)) {
                Text("Choose shifts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                Text("Tap any shift you want Sniper to watch.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            StatePill(selectedCount.toString() + " selected", SniperOrangeSoft, SniperOrange)
        }
        Box(Modifier.weight(1f)) {
            ScheduleList(state, weekOffset, { weekOffset = it }, onRules, showStatus = false)
        }
        Surface(color = CardSurface, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val buttonText = if (selectedCount > 0) {
                    "Continue with " + selectedCount + " shift" + if (selectedCount == 1) "" else "s"
                } else {
                    "Choose at least one shift"
                }
                PrimaryButton(buttonText, onNext, enabled = selectedCount > 0)
            }
        }
    }
}

@Composable
private fun ReadyPage(
    state: UiState,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onOpenTeamwork: () -> Unit,
) {
    val targets = exactTargets(state.rules).take(4)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = Ink) } }
        Spacer(Modifier.weight(0.3f))
        Surface(color = SniperOrangeSoft, shape = CircleShape, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) { TargetMark(56.dp) }
        }
        Spacer(Modifier.height(24.dp))
        Text("All Set", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Ink)
        Text("Your shift watchlist is ready", style = MaterialTheme.typography.titleMedium, color = Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("Sniper will watch Teamwork openings for your selected targets. Armed mode stays under your control.", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            targets.forEach { target ->
                Surface(color = CardSurface, shape = RoundedMedium, border = BorderStroke(1.dp, SniperOrange.copy(alpha = 0.45f))) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(target.second.name, color = SniperOrange, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(14.dp))
                        Text(target.first.format(DayFormat), color = Ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.weight(0.7f))
        PrimaryButton("Open Teamwork", onClick = onOpenTeamwork, trailing = Icons.Outlined.OpenInNew)
        TextButton(onClick = onFinish) { Text("Go to Teamwork Sniper", color = SniperOrange) }
    }
}

@Composable
private fun MainSniperApp(
    state: UiState,
    onSettings: (SniperSettings) -> Unit,
    onRules: (List<ShiftRule>) -> Unit,
    onNotification: () -> Unit,
    onAccessibility: () -> Unit,
    onOpenTeamwork: () -> Unit,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(AppTab.SCHEDULE.ordinal) }
    var settingsPage by rememberSaveable { mutableIntStateOf(SettingsPage.ROOT.ordinal) }
    val tab = AppTab.entries[tabIndex]

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            MainTopBar(
                title = when (tab) {
                    AppTab.SCHEDULE -> "This Week"
                    AppTab.ACTIVITY -> "Activity"
                    AppTab.SETTINGS -> if (settingsPage == SettingsPage.ROOT.ordinal) "Settings" else ""
                },
                targetCount = targetCount(state.rules),
                showOpenTeamwork = tab == AppTab.SCHEDULE,
                onOpenTeamwork = onOpenTeamwork,
            )
        },
        bottomBar = {
            NavigationBar(containerColor = CardSurface, tonalElevation = 0.dp) {
                AppTab.entries.forEach { item ->
                    val icon = when (item) {
                        AppTab.SCHEDULE -> Icons.Outlined.CalendarMonth
                        AppTab.ACTIVITY -> Icons.Outlined.History
                        AppTab.SETTINGS -> Icons.Outlined.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            tabIndex = item.ordinal
                            if (item != AppTab.SETTINGS) settingsPage = SettingsPage.ROOT.ordinal
                        },
                        icon = { Icon(icon, item.name.lowercase()) },
                        label = { Text(item.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SniperOrange,
                            selectedTextColor = SniperOrange,
                            indicatorColor = SniperOrangeSoft,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                AppTab.SCHEDULE -> ScheduleScreen(state, onRules)
                AppTab.ACTIVITY -> ActivityScreen(state.activity)
                AppTab.SETTINGS -> when (SettingsPage.entries[settingsPage]) {
                    SettingsPage.ROOT -> SettingsScreen(state, onSettings, onNotification, onAccessibility) { settingsPage = it.ordinal }
                    SettingsPage.TEMPLATES -> TemplateSettingsPage { settingsPage = SettingsPage.ROOT.ordinal }
                    SettingsPage.OVERLAY -> OverlayPreviewPage(
                        enabled = state.settings.legacyOverlayEnabled,
                        onToggle = { onSettings(state.settings.copy(legacyOverlayEnabled = it)) },
                        onBack = { settingsPage = SettingsPage.ROOT.ordinal },
                    )
                    SettingsPage.DIAGNOSTICS -> DiagnosticsPage(state) { settingsPage = SettingsPage.ROOT.ordinal }
                }
            }
        }
    }
}

@Composable
private fun MainTopBar(title: String, targetCount: Int, showOpenTeamwork: Boolean, onOpenTeamwork: () -> Unit) {
    Surface(color = CardSurface, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TargetMark(34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (title.isBlank()) "Teamwork Sniper" else title, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                val suffix = if (targetCount == 1) "" else "s"
                Text(targetCount.toString() + " shift target" + suffix, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (showOpenTeamwork) {
                IconButton(onClick = onOpenTeamwork) { Icon(Icons.Outlined.OpenInNew, "Open Teamwork", tint = SniperOrange) }
            }
        }
    }
}

@Composable
private fun ScheduleScreen(state: UiState, onRules: (List<ShiftRule>) -> Unit) {
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    ScheduleList(state, weekOffset, { weekOffset = it }, onRules, showStatus = true)
}

@Composable
private fun ScheduleList(
    state: UiState,
    weekOffset: Int,
    onWeekOffset: (Int) -> Unit,
    onRules: (List<ShiftRule>) -> Unit,
    showStatus: Boolean,
) {
    val weekStart = remember(weekOffset) {
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
    }
    val templates = remember { ShiftTemplateProvider() }
    val claimed = remember(state.activity) { claimedKeys(state.activity) }
    val openNow = remember(state.activity) { recentOpenKeys(state.activity) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showStatus) item { ReadinessStrip(state) }
        item { WeekChooser(weekStart, { onWeekOffset(weekOffset - 1) }, { onWeekOffset(weekOffset + 1) }) }
        items(7) { index ->
            val date = weekStart.plusDays(index.toLong())
            DaySection(
                date = date,
                rules = state.rules,
                templates = templates,
                claimedKeys = claimed,
                openKeys = openNow,
                onToggle = { code -> onRules(TargetSelectionRules.toggle(state.rules, date, code)) },
            )
        }
        item {
            Text(
                "Expected times are shown only when backed by the current Teamwork shift templates. Unconfirmed times stay hidden.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ReadinessStrip(state: UiState) {
    val missing = !state.permissions.notificationAccess || !state.permissions.accessibilityAccess
    val background = when {
        missing -> Color(0xFFFFF5E6)
        !state.settings.armed -> Assigned
        else -> SuccessSoft
    }
    val foreground = when {
        missing -> Color(0xFFA15C00)
        !state.settings.armed -> Muted
        else -> Success
    }
    val text = when {
        missing -> "Finish permissions before Sniper can watch Teamwork."
        !state.settings.enabled -> "Sniper is disabled."
        !state.settings.armed -> "Watchlist ready · Armed mode is off."
        else -> "Sniper is armed and watching your selected shifts."
    }
    Surface(color = background, shape = RoundedMedium) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            TargetMark(28.dp, color = foreground)
            Spacer(Modifier.width(10.dp))
            Text(text, color = foreground, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WeekChooser(weekStart: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedLarge) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onPrevious) { Text("‹", color = SniperOrange, style = MaterialTheme.typography.headlineSmall) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Week " + weekStart.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR), fontWeight = FontWeight.Bold, color = Ink)
                Text(weekStart.format(RangeFormat) + " – " + weekStart.plusDays(6).format(RangeFormat), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onNext) { Text("›", color = SniperOrange, style = MaterialTheme.typography.headlineSmall) }
        }
    }
}

@Composable
private fun DaySection(
    date: LocalDate,
    rules: List<ShiftRule>,
    templates: ShiftTemplateProvider,
    claimedKeys: Set<String>,
    openKeys: Set<String>,
    onToggle: (ShiftCode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(date.format(DayFormat), color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (date == LocalDate.now()) StatePill("Today", SniperOrangeSoft, SniperOrange)
        }
        templates.forDate(date).forEach { shift ->
            val key = targetKey(date, shift.code)
            val selected = rules.any { TargetSelectionRules.isExactTarget(it, date, shift.code) }
            val visualState = when {
                key in claimedKeys -> ShiftVisualState.CLAIMED
                selected -> ShiftVisualState.SELECTED
                else -> ShiftVisualState.AVAILABLE
            }
            ShiftTargetRow(shift, visualState, key in openKeys) {
                if (visualState != ShiftVisualState.CLAIMED) onToggle(shift.code)
            }
        }
    }
}

@Composable
private fun ShiftTargetRow(template: ShiftTemplate, state: ShiftVisualState, openNow: Boolean, onClick: () -> Unit) {
    val targetBackground = when (state) {
        ShiftVisualState.AVAILABLE -> CardSurface
        ShiftVisualState.SELECTED -> SniperOrange
        ShiftVisualState.CLAIMED -> SuccessSoft
    }
    val background by animateColorAsState(targetValue = targetBackground)
    val foreground = when (state) {
        ShiftVisualState.SELECTED -> Color.White
        ShiftVisualState.CLAIMED -> Success
        else -> SniperOrange
    }
    val secondary = when (state) {
        ShiftVisualState.SELECTED -> Color.White.copy(alpha = 0.84f)
        ShiftVisualState.CLAIMED -> Success.copy(alpha = 0.8f)
        else -> Muted
    }
    val border = when (state) {
        ShiftVisualState.AVAILABLE -> BorderStroke(1.3.dp, SniperOrange)
        ShiftVisualState.CLAIMED -> BorderStroke(1.dp, Success.copy(alpha = 0.25f))
        ShiftVisualState.SELECTED -> null
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedMedium).clickable(enabled = state != ShiftVisualState.CLAIMED, onClick = onClick),
        color = background,
        shape = RoundedMedium,
        border = border,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(template.code.name, color = foreground, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    templateTime(template),
                    color = if (state == ShiftVisualState.AVAILABLE) Ink else foreground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (openNow) {
                        StatePill("Open now", SuccessSoft, Success)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (template.provenance == TemplateProvenance.PROVISIONAL) "Time not confirmed" else "Expected Teamwork time",
                        color = secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                when (state) {
                    ShiftVisualState.AVAILABLE -> "Snipe"
                    ShiftVisualState.SELECTED -> "Sniping ✓"
                    ShiftVisualState.CLAIMED -> "Claimed ✓"
                },
                color = foreground,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ActivityScreen(activity: List<ActivityEntry>) {
    if (activity.isEmpty()) {
        EmptyState(Icons.Outlined.History, "Nothing yet", "Your Teamwork watch and claim activity will appear here.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Recent activity", color = Muted, style = MaterialTheme.typography.labelLarge) }
        items(activity.take(50), key = { it.id }) { entry -> ActivityCard(entry) }
    }
}

@Composable
private fun ActivityCard(entry: ActivityEntry) {
    val status = activityStatus(entry)
    val accent = when (status) {
        "Claimed" -> Success
        "Watching" -> Watching
        "Selected", "Checking" -> SniperOrange
        else -> Muted
    }
    val soft = when (status) {
        "Claimed" -> SuccessSoft
        "Watching" -> WatchingSoft
        "Selected", "Checking" -> SniperOrangeSoft
        else -> Assigned
    }
    val time = remember(entry.triggerEpochMs) {
        Instant.ofEpochMilli(entry.triggerEpochMs).atZone(ZoneId.systemDefault()).format(ActivityTimeFormat)
    }
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedLarge) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
            Surface(color = soft, shape = CircleShape, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (status == "Claimed") Icons.Outlined.CheckCircle else Icons.Outlined.History, null, tint = accent, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(activityTitle(entry, status), color = Ink, fontWeight = FontWeight.Bold)
                entry.openShifts.firstOrNull()?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                entry.failureReason?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Text(time + " · " + entry.decisionEngine, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            StatePill(status, soft, accent)
        }
    }
}

@Composable
private fun SettingsScreen(
    state: UiState,
    onSettings: (SniperSettings) -> Unit,
    onNotification: () -> Unit,
    onAccessibility: () -> Unit,
    onPage: (SettingsPage) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SettingsStatusCard(state) }
        item {
            SettingsGroup {
                SettingsToggleRow(
                    icon = { TargetMark(28.dp) },
                    title = "Sniper enabled",
                    subtitle = "Allow Teamwork notifications to trigger evaluation.",
                    checked = state.settings.enabled,
                    onCheckedChange = { onSettings(state.settings.copy(enabled = it)) },
                )
                DividerLine()
                SettingsToggleRow(
                    icon = { Icon(Icons.Outlined.Lock, null, tint = SniperOrange) },
                    title = "Armed mode",
                    subtitle = "Allow verified matching openings to be claimed.",
                    checked = state.settings.armed,
                    onCheckedChange = { onSettings(state.settings.copy(armed = it)) },
                )
            }
        }
        item {
            SettingsGroup {
                SettingsLinkRow(Icons.Outlined.CalendarMonth, "Shift templates", "Review expected Teamwork shift times.") { onPage(SettingsPage.TEMPLATES) }
                DividerLine()
                SettingsLinkRow(Icons.Outlined.Layers, "Overlay mode", if (state.settings.legacyOverlayEnabled) "Enabled · experimental" else "Off · native schedule is primary") { onPage(SettingsPage.OVERLAY) }
                DividerLine()
                SettingsLinkRow(Icons.Outlined.BugReport, "Diagnostics", "Permissions, runtime state and build info.") { onPage(SettingsPage.DIAGNOSTICS) }
            }
        }
        item {
            SettingsGroup {
                PermissionSettingsRow(Icons.Outlined.Notifications, "Notification access", state.permissions.notificationAccess, onNotification)
                DividerLine()
                PermissionSettingsRow(Icons.Outlined.Accessibility, "Accessibility access", state.permissions.accessibilityAccess, onAccessibility)
            }
        }
        item {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SmartToy, null, tint = Muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AI advisor", color = Ink, fontWeight = FontWeight.SemiBold)
                        val aiLabel = when {
                            !state.aiSettings.enabled -> "Optional · Off"
                            state.aiKeyPresent -> "Optional · Configured"
                            else -> "Optional · API key required"
                        }
                        Text(aiLabel, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Text(
                "Teamwork Sniper " + BuildConfig.VERSION_NAME + "\nSafe. Private. Reliable.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SettingsStatusCard(state: UiState) {
    val ready = state.permissions.notificationAccess && state.permissions.accessibilityAccess && state.settings.enabled
    val accent = if (ready) Success else SniperOrange
    val soft = if (ready) SuccessSoft else SniperOrangeSoft
    Surface(color = soft, shape = RoundedLarge) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            TargetMark(42.dp, color = accent)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(if (ready) "Sniper is ready" else "Setup needs attention", color = Ink, fontWeight = FontWeight.Bold)
                Text(if (state.settings.armed) "Armed for selected targets" else "Armed mode is currently off", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedLarge) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsToggleRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SniperOrange))
    }
}

@Composable
private fun SettingsLinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = SniperOrange, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun PermissionSettingsRow(icon: ImageVector, title: String, enabled: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) Success else SniperOrange)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(if (enabled) "Enabled" else "Required", color = if (enabled) Success else Muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun TemplateSettingsPage(onBack: () -> Unit) {
    val provider = remember { ShiftTemplateProvider() }
    val templates = provider.forDate(LocalDate.now())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SubpageHeader("Shift templates", "Expected times used in the schedule.", onBack) }
        item {
            Surface(color = SniperOrangeSoft, shape = RoundedMedium) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = SniperOrange)
                    Spacer(Modifier.width(10.dp))
                    Text("Only live-confirmed times are presented as known. Unknown templates stay visibly unconfirmed.", color = Ink, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(templates, key = { it.code.name }) { template ->
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedMedium) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(template.code.name, color = SniperOrange, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(templateTime(template), color = Ink, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (template.provenance == TemplateProvenance.LIVE_CONFIRMED) "Live-confirmed template" else "Not live-confirmed",
                            color = if (template.provenance == TemplateProvenance.LIVE_CONFIRMED) Success else Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayPreviewPage(enabled: Boolean, onToggle: (Boolean) -> Unit, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SubpageHeader("Overlay in Teamwork", "Preview how Sniper can augment empty schedule spaces.", onBack) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedLarge) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Teamwork Schedule · Preview", color = Ink, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewDay("Mon", "M1", "M2")
                        PreviewDay("Tue", "No shift", "S2")
                        PreviewDay("Wed", "M1", "M2")
                    }
                    Text("Orange outlined blocks represent Sniper choices. Availability still comes from a fresh Teamwork semantic observation.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SettingsGroup {
                SettingsToggleRow(
                    icon = { Icon(Icons.Outlined.Layers, null, tint = SniperOrange) },
                    title = "Overlay mode",
                    subtitle = "Experimental. Native Teamwork Sniper schedule remains the reliable fallback.",
                    checked = enabled,
                    onCheckedChange = onToggle,
                )
            }
        }
    }
}

@Composable
private fun RowScope.PreviewDay(day: String, first: String, second: String) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(day, color = Muted, style = MaterialTheme.typography.labelMedium)
        PreviewCell(first, snipe = first == "No shift")
        PreviewCell(second, snipe = true)
    }
}

@Composable
private fun PreviewCell(label: String, snipe: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(74.dp),
        color = if (snipe) Color.White else Assigned,
        shape = RoundedSmall,
        border = if (snipe) BorderStroke(1.dp, SniperOrange) else null,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.Center) {
            Text(if (label == "No shift") "S1" else label, color = if (snipe) SniperOrange else Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Text(if (snipe) "Snipe" else "Assigned", color = if (snipe) SniperOrange else Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DiagnosticsPage(state: UiState, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SubpageHeader("Diagnostics", "Simple runtime health, without exposing sensitive data.", onBack) }
        item {
            SettingsGroup {
                DiagnosticRow("Build", BuildConfig.VERSION_NAME, true)
                DividerLine()
                DiagnosticRow("Notification access", if (state.permissions.notificationAccess) "Ready" else "Missing", state.permissions.notificationAccess)
                DividerLine()
                DiagnosticRow("Accessibility access", if (state.permissions.accessibilityAccess) "Ready" else "Missing", state.permissions.accessibilityAccess)
                DividerLine()
                DiagnosticRow("Saved targets", targetCount(state.rules).toString(), true)
                DividerLine()
                DiagnosticRow("Activity records", state.activity.size.toString(), true)
                DividerLine()
                DiagnosticRow("AI advisor", if (state.aiSettings.enabled && state.aiKeyPresent) "Configured" else "Optional / off", true)
            }
        }
        item {
            Surface(color = Assigned, shape = RoundedMedium) {
                Text(
                    "A green build or permission state is not proof that a real Teamwork claim succeeded. Claim success requires Teamwork post-action verification.",
                    modifier = Modifier.padding(14.dp),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        StatePill(value, if (ok) SuccessSoft else SniperOrangeSoft, if (ok) Success else SniperOrange)
    }
}

@Composable
private fun SubpageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = Ink) }
        Column {
            Text(title, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BackHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = Ink) }
        Column(Modifier.padding(top = 7.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = SniperOrangeSoft, shape = CircleShape, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = SniperOrange, modifier = Modifier.size(34.dp)) }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(body, color = Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, trailing: ImageVector? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedMedium,
        colors = ButtonDefaults.buttonColors(containerColor = SniperOrange, disabledContainerColor = Line, disabledContentColor = Muted),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Icon(it, null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatePill(label: String, background: Color, foreground: Color) {
    Surface(color = background, shape = CircleShape) {
        Text(
            label,
            color = foreground,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun DividerLine() {
    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(start = 57.dp))
}

@Composable
private fun TargetMark(size: androidx.compose.ui.unit.Dp, color: Color = SniperOrange) {
    Canvas(Modifier.size(size)) {
        val stroke = size.toPx() * 0.075f
        val radius = this.size.minDimension * 0.31f
        drawCircle(color = color, radius = radius, style = Stroke(width = stroke))
        drawCircle(color = color, radius = radius * 0.34f, style = Stroke(width = stroke))
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val gap = radius * 0.52f
        drawLine(color, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, cy - gap), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(cx, cy + gap), androidx.compose.ui.geometry.Offset(cx, this.size.height), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, cy), androidx.compose.ui.geometry.Offset(cx - gap, cy), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(cx + gap, cy), androidx.compose.ui.geometry.Offset(this.size.width, cy), stroke)
    }
}

private fun templateTime(template: ShiftTemplate): String =
    template.start?.let { start ->
        template.end?.let { end -> start.format(TimeFormat) + " – " + end.format(TimeFormat) }
    } ?: "Time to be confirmed"

private fun targetCount(rules: List<ShiftRule>): Int = exactTargets(rules).size

private fun exactTargets(rules: List<ShiftRule>): List<Pair<LocalDate, ShiftCode>> =
    rules.filter { it.enabled && it.dates.size == 1 && it.codes.size == 1 }
        .mapNotNull { rule ->
            val date = rule.dates.single()
            val code = rule.codes.single()
            if (TargetSelectionRules.isExactTarget(rule, date, code)) date to code else null
        }
        .distinct()
        .sortedWith(compareBy<Pair<LocalDate, ShiftCode>> { it.first }.thenBy { it.second.order })

private fun claimedKeys(activity: List<ActivityEntry>): Set<String> =
    activity.asSequence()
        .filter { it.claimAttempted && it.verificationResult == "TARGET_NO_LONGER_OPEN" }
        .flatMap { it.openShifts.asSequence() }
        .mapNotNull(::activityShiftKey)
        .toSet()

private fun recentOpenKeys(activity: List<ActivityEntry>): Set<String> {
    val cutoff = System.currentTimeMillis() - 15 * 60 * 1000L
    return activity.asSequence()
        .filter { it.triggerEpochMs >= cutoff && it.openShifts.isNotEmpty() && it.verificationResult != "TARGET_NO_LONGER_OPEN" }
        .flatMap { it.openShifts.asSequence() }
        .mapNotNull(::activityShiftKey)
        .toSet()
}

private fun activityShiftKey(raw: String): String? {
    val match = Regex("^(\\d{4}-\\d{2}-\\d{2})\\s+([MS]\\d+)").find(raw) ?: return null
    val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
    val code = ShiftCode.fromRaw(match.groupValues[2]) ?: return null
    return targetKey(date, code)
}

private fun targetKey(date: LocalDate, code: ShiftCode) = date.toString() + "|" + code.name

private fun activityStatus(entry: ActivityEntry): String = when {
    entry.verificationResult == "TARGET_NO_LONGER_OPEN" -> "Claimed"
    entry.decision == "TARGET_SELECTED" -> "Selected"
    entry.claimAttempted -> "Checking"
    entry.openShifts.isNotEmpty() -> "Watching"
    else -> "Update"
}

private fun activityTitle(entry: ActivityEntry, status: String): String = when (status) {
    "Claimed" -> "Shift claimed successfully"
    "Watching" -> "Open shift detected"
    "Selected" -> "Shift selected"
    "Checking" -> "Claim attempt checked"
    else -> entry.decision.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Sniper update" }
}

private fun sniperPalette() = lightColorScheme(
    primary = SniperOrange,
    onPrimary = Color.White,
    secondary = SniperOrange,
    background = AppBackground,
    onBackground = Ink,
    surface = CardSurface,
    onSurface = Ink,
    outline = Line,
)
