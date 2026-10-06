# Changelog

## 0.1.0 (2026-10-07)

First release, for Minecraft 26.2 and NeoForge 26.2.0.88 (Java 25), client only. The release notes
for players are in [`docs/curseforge/changelog-0.1.0.md`](docs/curseforge/changelog-0.1.0.md). It is the release candidate
below plus these changes.

### Name and license

- The mod is called **Kinora Replay** (Kinora for short). Its id is `kinora`.
- Jars are named `kinora-replay-<version>-<Minecraft version>.jar` (the sample mod:
  `kinora-replay-sample-...`).
- The mod is under the Kinora Replay License: free to use and to build for yourself, not to repost.
  The API stays LGPL-3.0-only and the sample mod MIT.

### Replays

- The recording player walks like any other player in replays (one-tick interpolation), so limbs
  swing and motion is smooth between ticks.
- The recording player's outer skin layer (hat, jacket, sleeves) shows. Recordings store the
  player's synced values (skin layers, main hand, pose, crouching) whenever they change; older
  recordings show all skin layers.
- While a replay is open, other mods' key bindings (and vanilla actions) are switched off; only
  Kinora's keys, camera movement, screenshot and fullscreen act.
- Recording thumbnails work at any window size. The game crashed a few seconds into a recording
  when width and height had no common factor (for example 3440 x 1369); Kinora now shrinks the
  thumbnail itself, and a thumbnail that cannot be taken is skipped instead of stopping the game.
- Replays recorded on a server show the right time of day. Servers send the time of day only when
  a player joins or it changes; Kinora keeps it and writes it into every recording and snapshot.
- Distant Horizons' far terrain shows in replays recorded on a server: the replay goes by the
  recorded server's name, so Distant Horizons finds the data it saved there.
- Distant Horizons' far terrain also shows in replays recorded in singleplayer. Kinora points
  Distant Horizons at the world's own data (a copy in `kinora/cache/distanthorizons`, so the world
  is never written to). Recordings now note the save's folder name (`worldFolder`); older ones are
  matched by the world's name.
- Recorded players keep their own skins. An offline or development client cannot check the skin's
  signature and showed a default skin, and after a seek the recording player sometimes came back
  without one; both are fixed, also for recordings already made.
- Other mods' synced data on the recording player (NeoForge attachments, such as a magic mod's robe)
  shows in replays and renders, also after seeking.
- **Player names** can be hidden and shown: V in a replay, the replay menu, or Ctrl+K in the editor.
  The choice applies to renders and is remembered.
- The friends list no longer opens on O in a replay, so O reaches the orbit camera again.

### Rendering

- GPU video encoding: H.264 / H.265 on NVIDIA (NVENC), AMD (AMF) and Intel (QSV), offered only when
  they work on the machine. The 1080p test film renders in 11 s instead of 21 s.
- Frames are written to FFmpeg on their own thread while the next ones are graded and mixed;
  dissolve and motion-blur mixing runs in parallel.
- 16-bit PNG and OpenEXR (linear, half float) sequences; ProRes 4444 with alpha.
- Depth pass (EXR, distance in blocks) and transparent sky (alpha from depth, premultiplied while
  mixing).
- The render report splits waiting for the encoder from waiting for the GPU copy, and times each
  encoder step.
- Fixed: changing the format in the render dialog kept the previous format's quality value.
- Determinism: renders start from a freshly restored world; entities do not tick while a replay
  restores its world (their tick count drove idle animations); every translucent section is
  re-sorted each frame during renders and the settle check waits for re-sorts.

### Big frames

- Tiled rendering: frames beyond the GPU's limit (or 16384) are rendered in pieces and joined;
  photos up to 32768 wide (Ctrl+B: 16K).

### Directing

- The shot inspector shows where a shot sits in the replay: **Replay start** and **Replay end**
  (m:ss.ss, or seconds), beside its length. Changing the start moves the shot; changing the end
  makes it longer or shorter at the same speed (a shot with keyed speed changes keeps its length
  and its changes are stretched).
- Live cut: cut between up to nine cameras (shots) with 1-9 while the replay plays; the cuts become
  the edit.
- No first-person hand in front of the replay camera.
- Look actions: copy a shot's grading, effects and shake to one or all shots.
- Bezier handles: presets (automatic, ease, linear) and numbers in the inspector; one-value tracks
  show their curve in the timeline; path handles are drawn in the world.
- Camera paths: stretches inside blocks are drawn red; "Path: move it out of blocks" fixes them.

### Sound and 360°

- Continuous sounds (minecarts, bees, elytra wind, biome ambience) are in render audio, followed
  tick by tick.
- 360° MP4 / MOV renders get Spherical Video V2 metadata.

### Testing

- Multiplayer: recorded on a local dedicated server and played back (`mc-devserver.ps1`, `-Server`).
- Vulkan: renders work (`-Vulkan`; NeoForge's early window must be off).
- `samerenders` script command checks two renders match.
- The dev script's `state` logs the game time and the time of day.

## 0.1.0 release candidate (not published)

First version. Minecraft 26.2, NeoForge 26.2.0.88, client only.

### Recording

- `.kinora` format: raw packet capture, zstd compression, seek snapshots, markers, thumbnail, crash
  recovery, index.
- F6 records, F7 adds a marker, F8 saves the replay buffer ("clip that").
- Markers are added automatically for deaths, kills, bursts of damage, explosions, chat,
  advancements, dimension changes and mining streaks.

### Playback

- Replay browser, with drag and drop of shared packages.
- Pause, speed 0.01x to 10x, tick stepping and seeking.
- Free, spectate and orbit cameras.
- Hiding entities and categories, nametags and chat; overriding time of day and weather.
- Rebindable replay keys.

### Camera editor

- Shots with keyframed path, direction, roll, FOV and replay time (speed ramps, freezes, reverse).
- Follow and orbit rigs, look-at, shake, templates.
- A sequence with cuts, dissolves, dips to black and whip pans.
- Grading, lens and world tracks.
- Undo and redo, autosave.
- In-world camera path and motion trails, Ctrl+K command palette, inspector value editing.
- Titles, project versions, auto-director suggestions, first-run guide.

### Rendering

- Frame-exact offline renderer.
- PNG sequences, and video through FFmpeg (H.264, H.265, AV1, ProRes, FFV1, VP9) with stop and resume.
- Motion blur and supersampling; depth of field; grading and lens effects; titles.
- 360° (equirectangular, cube map) and stereo 3D.
- Sound mixed from the replay, positional, following speed changes, with optional music.
- Photo mode up to the GPU's texture limit.
- Bit-identical renders for vanilla and resource packs.
- Sodium and Iris (shader packs) supported.
- Render queue, performance report.

### Sharing

- Clip export: trim a replay into a smaller file.
- Share packages: replay + project + versions in one file.

### API

- `kinora-api` (LGPL-3.0): state queries, replay time and render clock, data tracks with snapshot
  state, a reproducible random source, hideable elements, listeners.
- `sample-mod` shows a complete integration.

### Tools

- `kinora-inspect` CLI: info, stats, records, markers, snapshots, validate, recover, thumbnail, clip.
- In-game dev scripts for automated testing without OS input.
