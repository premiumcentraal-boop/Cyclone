# Cyclone Mobile Automation Protocol

Branch: `feature/mobile-automation-studio`
Owner: Agent 2 — Automation Builder / Skill Studio

## Boundary

Agent 2 owns workflow definitions, triggers, deterministic execution, recorder state, skills, run history, checkpoints and the Automation Studio UI. It does **not** own Accessibility internals or Hermes planning.

The dependency boundary is `PhoneToolGateway`:

```kotlin
fun interface PhoneToolGateway {
    fun execute(request: PhoneToolRequest): PhoneToolResult
}
```

Agent 1 should implement this gateway using its final `PhoneToolRegistry` / `PhoneToolExecutor`. Agent 2 must not call `AccessibilityService` directly after that adapter is merged. The current `LegacyPhoneToolAdapter` exists only so this branch can integrate against the v0 foundation before Agent 1 lands.

Agent 3 should consume `AutomationDefinition`, `SkillDefinition`, `AutomationEventRouter`, `AutomationStore` and the `IntegrationGateway` contract. LLM-produced workflow text must be decoded/validated into typed definitions before being persisted or enabled.

## Persistent model

Top-level entities implemented:

- `AutomationDefinition`
- `TriggerDefinition`
- `ConditionDefinition`
- `StepDefinition`
- `Selector`
- `VariableDefinition`
- `SecretReference`
- `SkillDefinition`
- `AutomationRun`
- `RunStepRecord`
- `Checkpoint`
- `RecoveryPolicy`

Definitions are persisted through `AutomationStore` using readable JSON. `AutomationCodec` supports round-tripping automations and skills. Runs are capped to the most recent 100 records to keep storage bounded.

Example conceptual JSON:

```json
{
  "id": "invoice-saver",
  "name": "Save invoice attachments",
  "enabled": false,
  "trigger": {
    "type": "NOTIFICATION",
    "parameters": { "package": "com.example.mail", "text": "Invoice" }
  },
  "steps": [
    {
      "id": "open-notification",
      "name": "Open invoice notification",
      "type": "PHONE_TOOL",
      "parameters": { "tool": "phone.open_notification" },
      "confirmationRequired": false,
      "recovery": { "maxRetries": 1, "retryDelayMs": 500, "refreshBeforeRetry": true, "onFailure": "ABORT" }
    }
  ],
  "verification": [],
  "failureBehavior": "ABORT"
}
```

## Trigger types

Built into the typed router:

- `MANUAL`
- `NOTIFICATION`
- `SCHEDULE`
- `APP_OPENED`
- `CYCLONE_REMOTE`
- `WEBSOCKET`
- `CALENDAR_TIME`

Notification events are wired into `CycloneNotificationListener`. Schedule events use `AlarmManager` plus `AutomationAlarmReceiver`; repeating schedules can use `trigger.parameters["intervalMs"]`. App-open and recorder events are exposed as hooks for Agent 1 to call from its final Accessibility event pipeline to avoid both agents editing the same low-level implementation.

Future extension points can add geofence, charging, Bluetooth, NFC, file-change and webhook triggers without changing the workflow runner.

## Step types

Implemented step enum and execution paths:

- `PHONE_TOOL`
- `WAIT`
- `CONDITION`
- `BRANCH`
- `REPEAT`
- `VARIABLE_ASSIGNMENT`
- `PARSE_TEXT`
- `REGEX_EXTRACT`
- `DELAY`
- `ASSERTION`
- `INVOKE_SKILL`
- `HTTP_REQUEST`
- `SEND_CYCLONE_EVENT`
- `REQUEST_HUMAN_TAKEOVER`

`HTTP_REQUEST` and `SEND_CYCLONE_EVENT` are intentionally behind `IntegrationGateway`. The default Agent 2 implementation returns a structured `*_NOT_CONFIGURED` / `AGENT3_CYCLONE_INTEGRATION_REQUIRED` error rather than pretending the integration works.

## Execution and recovery

`AutomationEventRouter` queues matching workflows onto a single background executor. Deterministic workflows do not need AI tokens.

Each run:

1. creates a `RUNNING` record;
2. evaluates automation conditions;
3. saves a checkpoint before each step;
4. executes the step;
5. applies retries and recovery policy;
6. persists the run timeline after each state change;
7. evaluates verification conditions;
8. removes the checkpoint only after success/final failure.

Step states support `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `WAITING`, `WAITING_FOR_HUMAN`, and `SKIPPED`.

Recovery actions support retry, refresh-before-retry, go back, restart app adapter, request AI help, request human, and abort. AI help is an Agent 3 integration event, not an LLM loop inside Agent 2.

## Confirmation safety

`StepDefinition.confirmationRequired` marks consequential actions. The default Agent 2 runtime never auto-confirms such a step. It changes controller ownership to HUMAN, stores a waiting checkpoint and returns `WAITING_FOR_HUMAN`.

This is intended for purchases, messages, deletes, submissions, transfers and other consequential actions. It does not attempt to bypass login, CAPTCHA, 2FA or device security.

## Recorder

`AutomationRecorder` captures semantic workflow steps and deduplicates repeated events. It can record app-open, click, text-entry, scroll, Back and Home through integration hooks. Click/text events accept a full `Selector`, so resource IDs/text/content descriptions/roles can be stored instead of raw coordinates.

The recorder deliberately uses `${input}` as the default text placeholder instead of saving typed credentials or message contents.

Agent 1 integration points:

```kotlin
AutomationRuntime.onAppOpened(context, packageName)
AutomationRuntime.recorder.recordClick(selector)
AutomationRuntime.recorder.recordText(selector)
AutomationRuntime.recorder.recordScroll(direction)
AutomationRuntime.recorder.recordBack()
AutomationRuntime.recorder.recordHome()
```

Agent 1 should call these after it resolves normalized selectors from Accessibility events. Do not make Agent 2 parse `AccessibilityNodeInfo` directly.

## Skills

`SkillDefinition` has typed inputs, outputs and reusable steps. `INVOKE_SKILL`, `BRANCH` and `REPEAT` can call saved skills. Recursion is capped to prevent accidental loops. Skills are available to deterministic automations now and to Hermes through Agent 3 later.

## Automation Studio UI

`MainActivity` exposes lightweight sections rather than a drag/drop editor:

- Ask Cyclone
- Automations
- Skills
- Recorder
- Runs
- Devices
- Permissions
- Cyclone connection
- Settings
- Built vs verified

The Ask Cyclone field stores a pending natural-language request boundary only. Agent 3 is responsible for generating, validating and proposing a typed workflow before activation.

## Integration contract for Agent 1

Replace `LegacyPhoneToolAdapter` with an adapter over Agent 1's final tool executor. Preserve these data contracts:

```kotlin
data class PhoneToolRequest(
    val name: String,
    val arguments: Map<String, String>,
    val selector: Selector?
)

data class PhoneToolResult(
    val success: Boolean,
    val output: Map<String, String>,
    val errorCode: String?,
    val message: String?
)
```

Map Agent 1's stable selectors into `Selector` or introduce a shared selector type during merge. Unsupported capabilities must return structured failure, never `success=true` with missing behavior.

## Integration contract for Agent 3

Agent 3 should:

- list/read automations and skills through `AutomationStore`;
- call `AutomationEventRouter.runManual(id)` for existing automations;
- emit remote/WebSocket events through `AutomationRuntime`;
- compile natural language to a typed `AutomationDefinition` and validate before `saveAutomation`;
- implement `IntegrationGateway.sendCycloneEvent` and approved HTTP integration;
- use `WAITING_FOR_HUMAN` checkpoints for zero-token human waits;
- use `resume(runId)` only after controller ownership returns to AGENT and Agent 1 has refreshed phone observation.

## Built vs verified

### Built on this branch

- typed automation/skill/run/checkpoint schema
- readable JSON persistence/import/export primitives
- deterministic workflow runner
- trigger router
- AlarmManager schedule receiver
- variable assignment, parsing, regex extraction, branch/repeat and assertions
- reusable skills
- retries and configurable recovery actions
- confirmation/takeover checkpoint behavior
- selector-preserving recorder core
- run history persistence
- Automation Studio UI
- notification trigger integration
- harmless generic seeded example
- disabled generic work-shift template replacing hard-coded notification routing
- local recorder unit tests

### Not physically verified

- any workflow on an Android 14+ phone
- recorder events from a real Accessibility event source
- Agent 1's final phone toolbox adapter
- Agent 3's workflow generator/Cyclone event integration
- restart/resume after Android kills the process
- long-running schedule/battery behavior

Do not mark these verified until objective device or CI evidence exists.
