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

The four supplied image boards were viewed as references. The Windows-only path `C:\Users\Agent\Downloads\Cyclone\_Assets\_Pack\_v1` is not mounted in this workspace. The **actual logo/icon pack and separate day/night alpine photographs were not uploaded**, so those assets are not bundled. Existing native Cyclone mark and Android icons remain; chat uses a readable theme background. This is a source implementation checkpoint, not a claim of completed visual parity. Upload the pack as a ZIP to finish exact assets.

Profile live viewing uses existing exact-task View progress; there is no new inline Layer 2 frame source or fabricated preview. No percentage is shown without a real denominator. Profiles without a real task expose app opening and existing setup rather than fake autonomous job controls. Advanced teaching report pages retain their existing detailed layout. Follow Me analysis remains asynchronous and review does not automatically enable a routine.

Physical UI verification still needed: light/dark contrast with final assets; small screens/font scaling/IME; composer drag and teardown; Home → Ask → exact background workspace; Instagram scrolling outside glass; named-VD progress and Take control/Continue; GATE review; profile account isolation/opening; routine migration/editing; Follow Me review.

**NO DIRECT PHONE / PIXEL / USB / ADB TESTING WAS PERFORMED.**
