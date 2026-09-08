# Cyclone Mobile 4.2.0 visual source sprint

Base: published **v4.1.0**, exact source **7bcb2efb8c6d5ad3cd9e5148459ddfaa08b3b814**.
Branch: `codex/cyclone-4.1-visual`. Source candidate: **4.2.0 / versionCode 81**.
No release signing or publication is requested by this branch. `publication_authorized=false`.

## Native implementation

- Shared CycloneTheme, blue/cyan colors, typography, spacing, neutral surfaces, glass, status, real PackageManager app icons and task progress components. Legacy theme entry points delegate to the same theme.
- Home / Profiles / AI / Routines / Brain navigation. AI remains the center action. Persistent version labels and duplicate readiness hero removed. Home readiness uses enabled **and bound** PhoneControlReadiness and offers Setup/Repair.
- Home has a real composer, dictation and attachment controls. Pending composer input transfers into existing Ask submission; this is not a second task store.
- Chat retains process-session history and existing durable diagnostics. Natural assistant text, compact model dropdown from the actual registry, intelligence slider, separate persisted autonomy selection, file/photo attachments, microphone and send/stop controls.
- Without explicit whole-display screen-share authority, chat delegates background requests to existing OverlayChromeRuntime orchestration. Missing phone control names setup/repair. The named-VD hot gate remains one; follow-up requests use the existing queue.
- Existing overlay gains the same neutral light/dark surfaces, sliders-first composer and compact intelligence/autonomy controls. Existing drag, 30dp bottom gap, IME/navigation insets, nonmodal bounds and ghost teardown are retained.
- UiTask wraps the original WorkspaceTaskUi, retaining all session/display/workspace/generation fields. View progress uses ViewProgressRouter. TaskConsumerCopy translates executor kind labels for cards and notifications without changing the raw executor message or identity.
- Routines: Apps / Categories / Specifics, grouped/individual switch, search, real icons, app-group detail, canonical run counts and matched active task card. Run is disabled for disabled routines. Creation exposes Describe / Follow Me / Advanced.
- Follow Me: existing runtime recording and analysis; native review invokes the existing compiler, keeps the canonical routine, supports rename/remove-step/save. Saved learning drafts stay disabled until reviewed. Existing legacy report/history/manual teaching remain available through advanced paths.
- Profiles: real registered profiles, armed waiting state, active task, profile detail with matching task/steps. Human opening routes workspace.switch/pause through PhoneToolExecutor, not direct shell or a second mutator. Setup opens the existing managed-profile creation flow.
- Brain: Skills / Apps / Insights using real micro-skill success evidence, confidence, learned apps, paths, run results and learning notes. Run diagnostic detail remains accessible.
- Settings: AI, Phone, Profiles, Connections, Privacy and About drill-down rows; existing API-key/permission/Shizuku/profile/gateway callbacks retained. Storage row describes the existing stores; it does not invent deletion controls.

## Routine metadata migration

AutomationDefinition adds `appPackages`, `categories`, and `associationVersion`. JSON preserves them. Legacy records infer only explicit package keys in APP_OPENED triggers and PHONE_TOOL step parameters; no app-name/free-text guessing. Unknown associations remain Other/Uncategorized. Version 1 preserves explicit empty selections. Saving updates one canonical automation ID. A multi-app routine appears in multiple groups without being copied. Learned timelines can use existing package metadata; unsupported evidence is not guessed.

## Checkpoints (chronological)

1. `2746594` — design tokens and shared surfaces.
2. `7c4c9a2` — navigation, Home and exact task presentation.
3. `fdf448d` — compact Ask, intelligence/autonomy.
4. `979836f` — routine grouping and association storage.
5. `aebd3e9` — Brain and Settings.
6. `3b55f89` — profile detail/human opening and unified theme.
7. `0394649` — Home/Ask background integration and overlay controls.
8. `97554f5` — Follow Me review, editable associations, consumer progress copy.
9. Follow-up commits on this branch contain version, regression coverage and concrete compile fixes.

## Preserved contracts

Foreground = default-foreground/display 0. Named VD = named session/display >0. Layer 2 = workspace ID + generation/display 0, time-sliced under the existing mutation lock. No fourth plane. No SessionContract, Fast Path, Session Kernel, Skill Compiler or GATE core rewrite. PhoneToolExecutor remains authority. Readiness, Android 13+ support, Android 15+ isolated-work requirements, pinned official Shizuku policy, setup Back/cancel and single hot VD remain intact.

## Validation and outstanding visual work

70 local CI-script tests and version/product/security guards pass. Local Gradle cannot fetch its distribution; GitHub Mobile CI handles JVM tests/lint/assembly. Early CI found a missing TextButton import and a theme lookup inside Canvas drawing; both were fixed forward. Consult the latest exact-source branch run for final build status.

The continuation received the actual `Cyclone_Assets_Pack_v1(1).zip` and separate day/night alpine JPEGs. Ask now uses the unmodified photographs, a theme-aware readability scrim and readable message surfaces. The supplied mark and primary navigation geometry are native VectorDrawables. See `MOBILE_VISUAL_4.2_ASSETS.md` for provenance and conversion choices. Reference-board example metrics and activity are not bundled as production data.

Profile live viewing uses existing exact-task View progress; there is no new inline Layer 2 frame source or fabricated preview. No percentage is shown without a real denominator. Profiles without a real task expose app opening and existing setup rather than fake autonomous job controls. Advanced teaching report pages retain their existing detailed layout. Follow Me analysis remains asynchronous and review does not automatically enable a routine.

Physical UI verification still needed: light/dark contrast with final assets; small screens/font scaling/IME; composer drag and teardown; Home → Ask → exact background workspace; Instagram scrolling outside glass; named-VD progress and Take control/Continue; GATE review; profile account isolation/opening; routine migration/editing; Follow Me review.

## Continuation checkpoints — 8 September 2026

Recovered exact head `e326953ccf518a277e209d32bcdd45afb4cdf296`; its background intelligence-preference fix was already present and was not reimplemented.

- `1ab92d8`: corrected the three actual stale assertions in `OverlayProductGuardTest` and `CycloneV39AiChatPageTest`. The failing run did not contain tests named HomeV32VisualTest / AiV32AppTest / UiV32RegressionTest. Exact Mobile CI [34223261752](https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34223261752) passed JVM tests, lint and assembly before source work resumed.
- `2b14cae`: supplied alpine day/night art, exact native Cyclone mark and navigation vectors.
- `ba4cc34`: collapsible task details integrated above Ask's composer, automatic review expansion, accessible Stop. Original WorkspaceTaskUi is passed through; ViewProgressRouter remains the route. No overlay drag/window/teardown changes.
- `3eedf71`: real All/Active profile filters, stable status sorting, fail-closed task identity matching in profile detail, observed handoff result including pause, recoverable errors, disabled-routine detail guard, clear routine search empty state and accessible creation button. Overlay's navigation metadata now agrees with Profiles.
- `9132ce3`: Home composer accessibility, flexible section headings, selected-state semantics for tabs, intelligible compact Settings row, source regression coverage for Home/shared task state and Ask review routing.

## Screen audit and completion boundary

Home, AI/Ask, Routines, Profiles, Brain and Settings retain the existing sprint implementations. Home uses real readiness, routines and WorkspaceTasks; Ask has supplied artwork, compact existing model/intelligence controls, expandable real tasks and one send/stop control. Routines keeps its canonical app/category associations and Follow Me review; Profiles uses real registry/task data and PhoneToolExecutor; Brain's Skills/Apps/Insights still uses stored evidence. Settings retains setup, permissions, API key and gateway actions. No fabricated profiles, metrics or activity were added.

Source gaps identified during this continuation are addressed. Remaining work is visual/device acceptance, not another screen rewrite: actual contrast, fonts/IME/small screens, permission transitions, model/menu positioning, overlay drag/touch/teardown, exact-plane task transitions, GATE, human handoff and profile isolation. Optional utility SVGs/glossy reference PNGs are deliberately not all bundled; familiar existing Android control icons remain. A live inline Layer 2 preview still has no supported frame source, so exact-task View progress remains the truthful affordance.

Final-source Mobile CI must be checked by branch/head; later pushes supersede intermediate runs. Local validation: 70 repository tests and version/product guards pass. Gradle download is unavailable locally; no local JVM pass is claimed. Candidate metadata remains 4.2.0 / 81 with publication_authorized=false. No tag, signing or release workflow was invoked.

**NO DIRECT PHONE / PIXEL / USB / ADB TESTING WAS PERFORMED.**
