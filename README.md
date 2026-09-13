# Recruiser

A local-first spatial memory workspace, privately hosted with Sites. View GLB meshes, PLY meshes/points, and Gaussian PLY/SPLAT together in six degrees of freedom. Import original captures, connect a compatible reconstruction worker, review proposed additions, and preserve scene revisions.

## Run and build

Use the project package-lock.json. `npm ci` installs dependencies and `npm run build` builds the Vinext app. Sites tooling owns managed-environment setup and publication. `node --import tsx --test tests/recruiser/*.test.ts` runs the targeted tests. `npx tsc --noEmit` checks TypeScript.

## Use

Open scene files, add layers, and use Orbit or Mouse Fly. In Mouse Fly, wheel moves forward/back along the camera axis; Ctrl/Meta-wheel changes field of view (10–110°); Shift-wheel strafes; Alt-wheel rises/falls; Ctrl/Meta+Shift-wheel rolls. Left-drag looks and right-drag pans. Keyboard WASD, R/F and Q/E remain available; arrow keys look. Shift increases keyboard speed. Home frames visible geometry; Reset lens restores 55°. Wheel interception is limited to the canvas in Fly mode. Touch flight buttons expose all translation/rotation axes.

Select a layer and open Inspect layer. Flip X 180° converts Y-down/Z-forward camera coordinates to Y-up/Z-back; Flip Z is an alternative. No extension-based rotation is automatic: glTF specifies Y-up, while PLY orientation depends on the exporter. Choose Move layer or Rotate layer to focus the nudge pad. Arrows and Page Up/Down act on world XYZ, with adjustable steps; Shift multiplies by 10 and Ctrl/Meta by 0.1. Rotations are about the layer origin. Changes preview live; Apply alignment saves one revision, while Cancel/Escape or leaving the inspector discards the draft.

Screenshot saves a PNG of the rendered canvas. Record captures the viewport with canvas.captureStream/MediaRecorder at up to 30 fps, without audio, in browser-supported WebM or MP4. Stop downloads the clip; a 512 MiB recording cap triggers automatic stop/save. These are perspective viewport captures, not spherical/stereo exports. The recording timer and Home (fit view) button have distinct labeled controls. Browser recording remains unverified.

Export opens three explicit choices:
- Project ZIP: complete originals, mixed representations, transforms, hidden layers and revision history. Import it through File → Import project.
- Combined GLB: visible mesh and point layers in one scene, preserving transforms/materials. Hide Gaussian layers first. Objects are combined without surface welding or automatic registration. This is a static composition export; keep ZIP for original animations/extensions.
- Combined Gaussian PLY: visible Gaussian layers, baked transforms, base colors (SH degree zero), using the renderer’s loaded precision. Hide meshes and ordinary points first. Higher-order view-dependent colors remain in the original assets retained by Project ZIP; PLY is not advertised as lossless.

The capture dialog attempts browser-native decoding, including JPEG/MP4 signatures within INSP/INSV, reserves visible video space and reports zero decoded video dimensions instead of treating audio playback as a video preview. A compatible preview copy can be attached separately (optional previewBlobKey/previewName/previewMime source fields). The worker continues to receive original bytes; ZIP round trips preserve both originals and preview copies. Source download and preview download remain explicitly labeled. The supplied 20260905_011458.mp4 is valid HEVC Main 10 / PQ HDR with AAC; an H.264 SDR proxy was converted separately and decoded through its full duration. This does not implement stitching or a depth model.

Import media through Add captures. Original INSP/INSV bytes are retained; select their projection and lens profile in the source inspector. Worker capabilities must explicitly cover the input. Connect a browser-accessible HTTPS Recruiser v1 worker with CORS support. Review proposals before acceptance. Every accepted change is a new revision. Export ZIP retains scene history and original assets, including Gaussian representations.

## Integration and evidence

CHECKLIST.md contains the approved architecture, worker protocol, isolated task packets, and acceptance rubric. The worker implements decoding, stitching, pose recovery, registration and reconstruction; those compute services are not included in this frontend. Endpoint credentials remain in session memory. The frontend never embeds Admin-Manual secrets.

Nine targeted tests pass, including camera modifier semantics, immutable nudge transforms, actual combined GLB reimport, and baked Gaussian PLY reimport. No browser/GPU QA was requested. Actual browser playback of the user’s MP4 and raw camera files remains unverified; the supplied MP4 inspection established HEVC Main 10 / PQ HDR and a separately converted H.264 SDR copy decoded successfully. Standalone inspection confirmed JPEG/MP4 containers in earlier Air samples, but does not establish universal format support. Gaussian rendering uses Spark 2.1.0. Three.js and all decoder files are bundled locally.

Local projects use IndexedDB. Persistence is subject to browser storage policy; export provides portable preservation. Service worker caches the shell and same-origin renderer assets after online use. Raw media is not uploaded until reconstruction is submitted to the connected worker.

Copyright (C) 2025-2026 Robin L. M. Cheung, MBA. All rights reserved. Bundled third-party components retain their respective licenses.


### Cleanup and recovery (v0.3)

- Trash controls remove layers from the active scene and captures from the active capture list. Undo restores the individual item; Scene history restores a complete earlier composition. This is recoverable removal, not permanent blob erasure: originals remain for provenance, older revisions and saved worker jobs. `removedSourceIds` is an optional revision field; archives remap it when importing a copy.
- Inspect layer → Reset to imported pose restores its first saved position, quaternion and scale. Undo last saved alignment finds the previous differing pose on the current revision ancestry. Both preview before Apply/Cancel. GLB embedded node transforms remain intact; no file extension is used to automatically invert geometry.
- Export, screenshot and recording produce a queued “Your file is ready” dialog. Download is an explicit link; supported top-level contexts offer native Save file, and supported file types offer Share. No toast claims a download completed. Open save tab conditionally passes the prepared Blob to `/save-export` using exact origin, window and random-session checks, without shared IndexedDB or server upload. Popup/sandbox/COOP restrictions can block this; timeouts leave the prepared file available. The save page starts no WebGL renderer. Save-tab browser execution is unverified.
- Graphics startup lets the browser choose its GPU, then retries a low-power preference. Retry graphics rebuilds the renderer from saved state. Disposed renderers release their contexts. WebGL2 remains required; a blocked or disabled GPU cannot be enabled by the site. Project ZIP export remains available without graphics.
