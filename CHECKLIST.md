# Recruiser — Execution Checklist

**Scope:** capture/import → post-process → save/revisit MVP, including desktop reconstruction.
**Markers:** `[ ]` pending · `[X]` implemented, verification outstanding · `✅` verified.
**Dispatch:** one ready task per coder. Architecture decisions are resolved before dispatch; specifications are references, not extra work to infer.

## Phase 1: Release Build Foundation

### 1.1 Android build

- ✅ **REL1 — Produce production-signed APK, AAB and native symbols** — @build
  - Files: `scripts/build-android.sh`, `android/app/build.gradle.kts`
  - Existing production key; debug variants disabled; signature and manifest checks passed.

- ✅ **POC1 — Enforce 16 KB native alignment** — @build
  - File: `android/app/src/main/cpp/CMakeLists.txt`
  - Verified: packaged ELF and APK ZIP alignment. Earlier debug deliverables are superseded.

- ✅ **REL2-A — Reuse canonical versioning with immediate post-success increment** — @build
  - Files: `scripts/update-version.sh`, `version.txt`, `version.json`
  - Shared Android/web version readers; `release.lock` coordinates platform sets.
  - Verified: built `0.11.22422` → tree `0.12.22422`; built `0.13.22422` → tree `0.14.22422`.

- ✅ **REL2-B — Gate and retain complete release artifact sets** — @build
  - Files: `scripts/build-android.sh`, `scripts/lib/cc12-artifact-name.sh`, `.gitignore`
  - Command: `bash scripts/build-android.sh --console=plain`
  - Verified twice: signing, app/version, non-debuggable manifest, AAB, archives, native alignment, symbols, hashes and names.
  - Complete sets tracked in `dist/android/`; existing artifacts are not overwritten.

- [ ] **REL2-C — Specify build-failure regression tests** — @architect
  - File: `CHECKLIST.md`; target behavior: `scripts/build-android.sh`
  - Cases: missing credentials, wrong signer, corrupt output, missing symbols and an existing destination.
  - Acceptance: ready test packet with exact files/fixtures and failure assertions; no successful or partial delivery on failure.

### 1.2 Whole-platform build

- [ ] **BUILD1 — Prepare desktop build and reconstruction packets** — @architect
  - File: `CHECKLIST.md`
  - Reference: established `Admin-Manual/DOCS/CICD_CONVENTIONS.md` recipes.
  - Include Windows-host MSI/MSIX detection, EXE cross-compilation, desktop reconstruction, shared version, signing and tracked `dist/`.
  - Acceptance: exact platform files/commands frozen before coder dispatch. Desktop wiring is not implemented yet.

- [ ] **BUILD2 — Freeze web output handling that preserves release artifacts** — @architect
  - Current issue: web builds clear root `dist/`, including staged Android files.
  - Acceptance: implementation packet preserving release bytes and existing web hosting behavior.

## Phase 2: Post-Processing MVP

### 2.1 Existing implementation

- [X] **N1 — Capture, save, replay and export native observations** — @android
  - Files: `android/app/src/main/java/mba/robin/recruiser/capture/`
  - Implemented; full phone lifecycle acceptance remains outstanding.

- ✅ **N2 — Implement bounded depth fusion and checkpoints** — @reconstruction
  - Files: `android/core/`; `android/app/src/main/java/mba/robin/recruiser/capture/CaptureReconstruction.kt`
  - Verified: 23 core tests. Room reconstruction quality remains a device check.

- ✅ **N3 — Preserve and review observation packages in the web app** — @web
  - Files: `lib/recruiser/`, `components/recruiser/`, `tests/recruiser/`
  - Verified: 37 web tests and TypeScript check. External reconstruction service is not thereby verified.

### 2.2 Device and desktop acceptance

- [ ] **MVP1 — Preserve captures and migrate the phone to production signing** — @operator
  - Validate original-package backups and a restore path before authorizing uninstall/reinstall.
  - Acceptance: production-signed app with all original captures usable.
  - Current update is blocked by the old debug signer. No data was cleared.

- [ ] **MVP2 — Verify phone processing, save and reopen** — @operator
  - Use real retained depth and one independent world frame.
  - Acceptance: actual saved PLY reopens; original observations remain intact.
  - Fresh capture required for geometry-quality claims; old recordings predate the calibration fix.

- [ ] **MVP3 — Verify desktop processing, save and reopen** — @operator
  - Dependency: desktop implementation from BUILD1.
  - Acceptance: real imported observations produce a saved scene that reopens on desktop.

## Phase 3: Real-Time Capture — Paused

Robin's explicit instruction is required to resume this phase.

- [ ] **UI1 — Clean and verify actual Stitch exports** — @ui — **PAUSED**
  - Files: `LIBS/UI/STITCH/capture/`
  - Acceptance: no invented data, false capability claims or fake success/geometry is bundled.

- [ ] **UI2 — Wire Stitch screens and capture organization** — @ui — **PAUSED**
  - Architecture: `DOCS/ARCHITECTURE/stitch-capture-ui.md`
  - Includes selection, tags, vignettes and recoverable removal; not implemented yet.

- [ ] **COV1 — Implement retained coverage and adaptive guidance** — @reconstruction — **PAUSED**
  - Architecture: `DOCS/ARCHITECTURE/capture-coverage.md`
  - Acceptance: specification-defined state, durability, parallax and retargeting tests pass.

- [ ] **COV2 — Render native coverage and alignment guidance** — @android — **PAUSED**
  - Architecture: `DOCS/ARCHITECTURE/capture-coverage.md`
  - Acceptance: native implementation passes its contract tests; visible shading/alignment has separate device evidence.

## Deferred

- [ ] **Integrate Insta360 Air access** — @capture — **DEFERRED**
- [ ] **Implement spatial audio** — @audio — **DEFERRED**
- [ ] **Reconcile remaining thumbnail/audio proposals** — @architect — **DEFERRED**

## References

- Handoff/evidence: `DOCS/HANDOFF-capture-pause-2026-09-13.md`
- Original detailed packets and history: `DOCS/CHECKLIST-HISTORY-2026-09-13.md` — reference only, not the executor queue.
