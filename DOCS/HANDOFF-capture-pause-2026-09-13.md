# Recruiser: paused real-time effort and post-processing POC handoff

## Current authority

## Standing whole-build contract — do not rediscover or narrow to Android

Robin reaffirmed that a one-off APK is not the deliverable. The reusable build recipe must enforce the established conventions on every run: tracked repo-root `dist/`; production signing with the existing identity; canonical MAJOR.MINOR.BUILD shared across all platform artifacts; immediate post-success MINOR increment because the built stamp's attestation is consumed; complete artifact sets, conventional names, no silent omissions or overwrites; and verification inside the recipe. Windows-host detection must route MSI/MSIX packaging to the proper native Windows tools, with automatic EXE cross-compilation from the supported build environments. Desktop is part of the reconstruction product, not an optional viewer afterthought. These requirements remain in force even when quota runs out. Do not substitute a new scheme, debug builds or a phone-only declaration of completion.

REL2 adopted the actual `Admin-Manual/versioning/update-version.sh` template (Recruiser identity and existing-consumer mirrors), its documented Kintsugi-Tauri2 post-build guard, and the existing Kintsugi CC12 naming/assertion and no-overwrite staging contract. The known native Gradle build remains the platform-specific build command; unrelated Tauri/billing logic was not copied. `version.txt` is canonical, `version.json` its runtime mirror; Gradle and web buildInfo share these values. `android/version.properties`, package.json and package-lock.json are stamped mirrors, not independent counters. `release.lock` freezes a multi-platform set; while present, individual legs visibly defer the success-tail bump to the matrix owner. The local ignored signing configuration is set, so the repeatable command on this host is `bash scripts/build-android.sh --console=plain` with no per-run credential reconstruction.

Repeated execution evidence: the final canonical recipe built **0.11.22422**, advanced the tree immediately to **0.12.22422**, then built **0.13.22422** and advanced immediately to **0.14.22422**. Both runs passed embedded signer, manifest/application/version, strict keystore-backed AAB verification, ZIP integrity, packaged ELF alignment, native-symbol presence, checksum and CC12 naming gates. The Android APK/AAB/symbols/checksums for both runs are retained in tracked dist/android/. Early REL2 trials 0.9.19619 and 0.10.19620 are historical pre-canonical-stamper evidence, not the recipe to resume. Passing cached core tests and executed release app tests are distinguished in Gradle output. This proves repeat execution on this host, not bit-identical outputs with intentionally different version stamps, Windows packaging or a clean-host build.

Remaining full-pipeline gap: no `scripts/build-windows.sh` or `src-tauri/tauri.conf.json` exists in this checkout (CodeGraph query plus exact-path checks). Native Windows MSI/MSIX routing, EXE cross-compilation, desktop reconstruction integration, complete cross-platform orchestration and protection of tracked release artifacts from the existing web bundler's root-dist clearing are **not implemented/verified by REL2**. Do not mark the whole build contract complete. Reuse the established desktop recipes when forming bounded GLM coder packets; supply exact entity paths, toolchain/signing inputs, commands and acceptance, rather than sending coders to re-investigate conventions. Real-time/UI pause remains separate and unchanged.

## Earlier signing correction and retained evidence

**Signing correction — v0.8.19618:** Robin's standing convention is production-signed release artifacts even for POC/MVP builds. The earlier debug APKs below are rejected historical evidence, not acceptable deliverables or recipes. `scripts/build-android.sh` now requires signing credentials, uses only release tasks, produces APK/AAB/native symbols, verifies APK signature and 16 KB zip alignment, and stages stamped tracked artifacts. Gradle disables debug variants entirely. No new signing identity was generated: the Admin-Manual PlayStore production keystore was byte-identical to the existing HelloWord keystore. Passwords remain external environment values (or ignored `.env.android-signing`); the committed example contains no passwords.

REL1 evidence: `bash scripts/build-android.sh --console=plain` with the four documented signing environment variables produced BUILD SUCCESSFUL (74 tasks, 70 executed). Nine release app tests executed with zero failures; 23 core tests were up-to-date with zero failures. `apkanalyzer manifest debuggable` returned false; `apksigner verify` passed with certificate SHA-256 `56c13674ef22df95deb1e5c468820e8cfa3ea2f522511749ab7b6e5bde3bd943`; release ELF LOAD alignments are all 0x4000; APK zip alignment and all artifact SHA-256 checks passed. Gradle task inventory contains no assembleDebug, bundleDebug or testDebugUnitTest. `jarsigner -verify` reported `jar verified` for the AAB, with self-signed-chain, no-timestamp and JarInputStream ordering warnings; no Play upload or store acceptance is claimed.

Corrected artifacts (all v0.8.19618, under tracked `dist/android/`): `recruiser-v0.8.19618-android-arm64-release.apk`, `recruiser-v0.8.19618-android-arm64-release.aab`, `recruiser-v0.8.19618-android-arm64-native-symbols.zip`, `recruiser-v0.8.19618-android-arm64-SHA256SUMS.txt`. These are production-signed POC artifacts, not a declaration of public-release readiness. Existing debug artifacts are excluded from version control and must not be offered as downloads.

**Device migration pending:** ADB install with `-r` returned INSTALL_FAILED_UPDATE_INCOMPATIBLE because the installed debug app has a different signer. Nothing was uninstalled and app data was not cleared. Before replacing that installation, preserve/export and validate all real capture packages; obtain authorization for the uninstall/reinstall migration and provide a tested restore path. Do not bypass this with another debug-signed build or silently change the application ID. Phone reconstruction/save/reopen acceptance remains pending. The real-time/UI pause below is unchanged.

Robin: "we need to pause the realtime capture effort and finish a working poc/mvp with post-processing as it was before"; then "make sure you annotate the effort where we left off and check pieces" (spelling normalized). This overrides prior authorization to continue the real-time/UI swarm. Preserve the work; do not resume it automatically or spend quota on Air, audio, new screens or live coverage.

## Exact stopped frontier

- Frozen architecture/checklist commit: `25661575de1cb0bdf96bc69c37525559f700627c`, pushed to the existing origin. Native/web baseline implementation is in `f2a0e7d706d80f8b4ef3f2f90e97117146eee8d8` with later documented verification. No history was reverted.
- `/root/capture_contract`: interrupted at COV2, native renderer/controller/recorder integration. No new source changes present at pause.
- `/root/reconstruction`: interrupted at COV1, core coverage/guidance/writer/coordinator implementation. No new source changes present at pause.
- `/root/web_capture`: interrupted while inspecting/cleaning UI1 generated exports, before UI2 wiring. No completed validator, provenance manifest, runtime bridge or UI implementation. A suggested DOM-test dependency addition was stopped; do not install it as an implicit continuation.
- UI1, UI2, COV1 and COV2 remain incomplete. Their unchecked packets are historical pending contracts, not active assignments.
- Preserved architecture: `DOCS/ARCHITECTURE/capture-coverage.md`, `DOCS/ARCHITECTURE/stitch-capture-ui.md`, `LIBS/UI/STITCH/capture/DESIGN.md` and CHECKLIST packets. The existing native Activity still uses platform widgets; no claim that Stitch screens are wired.

## Design decisions to retain when explicitly resumed

Native spatial shading, not small running words: green only for durably saved useful overlap, blue for weaker observation, red localized unusable retained imagery, unknown unshaded. One adaptive panorama-like aiming target should positively guide without rejecting off-route capture or claiming the room is complete. Same-session pause alignment is proposed; the current baseline destroys the ARCore session on pause and separates world frames. Never silently merge independent worlds.

The future UI uses actual cleaned Stitch components over native camera/rendering surfaces; capture/tracking/geometry do not pass through JavaScript. Library selection, tags, named vignettes and recoverable trash are specified but not implemented by UI2. Do not promise a clear-captures or tagging feature based on that document alone.

No invented sensor rates, progress, saved counts, successful states or geometry. No LiDAR claim. Device capabilities and unavailable data must be described truthfully. Fake values belong only in isolated tests, never application defaults.

## Stitch provenance and unaccepted drafts

Private project `11810021676468696766`; design system `17924095674577582477`. The five downloaded files remain local, untracked under `LIBS/UI/STITCH/capture/exports/`; they are NOT bundled, approved or included in this handoff commit. Source IDs and SHA-256 values are frozen in UI1. They can be retrieved again from the private project; do not commit or copy raw rejected files into application assets merely to preserve them.

| Screen | Source ID |
| --- | --- |
| Library | c676894b5b8742039cb597a9222cfed4 |
| Viewfinder | 935017bce4b74071ab7fed6bd8cdea90 |
| Review | edf3a42853ff474dac339744c46b0d4a |
| Processing | c2dd069caf7147c9b8e75a1a8383167c |
| Revisit | 4bf3d0e5e9f448b9b0bf43c6b559cfe1 |

Rejected content includes simulated frame timing/telemetry scripts, fictional room tags, fake success/progress and verified/direct-depth claims, LiDAR copy and decorative fake geometry. Resume only after renewed authorization: finish UI1 cleaning and automated validation plus actual visual review, commit/push; implement COV1 then COV2 in dependency order; wire UI2 against verified native interfaces. Reconcile exact dependencies against the frozen checklist before dispatch. Do not regenerate designs just because the local draft files are absent.

## Verified post-processing baseline and honest limits

- This session: all 37 web tests passed (`node --import tsx --test tests/recruiser/*.test.ts`); `npx tsc --noEmit --incremental false` passed; `npm run build` passed. Production build has a large-chunk warning, not a compilation failure.
- Earlier native evidence: 23 core and 9 app JVM tests passed. Baseline 0.6.19616 APK SHA-256: `88663a91a5afb6db3ed16f9b5a99e80ae5e6089ed9d1fb3a49b4f1a30bc42488`. It was reinstalled successfully on the connected Fold5; existing app data was retained.
- Native post-processing uses retained metric depth, intrinsics and camera poses, with Vulkan depth unprojection and CPU voxel fusion, to produce a coloured PLY. Vulkan does not supply tracking or an arbitrary-image reconstruction algorithm. The portable web import/service client is not proof that an external reconstruction worker is running.
- Two real finalized sessions remain on the phone. One has no retained depth; the newer has depth/confidence and two independent world frames. Neither had a reconstruction at inspection. These recordings predate the 0.6 calibration fix: they can exercise the pipeline but do not establish reconstruction accuracy. Do not commit private captures/journals/screenshots or manufacture replacement evidence.
- The phone changed to another app during verification, so UI automation stopped. No saved-capture Build 3D/reopen success is claimed in this handoff yet. Native camera guidance polish, full room completeness, thermal endurance and arbitrary photo/server reconstruction remain unverified.
- `npm run build` clears root `dist/`, including staged Android APKs. The 0.6 APK was restored from the identical Android build output and its hash rechecked. Run web build before staging native artifacts; this is an artifact-order caveat, not loss of source or phone captures.

## POC packaging amendment

Observed on the Fold5 after launching 0.6: Android's compatibility dialog reports the native depth library's LOAD segments are not 16 KB aligned. `readelf` confirmed alignment 0x1000 in `librecruiser_depth.so`; ARCore JNI LOAD segments were already 0x4000. Only OK was tapped, not "Don't Show Again". Do not suppress the diagnostic.

| Entity | Exact target | Contract |
| --- | --- | --- |
| recruiser_depth link options | android/app/src/main/cpp/CMakeLists.txt:30 (after target creation) | Under `if(ANDROID)`, add `target_link_options(recruiser_depth PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")`; keep host parity builds, algorithms and dependencies unchanged. |
| Versioned Android build | scripts/build-android.sh:1 (existing, read-only) | Increment canonical version via existing helper, run core/app tests and package, then stage APK after web build. |

This is a bounded packaging correction for the existing POC, not resumed real-time development. Android's [16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes) specifies the two linker options for NDK r27 and lower. CHECKLIST POC1 is the executable packet.

POC1 software evidence: versioned helper produced **0.7.19617**, BUILD SUCCESSFUL (51 tasks: 20 executed, 31 up-to-date; core tests up-to-date, app tests executed). Native depth LOAD alignment is now 0x4000 for all three segments. Both bundled ARCore libraries also have 0x4000 LOAD alignment. APK zipalign `-c -P 16 -v 4` exited 0 with `Verification succesful`. Artifact: `dist/android/recruiser-capture-0.7.19617-arm64-debug.apk`, SHA-256 `9c947f99b40d63a6ac969331be1d7aa58cbbdbd5bedbb8e719661f55524af345`. ADB `install -r` returned Success without clearing app data. The app was not foregrounded after this update because the phone was in use; warning disappearance and Build 3D/save/reopen remain pending operator observations. Pause handoff commit `7ab8e7c` was pushed before this repair.

Operator verification protocol after the software gates: install the new development APK without clearing app data; launch when the phone is available and observe whether the compatibility warning is gone. Open a saved session with retained depth, choose one independent world, run Build 3D, observe actual output/error, close and reopen the saved PLY. Preserve source observations. If exercising pre-calibration-fix recordings, report functionality only, not geometric accuracy. A fresh stable-depth recording, sustained thermal check and interrupted processing/resume remain separate device evidence.

## Pieces check

Pieces OS responded at localhost port 39300, version 12.6.2. The structured summary search for `recruiser` with `created.from=2026-09-13T04:00:00Z` (Toronto midnight) returned zero results; the same bounded workstream-event search also returned zero. Direct read-only examination of the local Couchbase SQLite annotation text index for case-insensitive `%recruis%` returned no rows. The vector database contains vector metadata, not readable summary narratives. These empty results are inconclusive and are NOT evidence that the conversation never happened. No Pieces records were overwritten or invented; this repository handoff and the explicit user conversation preserve the verified frontier.
