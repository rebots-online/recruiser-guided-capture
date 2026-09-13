# Stitch capture UI and native application wiring

Authority: Robin requested the actual Google Stitch components, matched to recruiser.html, with responsive native capture and adaptive visual guidance. The user delegated native-versus-web presentation decisions. This document fixes the interface boundary; CHECKLIST packets are the implementation contract. Audio and Insta360 Air device access are excluded from this phone-only cohort.

## Source components and integration policy

Private Stitch project: `projects/11810021676468696766`; design system `assets/17924095674577582477`. Canonical visual contract: `LIBS/UI/STITCH/capture/DESIGN.md`. Existing standalone recruiser.html is read-only reference. No new product identity or stack migration.

| Screen | Stitch source ID | Canonical export |
| --- | --- | --- |
| Library | c676894b5b8742039cb597a9222cfed4 | LIBS/UI/STITCH/capture/exports/library.html |
| Capture | 935017bce4b74071ab7fed6bd8cdea90 | LIBS/UI/STITCH/capture/exports/viewfinder.html |
| Review | edf3a42853ff474dac339744c46b0d4a | LIBS/UI/STITCH/capture/exports/review.html |
| Processing | c2dd069caf7147c9b8e75a1a8383167c | LIBS/UI/STITCH/capture/exports/processing.html |
| Revisit | 4bf3d0e5e9f448b9b0bf43c6b559cfe1 | LIBS/UI/STITCH/capture/exports/revisit.html |

Stitch text describing its output is not verification. Downloaded review/processing files still include old simulated telemetry scripts. Before bundling, remove those scripts and inline handlers, fictional data/claims, nominal-state controls and decorative fake geometry; retain the generated structural markup, class system, SVG controls and layout. Record source SHA-256, integrated SHA-256 and the exact cleaning changes in provenance.json. Integrated exports contain semantic data bindings and only the app-owned runtime.js. Raw rejected drafts are not application assets.

## Entity table

All new source files start at line 1. Native package mba.robin.recruiser.capture. New UI data is JSON using the existing org.json dependency; all nullable measurements stay null and nanosecond values stay strings.

| Entity | Exact target | Responsibility and signature |
| --- | --- | --- |
| CaptureUiHost | android/app/src/main/java/mba/robin/recruiser/capture/CaptureUiHost.kt:1 | `class CaptureUiHost(activity:Activity,onAction:(String,JSONObject)->Unit)` exposes `val view:WebView`, `show(screen:String,state:JSONObject)`, `update(state:JSONObject)`, `setNativeSurface(view:View?)`, `close()`. Owns trusted local asset routing, JSON state delivery, measured native rectangle and touch pass-through |
| CaptureUiBridge | same file | `@JavascriptInterface fun dispatch(message:String)` parses a bounded JSON action object, validates action and selected IDs, posts to UI thread; never accepts executable script, shell, arbitrary path or network URL |
| CaptureUiState | LIBS/UI/STITCH/capture/runtime.js:1 | `window.RecruiserUi.render(state)` binds actual data; delegated data-action clicks send `{action,payload}` through native bridge; resize/scroll sends surface/control rectangles; empty real initial state, no recording simulator |
| Capture UI styles | LIBS/UI/STITCH/capture/runtime.css:1 | Small integration overrides: typography/touch targets, transparent native surface, unknown/error states, reduced motion and folded-width rules. Generated export CSS remains the visual base |
| CaptureLibraryStore | android/app/src/main/java/mba/robin/recruiser/capture/CaptureLibraryStore.kt:1 | `class CaptureLibraryStore(root:File)` with `snapshot():JSONObject`, `rename(sessionId:String,title:String)`, `createVignette(title:String):String`, `assign(sessionIds:List<String>,vignetteId:String?)`, `tag(sessionIds:List<String>,tags:List<String>)`, `trash(sessionIds:List<String>):String`, `restore(batchId:String)`. Atomic, validated grouping and recoverable removal; no world-frame mutations |
| Capture library metadata | app-private files/library.json | `{version:1,vignettes:[{id,title}],sessions:{sessionId:{title,vignetteId:null|string,tags:string[]}}}`; absent file means empty custom metadata, not fake sessions; corrupt file is preserved and reports error |
| Capture trash | app-private files/capture-trash/batchId/ | Moves complete selected session directories from captures/ using same-filesystem rename after explicit confirmation; receipt JSON maps original IDs. Restore is exposed to UI; never overwrite conflicting restored sessions or permanently purge automatically |
| CaptureUiData | android/app/src/main/java/mba/robin/recruiser/capture/CaptureUiData.kt:1 | Bounded reads of actual manifests/journals on IO executor into library/review state; counts, first retained image, dates, qualities, independent world frames, saved derived scene references. No static test data |
| CaptureActivity | android/app/src/main/java/mba/robin/recruiser/capture/CaptureActivity.kt:27 | Existing permission, ARCore install, controller, playback, export and processing owner rewired to CaptureUiHost actions. Existing lifecycle and recovery semantics retained |
| PointCloudView controls | android/app/src/main/java/mba/robin/recruiser/capture/PointCloudView.kt:1 | Add `fitScene()` and `setNavigationMode(mode:String)` for actual exported Fit/Orbit/Pan controls; native gesture renderer remains owner |
| Capture UI provenance | LIBS/UI/STITCH/capture/provenance.json:1 | Actual project/design/screen IDs, source and integrated hashes and cleaning reasons; no claim untouched generated code |
| Capture UI verification | scripts/verify-capture-ui.mjs:1 | Validates every bundled export/runtime: required component/action/binding contract, no external assets/inline handlers/mock scripts/forbidden unsupported claims or hardcoded telemetry, current provenance hashes, no stale raw assets bundled |
| Capture library tests | android/app/src/test/java/mba/robin/recruiser/capture/CaptureLibraryStoreTest.kt:1 | Real filesystem operations in test-owned temporary dirs: persistence, selection validation, corruption preservation, grouping distinct origins, rename/tag limits, trash/restore/conflict handling |
| Capture UI behavioral tests | tests/recruiser/capture-ui.test.ts:1 | Test-only DOM fixtures and actual runtime adapter: no bridge remains inert/unavailable, real-state bindings and actions, escaped hostile labels, disabled commands, native surface rectangles and no simulated state |

## Native versus web boundary

Bundle LIBS/UI/STITCH as an additional app asset source in android/app/build.gradle.kts. Load only `https://recruiser.local/capture/exports/{library,viewfinder,review,processing,revisit}.html` via WebViewClient.shouldInterceptRequest reading app assets; no server, INTERNET permission or external dependency. Block other navigations/requests and reject path traversal. Saved JPEG reads use a dedicated allowlisted local route that validates selected session and canonical image path; do not expose arbitrary app files. Disable file/content access, universal file URL access, mixed content, windows/popups and untrusted navigation. Bridge messages are at most 64KiB and accept only known actions with constrained IDs/text; state serialization uses JSONObject and JSON-safe quoting, not interpolated script strings. Destroy bridge/WebView on Activity teardown. No arbitrary data from captured files becomes HTML; use textContent/value and explicit DOM creation.

A FrameLayout places existing native GLSurfaceView/PointCloudView below a transparent WebView. The browser reports the real DOM native-surface bounds and interactive control rectangles on layout/scroll; native validates finite bounded values and converts CSS pixels to actual view coordinates. Layout changes resize/reposition the native view; feed its actual size to ARCore setDisplayGeometry through the existing renderer. Ancestors in the camera/scene slot are transparent, not merely the slot itself. WebView receives controls; touches beginning in a surface hole outside an interactive rectangle pass to the native view for the full gesture. No camera bytes, point buffers, depth textures or per-frame geometry cross the bridge. Replay uses the same native camera slot with capture controls disabled. Native COV2 renderer supplies live footprint/target overlays, not CSS simulations.

UI sends actions; only native callbacks acknowledge recording/saving/processing. Initial controls remain disabled until their capability/state is known. On page-ready deliver the latest state, not a stale initial snapshot. Coalesce ordinary state messages at <=4Hz; transitions/errors immediately. Native GL stays independent of WebView updates.

## Journeys and actions

Library: load actual saved directories and metadata; unreadable entries report errors. `capture-surroundings` enters permission/ARCore flow; `open-session` opens review. `select-session`, `toggle-select-all`, `tag-selected`/`apply-tags`, `move-selection`/`assign-vignette`, `open-vignette-dialog`/`create-vignette`, `delete-selected`/`confirm-delete` and `restore-trash` operate on validated selections. Select-all applies to the visible filtered set. Deletion dialog names actual count, explains recoverable removal, then moves only confirmed IDs. New captures inherit selected vignette; names/tags never merge coordinate frames. `export-selection` exports each complete original package into a user-selected directory via ACTION_OPEN_DOCUMENT_TREE; individual export retains ACTION_CREATE_DOCUMENT. No file is overwritten without the system document choice. Empty selections do nothing, not successful operations.

Viewfinder: Start capture, Pause, Resume, Finish, Another pass, sweep-left/right, toggle-coverage, Details and Back. Start and Finish acknowledgements come from ArCoreCaptureController; elapsed time is measured monotonic recording time, not a saved-frame count. No running text warnings; record details internally and use native visual states. `onCoverageUi` and `onGuidance` bind only optional Details/progress/control state. Another pass explicitly calls `startNextPass()` while capturing or `start()` while paused. Guide completion offers Another pass/Finish but keeps recording. Back during a recording finishes safely and then returns; background never auto-resumes. COV2 provides additive optional controller callbacks and methods; existing constructor callers remain compatible.

Review: actual session name, recorded JPEG previews, frame/IMU/depth counts and quality/gap details, rename/tags/vignette. Replay selects an actual recorded segment; Continue scanning starts a new independent world after leaving the camera. Build 3D selects a real world frame through existing chooseWorld and runs existing CaptureReconstruction on IO. Export uses original package writer. Saved PLY entries open revisit. Corrupt/missing data produces an honest error while retained originals remain untouched.

Processing: display real job status and backend; no estimate/percentage if denominator unavailable. Pause/Cancel both request durable job cancellation and wait for actual callback before acknowledging; cancel never deletes observations/checkpoints. Resume reuses recorded world and checkpoint. Saved success is set only after build returns its PLY. Thermal/capacity/no-depth failures retain original capture, with Review/Export reachable when job is no longer mutating it.

Revisit: actual PLY in PointCloudView with native drag/pinch/two-finger gestures; Fit/Orbit/Pan controls actually change view behavior. Bind count and metadata after load; no raw-depth/live-tracking claim for a saved fused scene. Back to review, Scan more and Export PLY map to real operations. Missing file is an error, not an empty successful scene.

## Limits and validation

Names trimmed 1..120 characters, tags at most 20 unique trimmed tags of 1..40 characters, selection at most 500 IDs, generated vignette/batch UUIDs, canonical session directories only. Metadata never modifies immutable observation assets. Store mutations serialize on the Activity IO executor and use fsync/atomic rename. Trash is recoverable, not permanent clear-all. No synthetic phone records are bundled; test data exists only in test sources.

Software gates: UI validator, DOM action/binding tests, real JVM store tests, Android compile/package and existing regressions. Operator protocol: phone camera/replay/GL visibility and touch pass-through, folded/unfolded portrait/landscape, no hidden controls, save/reopen/tag/move/trash/restore/export, actual adaptive targets/occlusion, sustained capture/thermal measurement. Device absence does not prevent implementation; it leaves device-only observations unverified.
