# Cyclone V2.7 — Adaptive Brain implementation plan

V2.7 upgrades V2.6 in five targeted areas without replacing the existing Agent 1/2/3 layers.

## 1. Adaptive micro-skill memory
- record every successful and failed `phone.*` action as evidence, not only the final task sequence
- preserve safe selectors, packages, screen fingerprints, before/after state, confidence and success/failure counts
- maintain a local launcher app inventory so Cyclone knows which apps exist and how they are opened
- retrieve relevant micro-skills before the first model decision
- execute only high-confidence deterministic shortcuts automatically; otherwise expose recalled knowledge to the model
- run an asynchronous Brain consolidation pass after each task
- mirror micro-skills, apps, notes and learned patterns into `Cyclone Brain/`

## 2. Brain chat
- add a dedicated Brain Chat mode inside the AI tab
- users can ask questions about local learned knowledge or explicitly add notes/knowledge
- local retrieval works without an AI model; OpenRouter can synthesize an answer from the retrieved local context when configured
- executable confidence remains evidence-based; an AI reflection may annotate knowledge but cannot silently promote unsafe executable actions

## 3. Cleaner AI history
- add status filters and concise run cards
- separate run summary, what worked, what failed and the technical timeline
- collapse noisy model/observe events by default while retaining a developer detail toggle

## 4. Task-scoped overlay
- show the decision overlay only while a task is running
- on completion/failure show a clear final state briefly
- fade/slide the overlay away automatically
- preserve the user toggle so the overlay can automatically reappear for the next task

## 5. Follow Me learning
- add a special `Follow Me` learning mode next to the existing single-app learner
- Cyclone never controls the phone in this mode; the user remains HUMAN controller
- observe user clicks, window changes and navigation across apps in the background
- continue/extend each app's semantic App Learner graph
- record cross-app/app-launch behavior into Adaptive Brain micro-skills
- keep credentials, typed sensitive values, OTPs and payment data out of the Brain mirror

## Acceptance targets
1. A successful `go home` action creates/updates a `go_home` micro-skill.
2. Opening an installed app creates/updates an `open_app:<package>` micro-skill and app inventory entry.
3. Repeating a previously successful simple task causes Brain recall before model decision 1.
4. Two or more verified high-confidence executions may run as a deterministic shortcut without model rediscovery.
5. A failed action lowers only the relevant micro-skill confidence rather than destroying unrelated successful knowledge.
6. Brain Chat can add and retrieve a user note.
7. AI history clearly separates summary, success/failure evidence and technical trace.
8. Overlay announces task completion/failure and removes itself automatically.
9. Follow Me can observe navigation across at least two apps without autonomous clicks.
10. CI and APK build evidence remain separate from physical-device verification.