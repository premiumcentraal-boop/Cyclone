from pathlib import Path

UI = Path("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/CycloneMobileV292App.kt")
text = UI.read_text(encoding="utf-8")
original = text


def replace_once(old: str, new: str, *, label: str) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Cannot apply {label}: expected source marker not found")
    text = text.replace(old, new, 1)


# Profile badge is the one Settings entry; no redundant top-right settings button.
if "import androidx.compose.foundation.clickable\n" not in text:
    text = text.replace(
        "import androidx.compose.foundation.background\n",
        "import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n",
        1,
    )
if "import com.cyclone.mobile.CycloneRelease\n" not in text:
    text = text.replace(
        "import com.cyclone.mobile.CycloneAccessibilityService\n",
        "import com.cyclone.mobile.CycloneAccessibilityService\nimport com.cyclone.mobile.CycloneRelease\n",
        1,
    )

replace_once(
    'modifier = Modifier.padding(start = 12.dp).size(42.dp),',
    'modifier = Modifier.padding(start = 12.dp).size(42.dp).clickable { settingsOpen = true },',
    label="profile-to-settings navigation",
)
replace_once(
    'Text("Cyclone 2.9.3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)',
    'Text(CycloneRelease.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)',
    label="global release label",
)
replace_once(
    'actions = { if (!settingsOpen) IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.SettingsIcon, "Settings") } },',
    'actions = {},',
    label="remove redundant settings action",
)

# Remove stale historical release numbers from user-visible text.
replace_once(
    'V292Hero(Icons.Rounded.School, "Teach Cyclone", "2.9.3 adds a Page Awareness Sandbox so you can freeze a real page and inspect raw Android evidence, semantic controls, the exact Page Agent payload, Brain/App Graph recall and execution-free model probes.")',
    'V292Hero(Icons.Rounded.School, "Teach Cyclone", "Page Awareness Sandbox lets you freeze a real page and inspect raw Android evidence, semantic controls, the exact Page Agent payload, Brain/App Graph recall and execution-free model probes.")',
    label="Teach version copy",
)
replace_once(
    'V292Hero(Icons.Rounded.SettingsIcon, "Cyclone 2.9.2 settings", "Phone permissions, result notifications and optional Cyclone Core connection.")',
    'V292Hero(Icons.Rounded.SettingsIcon, "${CycloneRelease.label} settings", "Phone permissions, result notifications and optional Cyclone Core connection.")',
    label="Settings release copy",
)

# Put the full Gateway prominently inside AI, immediately after the AI hero.
ai_start = text.index("private fun V292AiPage")
ai_tail = text[ai_start:]
if "GatewayAiCard(context, refreshTick)" not in ai_tail:
    mode_marker = "        item {\n            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {\n                V292ModeCard(mode == V292AiMode.PHONE"
    if mode_marker not in ai_tail:
        raise SystemExit("Cannot place Gateway AI card: mode-card marker not found")
    ai_tail = ai_tail.replace(
        mode_marker,
        "        item { GatewayAiCard(context, refreshTick) }\n" + mode_marker,
        1,
    )
    text = text[:ai_start] + ai_tail

# Guard against the exact stale strings that caused the 2.9.5 UX regression.
for stale in ("Cyclone 2.9.3", "Cyclone 2.9.2 settings", "2.9.3 adds a Page Awareness Sandbox"):
    if stale in text:
        raise SystemExit(f"Stale user-visible release string remains: {stale}")

required = (
    'HOME("Home"', 'TEACH("Teach"', 'AI("AI"', 'AUTOMATIONS("Automations"', 'BRAIN("Brain"',
    'GatewayAiCard(context, refreshTick)', 'CycloneRelease.label', '.clickable { settingsOpen = true }',
)
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit(f"Required UI markers missing after polish: {missing}")

if text != original:
    UI.write_text(text, encoding="utf-8")
    print("Applied Cyclone 2.9.5 unified UI polish")
else:
    print("Cyclone 2.9.5 unified UI polish already applied")
