# Kinora status (2026-10-03): 0.1.0 released

Milestones M0–M8 are done. What is left is under "Backlog".

## Done and verified in a dev client
- M0: multi-module build (kinora-core / kinora-api / kinora-mc / tools/inspector), research in `docs/internals/`, `docs/design.md`, `docs/format.md`. License: LGPL-3.0 API (`LICENSE-API`), MIT rest (`LICENSE`; since the 0.1.0 release the Kinora Replay License, the sample mod staying MIT), `THIRD_PARTY_NOTICES.md`.
- M1: recording (raw frame capture via `ConnectionMixin`, state model + snapshots, CLIENT_STATE, sounds, markers, thumbnail, replay buffer, crash recovery). `kinora-inspect` validates recordings.
- M2: playback through an EmbeddedChannel, puppet, replay clock mixins, free/spectate/orbit camera, seeking (snapshot restart + fast-forward), scene overrides, browser, HUD.
- M3 core: quaternions, easing, splines, arc length, time remap, rigs, look-at, shake, templates, sequence, project JSON — all core tests pass.

## Camera editor (M3 UI): verified
- In-game script runner (`dev/DevScript`, dev only) drives the client without OS input; see
  `tools/dev/README.md`. `tools/dev/scripts/editor-smoke.txt` passes: replay opens (paused), K toggles,
  editor opens, Ctrl+N shot, I key, right-drag + W fly moves the camera, second key, Space plays and
  pauses the preview, Esc closes.
- The earlier "Space does nothing" report was the OS-input test misfiring, not the editor.
- Undo/redo/play/loop buttons now use text labels.
- Known: each run recovers the previous autosave, so test projects accumulate shots. The selected shot
  in the strip is the dimmed (inactive) button, which reads backwards — M6 polish.

## M4 renderer: done and verified
- PNG and video (FFmpeg 9.0.2 in `run/kinora/tools/`): H.264, H.265, ProRes, stop and resume (NUT parts,
  joined without re-encoding, exact timestamps). The dialog marks formats the FFmpeg cannot encode
  (the essentials build has no AV1).
- Bit-identical renders (vanilla and with Faithful 32x): level clock resynced after loading, snapshot
  time packets advanced, animated textures tied to the replay tick (`SpriteAnimationMixin`).
- Sodium 0.9.2 + Iris 1.11.4 + Complementary Reimagined: renders work (dev runs need -PnoGlValidation,
  IrisShaders/Iris#3304); `TerrainReadiness` waits for Sodium's chunk builder; the optional
  `kinora.compat.mixins.json` drives Iris's frame clock. Shader packs with TAA still vary faintly.
- Hand-written projects: `project load`, `entities`, `seek`, `shot` script commands; examples in
  `docs/examples/`; `tools/dev/kinora-run.ps1`. The README is the guide for people and AI assistants.
- Camera now starts at the recorded player when a replay opens.

## M5 quality features: mostly done
- Sound: sounds captured during the render (with the camera per frame, in `queue/<job>.audio.jsonl`, kept
  across stop/resume), placed through the time remap, distance falloff and panning from the camera,
  tape-pitch / keep-pitch / mute modes, optional music; muxed with FFmpeg (AAC / Opus / FLAC / PCM) or
  `audio.wav` beside image sequences. Verified on the test film (-24.5 LUFS, 54 sounds).
- Grading and lens effects (`Grade`): exposure, contrast, saturation, temperature, tint, vignette, grain,
  chromatic aberration, bloom, letterbox. World time and weather tracks (fractional, fades).
- 360 equirectangular, 3x2 cube map, stereo SBS / TB (`Views`): six or two renders per image, joined on
  the encoder thread. Verified visually.
- Depth of field: depth copied by a shader pass (`DepthCopy`, `depth_copy.fsh`) at AfterLevel, read
  back with the colour; thin-lens blur from pre-blurred levels (`DepthOfField`); autofocus on targets.
- Follow/orbit smoothing fixed: targets tracked from the start of a render, 3 s pre-roll, history
  kept across seeks, missing history clamps instead of jumping to "now".
- `renderset {json}` script command; render dialog has View (projection) and Sound buttons.
- Not done: 16-bit PNG / EXR, tiling beyond the texture limit, depth / alpha passes.

## M6 UI polish: done
- Inspector: value fields for the selected key (number, x/y/z, yaw/pitch); "Add track..." list for every
  look, lens and world track with a useful first value; header names the track. Timeline shows every
  track the shot uses, capped at half the screen with scrolling.
- Camera path drawn with the game's gizmos (`PathGizmos`, emitted from the camera hook while the frame's
  gizmo collector is open): path line, key dots and direction arrows, playhead camera; H toggles.
- Command palette (Ctrl+K, `CommandPalette`): ~60 actions, fuzzy matched.
- Replay keys: `ReplayKeys` + `ReplayKeysScreen` (Esc menu), saved to `kinora/keys.json`, HUD hint uses
  them (shows layout-correct names, e.g. Ü / + / # on a German keyboard).
- First-run editor guide (`UiState`, `kinora/ui.json`), ? button.
- Selected shot outlined; free camera starts behind the recorded player; camera direction kept across
  world rebuilds; steady window preview during 360 / stereo renders; progress box scales to small renders.
- Dev: `mc-devclient.ps1` puts the window on the secondary monitor without focus; `camera` script command
  switches the editor to free camera.

## M7 extras: 9 of 14 done
- Automatic markers while recording (`EventDetector`): death, kill, damage burst, explosion, chat,
  advancement, dimension change, mining streak. Verified with `record-events.txt`.
- Clip export (`ClipExporter`, Esc menu and `kinora-inspect clip`): a 118 KB clip opened at 8.00 s.
- Photo mode (B, Shift+B for 8K): one PNG of the current view.
- Titles (`overlays`, editor "Titles (n)..." pop-out), auto-director (three suggested shots), motion
  trails, project versions, share packages (`.kinorapack`, drag onto the browser), render report.
- Not done: live-cut directing, macro recorder, path collision correction, 16-bit/EXR, tiling.

## M8 API, sample mod, docs: done
- `kinora-api` wired (`KinoraRuntimeImpl`): state, replay time, render clock, data tracks with snapshot
  state, seeded random, hideables, listeners. Registration before Kinora starts is held and bound later.
- `sample-mod` ("Sparkles"): `sample-record.txt` recorded 3 MOD_DATA records, `sample-play.txt` replayed
  them (counter restored after a seek) and rendered the sparkles.
- Docs: README (guide for people and AI assistants), `docs/api.md`, `docs/mixins.md`, `UPGRADING.md`,
  `CHANGELOG.md`. CI: `.github/workflows/build.yml` (build, lang check, artifacts).
- Final regression: core tests (90) pass; `editor-smoke.txt` and `film.txt` pass after the partial-tick
  clamp fix and the x264/x265 `-preset medium` change.

## Compatibility (tested)
| What | Version | Result |
| --- | --- | --- |
| Minecraft / NeoForge | 26.2 / 26.2.0.88 | Works |
| OpenGL, NVIDIA, Windows 11 | – | Works |
| Vulkan, NVIDIA, Windows 11 | – | Works with NeoForge's early window turned off (`earlyWindowControl = false`); `kinora-run.ps1 -Vulkan` |
| macOS, Linux | – | Not tested (no machine); accepted risk |
| Sodium | 0.9.2 | Works |
| Iris + Complementary Reimagined | 1.11.4 + r5.9.3 | Works; faint run-to-run noise from TAA. Dev needs `-PnoGlValidation`. |
| Faithful 32x | 26.2 | Works, bit-identical (must be force-enabled: old pack metadata) |
| FFmpeg | 9.0.2 essentials | H.264, H.265, ProRes (422, 4444 with alpha), VP9, FFV1, NVENC; AV1 needs a full build |
| Multiplayer recording | NeoForge dedicated server, offline mode, compression on | Works: `mp-record.txt` on `mc-devserver.ps1`, then `mp-play.txt` (markers, seek, render) |
| Determinism | – | Same shot rendered twice: identical except up to 2 (OpenGL) / 4 (Vulkan) single pixels on water seams in some frames (`samerenders 4`) |

## Performance (Test Arena, NVIDIA, Windows 11)
| Case | Speed |
| --- | --- |
| 1080p PNG | ~37 images/s |
| 1080p H.264 film, 900 frames, dissolve, sound | 20 s with x264 (FFmpeg-bound), 11 s with NVENC (~84 images/s) |
| 1080p graded | ~14 images/s |
| 720p depth of field | ~10 images/s |
| 2048x1024 360° | ~18 frames/s |
| 720p Iris + Complementary | ~40 images/s |
| Recording | 65–78x compression |

## Backlog (priority order)
1. ~~Encoder throughput~~: done (GPU encoders, writer thread, parallel mixing).
2. ~~16-bit PNG / OpenEXR, depth and alpha passes~~: done (`passes.txt`). Entity-id and normal passes
   remain, deliberately: they need their own shaders for every terrain, entity and particle pipeline (and would
   fight Iris shader packs). Depth and alpha cover the common compositing needs.
3. ~~Tiled renders beyond the GPU texture limit~~: done (`tiles.txt`, `photo-big.txt`: a 20000 x 11250 photo in 2 x 2 tiles).
4. ~~Live-cut directing~~: done (`live-cut.txt`).
5. ~~Path collision correction~~: done (`path-fix.txt`: a path dipping into the floor is lifted clear).
6. ~~Multiplayer recording tests~~: done. Packet translation for recordings from older versions remains
   (needs a second Minecraft version to exist).
7. ~~Vulkan~~: done. macOS / Linux: no test machine; accepted risk.
8. ~~Looping / tickable sounds in render audio~~: done (`loops-record.txt`, `loops-play.txt`). Game music stays
   out by design.
9. ~~Spherical metadata in 360° MP4s~~: done (`vr360.txt`). ~~Bezier handles~~: done (`curves.txt`: handle presets and
   numbers in the inspector, curves in timeline rows, path handles in the world). Macro recorder: replaced by the
   look actions (`look.txt`), which cover the edit it was meant to repeat; scripts and project files cover the rest.

## Test assets
- `run/kinora/replays/2026-10-02_17-27-11_Test_Arena.kinora` (40 s, 1 snapshot).
- Launch with a replay: `tools\dev\mc-devclient.ps1 -NoJoin -GradleArgs @("-Preplay=<abs path>")`.

Nothing is committed yet.
