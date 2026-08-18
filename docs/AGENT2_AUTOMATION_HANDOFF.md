# Agent 2 — Cyclone Mobile Automation Studio Handoff

Branch: `feature/mobile-automation-studio`  
Draft PR: #3, targeting `feature/android-mobile-v0`

## Ownership

Agent 2 owns the automation/skill layer only:

- typed automation, trigger, condition, step, variable, selector, skill, run and checkpoint models
- deterministic workflow execution
- trigger routing and schedules
- workflow recording
- run history and human-wait checkpoints
- Automation Studio UI
- Agent-3 proposal compilation into Agent-2 typed definitions

Agent 2 does **not** own Android Accessibility internals or Hermes planning.

## Agent 1 integration — actual landed contract

Agent 1 now exposes these concrete types on `feature/mobile-phone-toolbox-agent1`:

```kotlin
com.cyclone.mobile.PhoneToolExecutor.execute(
    context: Context,
    request: com.cyclone.mobile.PhoneToolRequest
): com.cyclone.mobile.PhoneToolResult
```

Agent 1 request:

```kotlin
PhoneToolRequest(
    commandId: String,
    tool: String,
    params: JSONObject
)
```

Agent 1 result includes `ok`, timestamps, attempts, before/after fingerprints, payload and typed `PhoneToolError`.

Agent 2 currently consumes its own intentionally small boundary:

```kotlin
fun interface PhoneToolGateway {
    fun execute(request: automation.PhoneToolRequest): automation.PhoneToolResult
}
```

During integration, replace `LegacyPhoneToolAdapter` with one adapter that:

1. creates a unique command ID for each automation tool call;
2. maps `request.name` to Agent 1 `tool` unchanged (`phone.*`);
3. copies scalar automation arguments into Agent 1 `params`;
4. maps Agent 2 `Selector` into Agent 1 `ElementSelector` JSON;
5. calls `PhoneToolExecutor.execute(context, request)`;
6. maps `ok`, payload and typed error back into the automation result;
7. preserves Agent 1 `FRESH_OBSERVATION_REQUIRED`, `HUMAN_HAS_CONTROL`, duplicate suppression and post-action verification semantics rather than reimplementing them.

Do not make `AutomationRunner` call `CycloneAccessibilityService` directly.

### Selector mapping

Agent 2 -> Agent 1:

- `resourceId` -> `resourceId`
- `text` -> `text`
- `partialText` -> `textContains`
- `contentDescription` -> `contentDescription`
- `className` -> `class`
- `role` -> `role`
- `ancestor` -> `ancestorText`
- `descendant` -> `descendantText`
- `x/y` -> `x/y`
- relative/fuzzy fields should be normalized to Agent 1 `relativeToText`, `relativeDirection`, `fuzzyText`, and `minFuzzyScore` as the shared selector model is consolidated.

Agent 1 already provides `phone.wait_for` and `phone.assert`; prefer those for phone-state waits/assertions over model/server polling.

## Agent 3 integration — actual landed proposal shape

Agent 3 currently produces review documents shaped like:

```json
{
  "name": "Open battery settings",
  "trigger": {"type": "manual"},
  "steps": [
    {"type": "phone_tool", "tool": "phone.open_app", "params": {"package": "com.android.settings"}},
    {"type": "phone_tool", "tool": "phone.click", "params": {"selector": {"text": "Battery"}}},
    {"type": "assertion", "condition": {"text": "Battery"}}
  ]
}
```

This is deliberately **not** identical to Agent 2's persisted Kotlin JSON. Never pass the raw Hermes document to `AutomationCodec` and never execute it directly.

Use:

```kotlin
AutomationRuntime.importAiProposal(context, proposalJson)
```

That method calls `AutomationProposalCompiler`, persists only a successfully compiled `AutomationDefinition`, and always leaves the generated automation `enabled = false` for review.

The compiler currently normalizes:

- lowercase Agent-3 trigger/step names into typed Agent-2 enums
- top-level Agent-3 `tool` + `params` into `StepDefinition.parameters`
- nested phone selectors into Agent-2 selectors
- screen assertions into deterministic `phone.assert` steps
- wait conditions into deterministic `phone.wait_for` steps
- `set_variable` into `VARIABLE_ASSIGNMENT`
- `cyclone_event` into `SEND_CYCLONE_EVENT`
- recovery policy names into Agent-2 `RecoveryPolicy`
- takeover `resumeCondition` into a post-takeover `phone.assert` verification step

It rejects:

- unknown trigger or step types
- non-`phone.*` phone tools
- consequential steps without required confirmation
- takeover steps without a structured resume condition
- literal defaults on secret variables

## Human takeover/resume contract

Agent 2 checkpoints before each step. `WAITING_FOR_HUMAN` preserves the waiting checkpoint.

On resume:

1. controller ownership must be returned to AGENT by the caller/user flow;
2. `AutomationRunner` forces `IntegrationGateway.refreshObservation()` before continuing;
3. a confirmation wait is approved for that one current step only;
4. an explicit `REQUEST_HUMAN_TAKEOVER` step is marked completed and execution advances;
5. Agent-3 takeover proposals add a following `phone.assert` so the declared resume condition is verified deterministically.

No LLM loop is required while waiting.

## Scheduling

`AutomationRuntime.schedule()` supports one-shot alarms. `registerSchedule()` understands `trigger.parameters["atMillis"]` and repeating `intervalMs` schedules. Enabled schedules are restored when the runtime initializes; disabled schedule alarms are cancelled, and disabled workflows are not rescheduled after an alarm fires.

## Merge guidance

The three branches intentionally overlap in a few composition files. Resolve them by ownership rather than by choosing one branch wholesale:

- `CycloneAccessibilityService.kt`, `PhoneTool*`, `SelectorEngine.kt`, capability/controller primitives: **Agent 1 wins**.
- `automation/*`, `AUTOMATION_PROTOCOL.md`, Automation Studio models/runner/recorder: **Agent 2 wins**.
- Cyclone Core authenticated mobile protocol, Hermes routing/planning/takeover coordination: **Agent 3 wins**.
- `CycloneNotificationListener.kt`: preserve Agent 1 notification retention **and** call `AutomationRuntime.onNotification(...)`.
- `BridgeClient.kt`: preserve Agent 1 typed phone command handling, Agent 2 automation event ingress, and Agent 3 authenticated device/session protocol; this file requires an intentional integration pass.
- `MainActivity.kt`: preserve Agent 2 Automation Studio sections while keeping Agent 1 capability/status surfaces and Agent 3 takeover/Ask Cyclone surfaces.
- `app/build.gradle.kts`: union dependencies/tests rather than taking one side.

## Built vs verified

Built on Agent 2 branch:

- automation/skill schema and JSON persistence
- deterministic runner, retries/recovery, variables, parsing, branches/repeat, skills
- manual/notification/schedule/app-open/remote/WebSocket/calendar-time trigger contracts
- schedule lifecycle
- semantic recorder and edit/delete/reorder controls
- run timeline/checkpoints and human resume
- consequential-action confirmation boundary
- Agent-3 proposal compiler and tests
- Automation Studio UI

Still not physically verified:

- a workflow running on a real Android 14+ device
- recorder events fed by Agent 1's real Accessibility pipeline
- the final Agent-1 gateway adapter after branch integration
- the final Agent-3 mobile proposal transport after branch integration
- process-death/reboot resume on hardware
- long-duration schedule/battery behavior

Do not mark those device gates verified until objective hardware evidence exists.
