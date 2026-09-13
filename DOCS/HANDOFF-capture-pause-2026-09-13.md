# Recruiser: paused real-time effort and post-processing POC handoff

## Current authority

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

Operator verification protocol after the software gates: install the new development APK without clearing app data; launch when the phone is available and observe whether the compatibility warning is gone. Open a saved session with retained depth, choose one independent world, run Build 3D, observe actual output/error, close and reopen the saved PLY. Preserve source observations. If exercising pre-calibration-fix recordings, report functionality only, not geometric accuracy. A fresh stable-depth recording, sustained thermal check and interrupted processing/resume remain separate device evidence.

## Pieces check

Pieces OS responded at localhost port 39300, version 12.6.2. The structured summary search for `recruiser` with `created.from=2026-09-13T04:00:00Z` (Toronto midnight) returned zero results; the same bounded workstream-event search also returned zero. Direct read-only examination of the local Couchbase SQLite annotation text index for case-insensitive `%recruis%` returned no rows. The vector database contains vector metadata, not readable summary narratives. These empty results are inconclusive and are NOT evidence that the conversation never happened. No Pieces records were overwritten or invented; this repository handoff and the explicit user conversation preserve the verified frontier.
