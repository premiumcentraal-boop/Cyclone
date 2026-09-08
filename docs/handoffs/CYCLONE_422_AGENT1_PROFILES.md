CYCLONE 4.2.2 — AGENT 1 PROFILE HANDOFF

BRANCH:
agent/422-profile-provisioning-repair

FINAL SHA:
328738d412db429781e121b7786df8841213b094 (implementation commit; this handoff is committed immediately after it)

BASE SHA:
7d263f4a49ea08e6903427653e90723cd818d7e9

MISSION RESULT: PARTIAL

ROOT CAUSE FOUND:
The confirmed 4.2.1 product defect is the provisioning error contract, not evidence that Shizuku was missing. ProfileSetupRuntime used one generic root helper where practically any non-zero exit, Error line, or Exception became “Android couldn’t complete this step.” That erased the platform distinction between root denial, managed-profile limits, unsupported profile topology, creation rejection, start/unlock failure, package-install failure, verification failure, storage failure, and OEM/policy restrictions. The exact platform reason on the user’s failing phone is still unknown because that phone is not connected to this agent. 4.2.2 therefore preserves and classifies the bounded Android result instead of guessing the OEM failure.

CURRENT PRIVILEGE MODEL:
Profile provisioning remains a local, user-initiated managed-profile operation. Root is the deterministic provisioning authority. Every privileged operation is represented by a closed ProfileSetupCommand created by fixed internal factories; there is no generic root/shell API and no model/Gateway/MCP exposure. The create operation is executed once through the chosen authority and is never replayed through a second privilege path.

ROOT BEHAVIOR:
Before profile creation Cyclone now executes a real uid-0 command through the same su -c path used for provisioning, rather than treating the presence of su as sufficient. Root-unavailable, root-denied, and root-command-failed states remain distinct. Once the typed probe succeeds, the existing RootProbe is refreshed so Layer2 cross-profile verification keeps the same root truth. Privileged output is bounded and only a short sanitized platform message is retained with the typed operation/exit/classification/retry metadata.

SHIZUKU BEHAVIOR:
Shizuku authorization is read separately from root and is not reported as Profile B readiness. AOSP shell identity can have user-management privileges on supported Android builds, but this lane does not wire a generic Shizuku shell executor or claim that authorization alone proves profile creation capability. shizukuProfileProbeVerified therefore remains false in 4.2.2 and root remains the only selected provisioning authority. A future Shizuku fallback must use a fixed typed broker and its own successful capability probe before it can become an authority.

PROFILE TYPE USED:
Android managed profile under the current Profile A user, unchanged from 4.2.1. This still provides separate per-profile application data and separate sign-ins while preserving the existing Layer2 Android-user-id targeting model. The repair does not substitute a full secondary user merely to make creation return success.

CAPABILITY PREFLIGHT:
The runtime now verifies the actual root execution path; derives the Android user running Cyclone; asks Android for the current user; parses cmd user list --all --verbose into typed user/profile records including managed type, parentId, running and partial state; reads pm get-max-users where available; checks FEATURE_MANAGED_USERS, DISALLOW_ADD_MANAGED_PROFILE and low-RAM state; rejects nested-profile parents and current-user mismatches; reconciles Cyclone’s durable profile name/user journal with Android’s live users; distinguishes an existing exact Cyclone-owned Profile B from ambiguous/mismatched users; and verifies that all selected Profile A apps still exist. Android’s managed-profile creation logic can permit profiles in cases where the generic maximum-user count is otherwise reached, so pm get-max-users is recorded as capability evidence but is not used as an unsafe standalone pre-rejection. The typed create-user result remains authoritative for max-user/max-profile rejection.

ERROR CLASSIFICATION:
Typed ProfileSetupFailureKind covers ROOT_UNAVAILABLE, ROOT_DENIED, ROOT_COMMAND_FAILED, MANAGED_PROFILE_UNSUPPORTED, MAX_USERS_REACHED, MAX_PROFILES_REACHED, PROFILE_CREATION_REJECTED, PROFILE_ALREADY_EXISTS, PROFILE_START_FAILED, PROFILE_NOT_UNLOCKED, PACKAGE_INSTALL_FAILED, PROFILE_VERIFICATION_FAILED, STORAGE_FAILURE, OEM_RESTRICTION, and UNKNOWN_PLATFORM_FAILURE. Each failure has a short headline, plain-English reason, one action, retry usefulness, and a bounded sanitized platform message. CycloneProfilesPage renders the typed headline/reason/action and does not route root-only failures to Shizuku instructions.

RETRY / RECOVERY DESIGN:
Cyclone journals the generated Profile B name, parent user, selected packages, exact created Android user id and setup stage. The Android user id is committed immediately after create-user returns an id, before start/install/setup work. Every retry first re-lists Android users and must recover the one exact Cyclone-owned managed profile with the expected name, parent and id. A journal mismatch or ambiguous duplicate fails closed. Existing verified app installations are preserved and install-existing is skipped for those apps; missing apps are resumed individually. Cancellation is checked at bounded operation boundaries and leaves the journal intact. A create result that races with an already-existing exact Cyclone profile is re-listed and resumed rather than creating another identity.

PROFILE A SAFETY:
No remove-user, delete-user, clear-data, account-copy, or destructive cleanup operation exists in the typed profile command plan. Profile A can be a create parent but is rejected anywhere an operation requires a Profile B user id. A saved profile belonging to another parent fails closed. The runtime never deletes unrelated Android users to make room and never resets Profile A.

FILES CHANGED:
apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileProvisioningContract.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileSetupPlan.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileSetupRuntime.kt
apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneProfilesPage.kt
apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/workspaces/ProfileSetupPlanTest.kt
apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/CycloneProfileProvisioningUiTest.kt
docs/handoffs/CYCLONE_422_AGENT1_PROFILES.md

TESTS RUN:
Requested local commands were attempted to be sourced through the repository environment, but this ChatGPT execution environment has no checked-out Cyclone repository/Android SDK and outbound git/network resolution is unavailable. The connected GitHub branch is also not in mobile-ci.yml’s push allowlist, there is no PR by instruction, and the available GitHub connector does not expose workflow_dispatch. Therefore these required commands were NOT executed and are not claimed green:
cd apps/mobile && ./gradlew :app:testDebugUnitTest
cd apps/mobile && ./gradlew :app:compileDebugKotlin
cd apps/mobile && ./gradlew :app:lintDebug
git diff --check
Profile-specific tests were added for all required deterministic cases but could not be executed through Gradle here. Source/branch audit confirmed the branch is exactly one implementation commit ahead of the preserved base before this handoff, and only owned runtime/profile UI/profile test paths were changed by the implementation commit.

TEST RESULTS:
UNVERIFIED BY GRADLE IN THIS ENVIRONMENT. No failing result is being hidden; no Mobile CI run exists for implementation SHA 328738d412db429781e121b7786df8841213b094. Required coverage is present in ProfileSetupPlanTest and CycloneProfileProvisioningUiTest for: create-user nonzero after root success; preserved Android reason; max profile/user mapping; root denial; exact-profile resume; journal mismatch; re-list verification; install resume; selected-app disappearance; cancellation recovery; Profile A destructive-safety; Shizuku-not-readiness; typed command rejection; and full ready verification. Integration must run the required Gradle/lint/diff checks before merge.

PHYSICAL DEVICE STATUS:
PHYSICAL PROFILE CREATION: UNVERIFIED. No failing phone, Pixel, USB, or ADB device is connected to this agent. Unit/source logic must not be treated as an OEM/Pixel acceptance claim.

KNOWN OEM LIMITATIONS:
OEMs and device-policy configurations can disable managed users, restrict DISALLOW_ADD_MANAGED_PROFILE, limit the number/type of profiles, report low-RAM restrictions, or return vendor-specific create/start failures. Android itself computes managed-profile capacity using user-type/profile rules that are not equivalent to a simple pm get-max-users comparison. Unknown vendor text therefore falls back to a typed creation/platform failure instead of claiming a false limit. Profile B must also become RUNNING_UNLOCKED and remain visible/targetable to Layer2 before readiness can be proven.

INTEGRATION NOTES:
Preserved base is exactly release/cyclone-mobile-v4.2.1 @ 7d263f4a49ea08e6903427653e90723cd818d7e9. The implementation commit is 328738d412db429781e121b7786df8841213b094 and has that SHA as its sole parent. No versionName/versionCode, signing, key, release workflow, Device Gateway, MCP, PC Companion, overlay, Ask/composer, or AI routing files were modified. Do not merge until the required Gradle test/compile/lint commands and git diff --check pass on the branch. After those checks, physical acceptance should reproduce the original user journey and capture the typed failure if the phone still rejects creation.

READY_FOR_422_INTEGRATION: NO
