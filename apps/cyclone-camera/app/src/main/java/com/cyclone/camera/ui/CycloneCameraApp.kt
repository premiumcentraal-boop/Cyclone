package com.cyclone.camera.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cyclone.camera.BuildConfig
import com.cyclone.camera.engine.CameraMode
import com.cyclone.camera.engine.EngineState
import com.cyclone.camera.engine.IntegrityTier
import com.cyclone.camera.engine.LogEntry
import com.cyclone.camera.engine.LogLevel

private object C {
    val Carbon = Color(0xFF171A23)
    val CarbonRaised = Color(0xFF212635)
    val Plate = Color(0xFF343B57)
    val PlateLight = Color(0xFF465174)
    val Sky = Color(0xFF9FBEE7)
    val Indigo = Color(0xFF3D4F97)
    val Hairline = Color(0xFF606C9D)
    val White = Color(0xFFF6F7FB)
    val Muted = Color(0xFFAEB8D2)
    val Amber = Color(0xFFECAB37)
    val Signal = Color(0xFFF68D1F)
    val Red = Color(0xFFE60012)
    val Green = Color(0xFF49BE83)
    val Grey = Color(0xFF72798D)
}

@Composable
fun CycloneCameraApp(viewModel: CameraViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let(viewModel::selectFile)
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = C.Signal,
            background = C.Carbon,
            surface = C.CarbonRaised,
            onSurface = C.White,
        ),
        typography = MaterialTheme.typography.copy(
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(C.Carbon)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            TopBar(
                title = if (state.settingsOpen) "SETTINGS" else when (state.tab) {
                    MainTab.HOME -> "HOME"
                    MainTab.SYSTEM -> "SYSTEM"
                    MainTab.LOGS -> "LOGS"
                },
                settingsOpen = state.settingsOpen,
                logsOpen = state.tab == MainTab.LOGS,
                onBack = viewModel::closeSettings,
                onSettings = viewModel::openSettings,
                onShare = {
                    val dump = state.logs.joinToString("\n") { log ->
                        buildString {
                            append(log.timestamp).append(" [").append(log.level.name).append("] ").append(log.message)
                            log.hint?.let { append(" — ").append(it) }
                        }
                    }
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Cyclone Camera logs")
                                putExtra(Intent.EXTRA_TEXT, dump)
                            },
                            "Share logs",
                        ),
                    )
                },
            )

            Box(Modifier.weight(1f)) {
                if (state.settingsOpen) {
                    SettingsScreen(state, viewModel)
                } else {
                    when (state.tab) {
                        MainTab.HOME -> HomeScreen(state, viewModel, onPickFile = { picker.launch("video/*") })
                        MainTab.SYSTEM -> SystemScreen(
                            state,
                            viewModel,
                            onReboot = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_REBOOT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                        )
                        MainTab.LOGS -> LogsScreen(state, viewModel)
                    }
                }
            }

            if (!state.settingsOpen) BottomBar(state.tab, viewModel::selectTab)
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    settingsOpen: Boolean,
    logsOpen: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onShare: () -> Unit,
) {
    CarbonTexture {
        Row(
            modifier = Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (settingsOpen) {
                IconControl(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(8.dp))
            } else {
                LogoPill()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("CYCLONE CAMERA", color = C.White, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = .45.sp)
                Text(title, color = C.Amber, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
            if (!settingsOpen && logsOpen) IconControl(Icons.Default.Share, "Share logs", onShare)
            if (!settingsOpen) {
                Spacer(Modifier.width(4.dp))
                IconControl(Icons.Default.Settings, "Settings", onSettings)
            }
        }
    }
}

@Composable
private fun LogoPill() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(C.White)
            .border(2.dp, C.Red, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Icon(Icons.Default.Videocam, null, tint = C.Red, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun IconControl(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CutCornerShape(5.dp)).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = C.Sky, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun BottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(70.dp).background(C.CarbonRaised).border(1.dp, C.Hairline).navigationBarsPadding(),
    ) {
        BottomItem("HOME", Icons.Default.Home, MainTab.HOME, selected, onSelect)
        BottomItem("SYSTEM", Icons.Default.Tune, MainTab.SYSTEM, selected, onSelect)
        BottomItem("LOGS", Icons.Default.Description, MainTab.LOGS, selected, onSelect)
    }
}

@Composable
private fun RowScope.BottomItem(label: String, icon: ImageVector, tab: MainTab, selected: MainTab, onSelect: (MainTab) -> Unit) {
    val active = tab == selected
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight().background(if (active) C.Indigo else Color.Transparent).clickable { onSelect(tab) },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (active) C.White else C.Muted, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (active) C.Amber else C.Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
    }
}

@Composable
private fun HomeScreen(state: CameraUiState, vm: CameraViewModel, onPickFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(2.dp))
        StatusPill(state.engineState, vm.errorHint(), vm::quickOff)
        ModeSwitch(state.mode, vm::setMode)
        SourceCard(state, onPickFile, vm::toggleStream, vm::setStreamUrl, vm::applyStream)
        ChromeCard { SwitchRow("Loop video", state.loopVideo, { vm.toggleLoop() }) }
        PrimaryButton(
            label = if (state.engineState == EngineState.OFF) "ARM" else "DISARM",
            alert = state.engineState != EngineState.OFF,
            onClick = vm::toggleArm,
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StatusPill(state: EngineState, hint: String?, onQuickOff: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "statusPulse")
    val pulse by infinite.animateFloat(
        initialValue = .78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "statusAlpha",
    )
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    val color = when (state) {
        EngineState.OFF -> C.Grey
        EngineState.ARMED -> C.Green
        EngineState.INJECTING -> C.Amber
        is EngineState.ERROR -> C.Red
    }
    val label = when (state) {
        EngineState.OFF -> "OFF"
        EngineState.ARMED -> "ARMED"
        EngineState.INJECTING -> "INJECTING"
        is EngineState.ERROR -> "ERROR: ${state.reason}"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .alpha(if (state == EngineState.INJECTING) pulse else 1f)
                .background(color, RoundedCornerShape(999.dp))
                .pointerInput(Unit) {
                    detectTapGestures {
                        val now = System.currentTimeMillis()
                        taps = if (now - lastTap < 550) taps + 1 else 1
                        lastTap = now
                        if (taps >= 3) {
                            taps = 0
                            onQuickOff()
                        }
                    }
                }
                .semantics { contentDescription = "$label status. Triple tap for quick off." }
                .padding(horizontal = 15.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(C.White, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(label, color = C.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = .55.sp)
        }
        if (state is EngineState.ERROR && !hint.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(hint, color = C.Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ModeSwitch(selected: CameraMode, onSelect: (CameraMode) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("CAMERA MODE")
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().height(62.dp).background(C.CarbonRaised, CutCornerShape(7.dp)).padding(4.dp)) {
            CameraMode.entries.forEach { mode ->
                val active = mode == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (active) C.Indigo else Color.Transparent, CutCornerShape(4.dp))
                        .border(if (active) 1.dp else 0.dp, if (active) C.Sky else Color.Transparent, CutCornerShape(4.dp))
                        .clickable { onSelect(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(mode.name, color = if (active) C.White else C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    state: CameraUiState,
    onPickFile: () -> Unit,
    onToggleStream: () -> Unit,
    onUrlChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    ChromeCard {
        SectionLabel("VIDEO SOURCE")
        Spacer(Modifier.height(9.dp))
        if (state.source == null) {
            Text("No source selected", color = C.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Text("—", color = C.Muted, fontSize = 12.sp)
        } else {
            Text(state.source.label, color = C.White, fontWeight = FontWeight.Black, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${state.source.resolution}  •  ${state.source.fps} fps", color = C.Sky, fontSize = 11.sp)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UtilityButton("CHANGE", Modifier.weight(1f), onPickFile)
            UtilityButton("STREAM", Modifier.weight(1f), onToggleStream)
        }
        if (state.streamExpanded) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = state.streamUrl,
                    onValueChange = onUrlChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("rtmp:// or rtsp://", color = C.Grey, fontSize = 11.sp) },
                    isError = state.streamUrlInvalid,
                    singleLine = true,
                    textStyle = TextStyle(color = C.White, fontSize = 11.sp),
                )
                UtilityButton("APPLY", Modifier.width(76.dp), onApply, signal = true)
            }
            if (state.streamUrlInvalid) Text("Enter a valid RTMP or RTSP URL", color = C.Red, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun SystemScreen(state: CameraUiState, vm: CameraViewModel, onReboot: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChromeCard {
            SectionLabel("SYSTEM COMPONENT")
            Spacer(Modifier.height(10.dp))
            when {
                state.setupComplete -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp).background(C.Green, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, null, tint = C.White, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(9.dp))
                        Text("System ready ✓", color = C.Green, fontWeight = FontWeight.Black)
                    }
                    if (state.rebootReady) {
                        Spacer(Modifier.height(12.dp))
                        UtilityButton("REBOOT NOW", Modifier.fillMaxWidth(), onReboot, signal = true)
                    }
                }
                state.setupRunning -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = C.Signal, strokeWidth = 3.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Installing system component…", color = C.White, fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    Text("One-time system component required", color = C.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    UtilityButton("RUN SETUP", Modifier.fillMaxWidth(), vm::runSetup, signal = true)
                }
            }
        }
        ChromeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("INTEGRITY")
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.height(40.dp).clickable(onClick = vm::refreshIntegrity).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Refresh, null, tint = C.Amber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("RE-CHECK", color = C.Amber, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                IntegrityTier.entries.forEach { tier ->
                    val result = state.integrity.firstOrNull { it.tier == tier }
                    IntegrityPill(tier.name, result?.passed == true, Modifier.weight(1f))
                }
            }
        }
        ChromeCard {
            SectionLabel("OPTIONS")
            Spacer(Modifier.height(4.dp))
            SwitchRow("Hide app icon", state.engineSettings.hideAppIcon, vm::setHideIcon)
            DottedDivider()
            SwitchRow("Steady motion data", state.engineSettings.sensorLock, vm::setSensorLock)
            DottedDivider()
            SwitchRow("Natural frame timing", state.engineSettings.jitterInjection, vm::setJitter)
        }
    }
}

@Composable
private fun IntegrityPill(label: String, passed: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(38.dp)
            .background(if (passed) C.Green.copy(alpha = .18f) else C.Carbon, RoundedCornerShape(999.dp))
            .border(1.dp, if (passed) C.Green else C.Hairline, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(if (passed) "✓" else "—", color = if (passed) C.Green else C.Grey, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(4.dp))
        Text(label, color = C.White, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = .25.sp)
    }
}

@Composable
private fun LogsScreen(state: CameraUiState, vm: CameraViewModel) {
    val visible = state.logs.filter { entry ->
        when (state.logFilter) {
            LogFilter.ALL -> true
            LogFilter.INFO -> entry.level == LogLevel.INFO
            LogFilter.WARN -> entry.level == LogLevel.WARN
            LogFilter.ERROR -> entry.level == LogLevel.ERROR
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            LogFilter.entries.forEach { filter -> FilterChip(filter.name, state.logFilter == filter) { vm.setLogFilter(filter) } }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No logs yet", color = C.Muted, fontSize = 13.sp) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(visible) { entry -> LogRow(entry) }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(38.dp)
            .background(if (selected) C.Indigo else C.CarbonRaised, CutCornerShape(4.dp))
            .border(1.dp, if (selected) C.Sky else C.Hairline, CutCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) C.White else C.Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.INFO -> C.Sky
        LogLevel.WARN -> C.Amber
        LogLevel.ERROR -> C.Red
    }
    ChromeCard(padding = 11.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Text(entry.timestamp, color = C.Muted, fontSize = 10.sp, modifier = Modifier.width(62.dp))
            Box(Modifier.padding(top = 4.dp).size(7.dp).background(levelColor, CircleShape))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.message, color = C.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (entry.level == LogLevel.ERROR && !entry.hint.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(entry.hint, color = C.Muted, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: CameraUiState, vm: CameraViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChromeCard {
            SectionLabel("RESOLUTION OVERRIDE")
            Spacer(Modifier.height(10.dp))
            ResolutionDropdown(state.engineSettings.resolutionOverride, vm::setResolution)
        }
        ChromeCard {
            SwitchRow("Auto-disarm on screen lock", state.engineSettings.autoDisarmOnLock, vm::setAutoDisarm)
        }
        ChromeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("App version", color = C.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(BuildConfig.VERSION_NAME, color = C.Amber, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun ResolutionDropdown(selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null, "1920x1080", "1280x720", "640x480")
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(C.Carbon, CutCornerShape(3.dp))
                .border(1.dp, C.Hairline, CutCornerShape(3.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected ?: "Match source", color = C.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, null, tint = C.Amber)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(C.CarbonRaised)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option ?: "Match source", color = C.White) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.White,
                checkedTrackColor = C.Signal,
                uncheckedThumbColor = C.Muted,
                uncheckedTrackColor = C.Carbon,
                uncheckedBorderColor = C.Hairline,
            ),
        )
    }
}

@Composable
private fun PrimaryButton(label: String, alert: Boolean, onClick: () -> Unit) {
    val fill = if (alert) C.Red else C.Signal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(fill, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .border(2.dp, C.White.copy(alpha = .75f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, color = C.White, fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 1.2.sp)
    }
}

@Composable
private fun UtilityButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit, signal: Boolean = false) {
    Box(
        modifier = modifier
            .height(44.dp)
            .background(if (signal) C.Signal else C.Amber, CutCornerShape(3.dp))
            .border(1.dp, C.White.copy(alpha = .65f), CutCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (signal) C.White else C.Carbon, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = .45.sp)
    }
}

@Composable
private fun ChromeCard(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp)
    Box(modifier = modifier.fillMaxWidth().padding(bottom = 2.dp, end = 1.dp)) {
        Box(Modifier.matchParentSize().offset(1.dp, 2.dp).background(C.Indigo, shape))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(C.PlateLight, C.Plate)), shape)
                .border(1.dp, C.Sky.copy(alpha = .65f), shape)
                .padding(padding),
            content = content,
        )
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(label, color = C.Sky, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = .85.sp)
}

@Composable
private fun DottedDivider() {
    Canvas(Modifier.fillMaxWidth().height(5.dp)) {
        var x = 0f
        val step = 6.dp.toPx()
        while (x < size.width) {
            drawCircle(C.Hairline.copy(alpha = .7f), 1.dp.toPx(), Offset(x, size.height / 2))
            x += step
        }
    }
}

@Composable
private fun CarbonTexture(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(C.CarbonRaised)
            .drawBehind {
                val gap = 8.dp.toPx()
                var y = gap / 2
                while (y < size.height) {
                    var x = gap / 2
                    while (x < size.width) {
                        drawCircle(C.Sky.copy(alpha = .09f), .7.dp.toPx(), Offset(x, y))
                        x += gap
                    }
                    y += gap
                }
            },
        content = content,
    )
}
