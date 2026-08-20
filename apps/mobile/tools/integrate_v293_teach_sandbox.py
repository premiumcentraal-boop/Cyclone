#!/usr/bin/env python3
"""One-time source integration for the 2.9.3 diagnostic Teach card.

The active mobile shell is still intentionally kept in CycloneMobileV292App.kt for compatibility with
2.9.x state/migrations. This helper performs a small, checked, idempotent edit rather than copying the
~50KB shell into another version-named file. CI runs it before compilation and persists the resulting
source on release-branch pushes.
"""
from pathlib import Path

path = Path("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/CycloneMobileV292App.kt")
text = path.read_text(encoding="utf-8")

if "com.cyclone.mobile.debug.PageDebugSandboxV293" not in text:
    anchor = "import com.cyclone.mobile.guided.TeachingGestureEvidenceV292\n"
    if anchor not in text:
        raise SystemExit("Could not locate V292 import anchor")
    text = text.replace(anchor, anchor + "import com.cyclone.mobile.debug.PageDebugSandboxV293\n", 1)

text = text.replace('Text("Cyclone 2.9.2", style = MaterialTheme.typography.labelSmall', 'Text("Cyclone 2.9.3", style = MaterialTheme.typography.labelSmall', 1)
text = text.replace(
    'V292Hero(Icons.Rounded.School, "Teach Cyclone", "2.9.2 turns what you demonstrate into semantic pages, directional gestures, Brain evidence and a reviewable routine instead of leaving it as a passive recording.")',
    'V292Hero(Icons.Rounded.School, "Teach Cyclone", "2.9.3 adds a Page Awareness Sandbox so you can freeze a real page and inspect raw Android evidence, semantic controls, the exact Page Agent payload, Brain/App Graph recall and execution-free model probes.")',
    1,
)

marker = 'Text("Page Awareness Sandbox", fontWeight = FontWeight.Bold)'
if marker not in text:
    anchor = '        item { OutlinedButton(onClick = { RoutineTeachingRuntime.launchReport(context, null) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.History, null); Spacer(Modifier.width(5.dp)); Text("Teaching history, AI notes & corrections") } }\n'
    if anchor not in text:
        raise SystemExit("Could not locate Teach history anchor")
    card = '''        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Page Awareness Sandbox", fontWeight = FontWeight.Bold)
                            Text("Freeze what Android sees, what PageContext keeps, what the Page Agent actually receives, and A/B-test the harness without executing actions.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = {
                        val service = CycloneAccessibilityService.instance
                        if (service == null) Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                        else {
                            PageDebugSandboxV293.start(service)
                            Toast.makeText(context, "PAGE DEBUG overlay started — capture the target app pages", Toast.LENGTH_SHORT).show()
                            (context as? Activity)?.moveTaskToBack(true)
                        }
                    }, enabled = !follow.active, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.width(5.dp)); Text("Start page sandbox")
                    }
                    OutlinedButton(onClick = { PageDebugSandboxV293.launchReport(context) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Info, null); Spacer(Modifier.width(5.dp)); Text("Open sandbox inspector")
                    }
                    Text("Model A/B probes are opt-in, use the selected OpenRouter model, make five calls on one frozen page, and never execute their proposed actions.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
'''
    text = text.replace(anchor, card + anchor, 1)

path.write_text(text, encoding="utf-8")
print("Cyclone 2.9.3 Teach sandbox integration is present")
