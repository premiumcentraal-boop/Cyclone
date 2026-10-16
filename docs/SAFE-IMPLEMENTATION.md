# Cyclone Safe — Alpha 135 implementation checkpoint

**INCOMPLETE — do not merge or publish as a completed Plan 61 implementation.**

Base: `7a266ba8` (Alpha 134 / code 288) from
`claude/cyclone-v5-handoff-review-9qrs40`.
Working branch: `codex/cyclone-safe-alpha135`, in the isolated worktree
`C:/Users/Agent/Cyclone-safe-alpha135`.
Specification: `Cyclone V5 plan/61-cyclone-safe.md`, copied unchanged from
`origin/claude/vigilant-goodall-dy6z4y` (`30c46961`, PR #221).

The development metadata is Alpha 135 / code 289. `publication_authorized=false`.
No merge, push, tag, signing, release, installation, or device action is authorized
by this checkpoint. The owner must explicitly say **merge and publish** before
using the existing release lane, after implementation and validation finish.

## Implemented foundations

- `SafeManifest`: explicit SAFE / SAFE_SEALED / NEVER classifications and reasons
  for preferences, databases, file roots, transient device-protected files and
  Keystore aliases. Longest path match protects the sealed Mind file and excludes
  its transient mission queue from the enclosing Brain directory.
- `SafeSnapshot`: versioned `CYCSAFE1` framing, ZIP entries, authenticated header
  hash, independent random 96-bit nonce per AES-256-GCM chunk, ordered chunk AAD,
  authenticated terminal frame, entry sizes and SHA-256 hashes. Authentication
  finishes before restore writes. Tampering, truncation, extra bytes, duplicate
  entries and traversal paths fail closed.
- `SafeKey`: random 256-bit keys, domain-separated root/hive/Keystore-key wrapping
  primitives, version-bound hive wrapping, 256-bit entropy to 24-word BIP-39
  encoding (word list supplied by caller), and recovery wrapping with scrypt
  N=131072/r=8/p=1. Production-cost KDF is checked against Python/OpenSSL.
- `SafeSealedStores`: in-memory adapters for identity, hive, vault, provider key,
  Mind memory and trust records; private device/signing/bootstrap keys are not
  exported. Restored trust provenance is revoked, requiring fresh owner pairing.
  Corrupt sealed data aborts capture instead of becoming an empty backup.
- `SafeStores`: manifest-driven preferences, files and `VACUUM INTO` database
  capture; restore ordering databases → files → preferences; Android user pinning;
  rejection of unknown destinations, symlinks and live database WAL state.
- `SafeRootStore`: closed, validated command plans using `su --mount-master`,
  quiet key transport outside the bootstrap journal, root-only 0600 key creation,
  process-UID Main-only writes, ciphertext staging/read-back and retention of
  three copies. **Not invoked by any production entry point yet.**
- `SafeFolderStore`: provider-backed ciphertext `.part` write, fsync, rename,
  exact-name check, authenticated read-back and per-user retention of three.
  **No folder picker or persisted URI lifecycle is wired yet.**
- `SafeRestore`: newest-candidate planning, deterministic restore order, complete
  archive validation before writes and rejection of another Android user's Safe.
  **No startup/onboarding integration yet.**
- `SafeDelete`: pure one-shot/expiring confirmation coordinator with ordered
  PC → folder → root → hive → profiles → Main steps; stop on first failure.
  **No owner screen, credential callback, signed phone token issuer or concrete
  deletion actions yet. No user data can be deleted through this coordinator.**
- Gateway `command/safe.py`: standalone ciphertext-only store, transactional
  cross-process writer serialization, three-copy retention, interrupted-part and
  renamed-orphan recovery, integrity checks, pinned P-256 signature verification,
  expiry and durable replay protection for delete tokens. **Not wired to bridge,
  HTTP, MCP, Glass or Command Center routes.** The PC checks framing/digests, not
  GCM authenticity: it has no Safe key.
- Android `hasFragileUserData`, explicit cloud/device-transfer backup exclusions
  for sealed and transient stores, and SafeManifest/owner-delete CI guards.
  Existing permissions, SDKs, package, signer configuration and UID are unchanged.
- Bouncy Castle 1.80 supplies scrypt; one unused duplicate OSGi manifest resource
  is excluded from APK packaging. No runtime network service was added.

## Required before this can be called Cyclone Safe

1. Implement one-per-phone Safe key lifecycle and durable root/local wraps;
   integrate the latest hive key, recovery word-list asset and secure one-time
   owner display. Never generate a new Safe key silently over a recoverable one.
2. Wire initial/background/debounced/nightly-charging scheduling, read-back failure
   retries and Main's per-user capture/restore coordination through the existing
   bootstrap. The raw sealed stores must be captured under their owning locks.
3. Wire owner SAF folder selection, persisted URI handling and per-place status.
4. Implement constrained `safe.put/list/get/delete` bridge transport and PC sync,
   pinned-phone-key lookup, owner-approved returning-phone pairing, Glass status
   and unlocked-hive Safe-key recovery. Do not add a model-accessible delete path.
5. Add the Restore card before onboarding/runtime initialization, recovery-code
   input, new-key re-pairing and existing-user install/bootstrap restore. Preserve
   snapshots for Android users that no longer exist.
6. Add the owner-only Main Settings screen with DELETE + DEVICE_CREDENTIAL and
   explicit destination inventory. Wire all concrete delete steps, PC confirmation
   and the optional unchecked hive-item deletion. Never expose this through
   TaskCommands, Lab, fleet, MCP or agent/gateway mutation routes.
7. Finish startup idempotence/interruption integration and large-library memory
   checks. The current entry adapter retains plaintext entries in app-process
   memory; the format itself encrypts/decrypts in bounded chunks. Large libraries
   still need streaming entry sources to avoid Android heap pressure.
8. Test actual Android adapters and fresh-Keystore restore end to end. JVM re-key
   tests prove the common sealing primitive, not Android's hardware Keystore or
   document-provider behavior. Run full release CI through the normal lane only
   after the owner authorizes publishing and implementation is complete.

## Device status

**Physical verification: UNVERIFIED.** No ADB commands or owner device actions.
Alpha 132 has no Safe. Do not uninstall it. These foundations do not yet make
uninstall safe. The existing device-side update failure has not been diagnosed
or altered, and no new signer, sharedUserId or permission was introduced.

## Local verification at this checkpoint (2026-10-16)

- Android: **2,729 passed**, including **35 Safe tests**, no failures or skips.
- Gateway: **1,190 passed, 18 skipped**; includes **27 Safe tests**.
- Agent MCP: **93 passed**.
- Ports SDK: **92 passed, 2 skipped**.
- Phone MCP: **191 passed**.
- Repository CI unittest discovery: **276 passed**.
- Safe/portable-settings/PC-web pytest guards: **18 passed**.
- `release_versions.py --check`, `mobile_product_guard.py`,
  `repository_security_guard.py`, and `git diff --check`: passed.
- `:app:assembleDebug` and `:app:assembleRelease`: passed. Release output is
  **unsigned**, matching the existing local/CI candidate configuration. No
  signer or signing workflow was changed. Release CI was not invoked.
- APK output metadata agrees on `5.0.0-alpha.135.dev1` / code **289**.
- Manifest permissions compared with the Alpha 134 base: identical;
  `sharedUserId` absent. No device commands were run.

Build outputs (experimental checkpoint, not a completed Safe release):

| Output | Bytes | SHA-256 |
|---|---:|---|
| `apps/mobile/app/build/outputs/apk/debug/app-debug.apk` | 89008618 | `8876d14affe6bbc4ce0fd5e10f17e44da828cdb920ac585fdd91b68c4246ce0f` |
| `apps/mobile/app/build/outputs/apk/release/app-release-unsigned.apk` | 65684943 | `bd0b4d2551dad6521f7d7d2a6f4d9e91e5f5de41d6e637337a9043e8e0247fd2` |

Exact commands (PowerShell, worktree root):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
& "$env:TEMP/cloak-build/gradle-8.11.1/bin/gradle.bat" -p apps/mobile :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain
uv run --with-editable 'apps/device-gateway[test]' --with-editable tools/codex-phone-mcp --with-editable tools/cyclone-ports-sdk --with-editable tools/cyclone-testbench -- python -X utf8 -m pytest apps/device-gateway/tests -q -o addopts=''
uv run --with-editable 'apps/device-gateway[test]' --with-editable tools/cyclone-agent-mcp --with-editable tools/cyclone-ports-sdk -- python -m pytest tools/cyclone-agent-mcp/tests -q
uv run --with-editable 'apps/device-gateway[test]' --with-editable tools/cyclone-ports-sdk -- python -m pytest tools/cyclone-ports-sdk/tests -q
uv run --with-editable 'apps/device-gateway[test]' --with-editable tools/codex-phone-mcp --with-editable tools/cyclone-ports-sdk -- python -m unittest discover -s tools/codex-phone-mcp/tests -v
uv run --with pytest -- python -X utf8 -m unittest discover -s scripts/ci/tests -v
uv run --with pytest -- python -X utf8 -m pytest scripts/ci/tests/test_cyclone_safe_guard.py scripts/ci/tests/test_portable_settings_guard.py scripts/ci/tests/test_pc_web_only.py -q
uv run -- python scripts/ci/release_versions.py --check
uv run -- python scripts/ci/mobile_product_guard.py
uv run -- python scripts/ci/repository_security_guard.py
git diff --check
git diff --cached --check
```

Verification notes: running gateway and agent pytest directories together first
caused their existing duplicate test module names to collide; separate processes
resolved collection. The Windows-default CP1252 gateway run encountered an
existing UTF-8 fixture failure; `-X utf8` passed. The successful gateway suite
still reports an anyio deprecation warning and an existing fake-server thread's
JSONDecodeError warning. The first full Android compilation reported existing
`jvmTarget` deprecation and four nullable-Java test warnings; no Safe-source
compiler warning appeared. Initial APK packaging exposed duplicate OSGi metadata;
the narrow exclusion above fixed it. Git reports the checkout's normal LF/CRLF
conversion notices when staging new files.
