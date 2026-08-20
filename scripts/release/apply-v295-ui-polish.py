from pathlib import Path

UI = Path("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/CycloneMobileV292App.kt")
DEBUG = Path("apps/mobile/app/src/main/java/com/cyclone/mobile/debug/PageDebugSandboxV293.kt")

ui = UI.read_text(encoding="utf-8")
ui_original = ui


def replace_ui(old: str, new: str, *, label: str) -> None:
    global ui
    if new in ui:
        return
    if old not in ui:
        raise SystemExit(f"Cannot apply {label}: expected source marker not found")
    ui = ui.replace(old, new, 1)


# Profile badge is the one Settings entry; no redundant top-right settings button.
if "import androidx.compose.foundation.clickable\n" not in ui:
    ui = ui.replace(
        "import androidx.compose.foundation.background\n",
        "import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n",
        1,
    )
if "import com.cyclone.mobile.CycloneRelease\n" not in ui:
    ui = ui.replace(
        "import com.cyclone.mobile.CycloneAccessibilityService\n",
        "import com.cyclone.mobile.CycloneAccessibilityService\nimport com.cyclone.mobile.CycloneRelease\n",
        1,
    )

replace_ui(
    'modifier = Modifier.padding(start = 12.dp).size(42.dp),',
    'modifier = Modifier.padding(start = 12.dp).size(42.dp).clickable { settingsOpen = true },',
    label="profile-to-settings navigation",
)
replace_ui(
    'Text("Cyclone 2.9.3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)',
    'Text(CycloneRelease.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)',
    label="global release label",
)
replace_ui(
    'actions = { if (!settingsOpen) IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.SettingsIcon, "Settings") } },',
    'actions = {},',
    label="remove redundant settings action",
)
replace_ui(
    'V292Hero(Icons.Rounded.School, "Teach Cyclone", "2.9.3 adds a Page Awareness Sandbox so you can freeze a real page and inspect raw Android evidence, semantic controls, the exact Page Agent payload, Brain/App Graph recall and execution-free model probes.")',
    'V292Hero(Icons.Rounded.School, "Teach Cyclone", "Page Awareness Sandbox lets you freeze a real page and inspect raw Android evidence, semantic controls, the exact Page Agent payload, Brain/App Graph recall and execution-free model probes.")',
    label="Teach version copy",
)
replace_ui(
    'V292Hero(Icons.Rounded.SettingsIcon, "Cyclone 2.9.2 settings", "Phone permissions, result notifications and optional Cyclone Core connection.")',
    'V292Hero(Icons.Rounded.SettingsIcon, "${CycloneRelease.label} settings", "Phone permissions, result notifications and optional Cyclone Core connection.")',
    label="Settings release copy",
)

# Put the full Gateway prominently inside AI, immediately after the AI hero.
ai_start = ui.index("private fun V292AiPage")
ai_tail = ui[ai_start:]
if "GatewayAiCard(context, refreshTick)" not in ai_tail:
    mode_marker = "        item {\n            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {\n                V292ModeCard(mode == V292AiMode.PHONE"
    if mode_marker not in ai_tail:
        raise SystemExit("Cannot place Gateway AI card: mode-card marker not found")
    ai_tail = ai_tail.replace(
        mode_marker,
        "        item { GatewayAiCard(context, refreshTick) }\n" + mode_marker,
        1,
    )
    ui = ui[:ai_start] + ai_tail

for stale in ("Cyclone 2.9.3", "Cyclone 2.9.2 settings", "2.9.3 adds a Page Awareness Sandbox"):
    if stale in ui:
        raise SystemExit(f"Stale user-visible release string remains in product UI: {stale}")

required = (
    'HOME("Home"', 'TEACH("Teach"', 'AI("AI"', 'AUTOMATIONS("Automations"', 'BRAIN("Brain"',
    'GatewayAiCard(context, refreshTick)', 'CycloneRelease.label', '.clickable { settingsOpen = true }',
)
missing = [marker for marker in required if marker not in ui]
if missing:
    raise SystemExit(f"Required UI markers missing after polish: {missing}")

if ui != ui_original:
    UI.write_text(ui, encoding="utf-8")
    print("Applied Cyclone 2.9.5 main UI polish")
else:
    print("Cyclone 2.9.5 main UI polish already applied")

# Page Awareness is a visible product surface too. Keep its internal V293 schema/class
# names for compatibility, but never show historical release numbers to the user.
debug = DEBUG.read_text(encoding="utf-8")
debug_original = debug

if "import com.cyclone.mobile.CycloneRelease\n" not in debug:
    debug = debug.replace(
        "import com.cyclone.mobile.CycloneAccessibilityService\n",
        "import com.cyclone.mobile.CycloneAccessibilityService\nimport com.cyclone.mobile.CycloneRelease\n",
        1,
    )

debug_replacements = (
    ("* Cyclone 2.9.3 diagnostic sandbox.", "* Cyclone Page Awareness diagnostic sandbox."),
    ('header("X-Title", "Cyclone Mobile 2.9.3 Page Debug Sandbox")', 'header("X-Title", "${CycloneRelease.label} Page Debug Sandbox")'),
    ('text = "◉ PAGE DEBUG · 2.9.3"', 'text = "◉ PAGE DEBUG · ${CycloneRelease.version}"'),
    ('title = "Cyclone 2.9.3 Page Debug Sandbox"', 'title = "${CycloneRelease.label} Page Debug Sandbox"'),
    ('text = "Cyclone 2.9.3 · Page Awareness Sandbox"', 'text = "${CycloneRelease.label} · Page Awareness Sandbox"'),
    ('ClipData.newPlainText("Cyclone 2.9.3 page debug", body.text)', 'ClipData.newPlainText("${CycloneRelease.label} page debug", body.text)'),
)
for old, new in debug_replacements:
    if new in debug:
        continue
    if old not in debug:
        raise SystemExit(f"Cannot update Page Debug release label: {old}")
    debug = debug.replace(old, new, 1)

if "Cyclone 2.9.3" in debug:
    raise SystemExit("Stale visible 2.9.3 Page Debug label remains")

if debug != debug_original:
    DEBUG.write_text(debug, encoding="utf-8")
    print("Applied global release identity to Page Awareness Sandbox")
else:
    print("Page Awareness Sandbox release labels already unified")
