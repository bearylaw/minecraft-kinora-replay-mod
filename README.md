<p align="center"><img src="kinora-mc/src/main/resources/kinora.png" alt="Kinora Replay icon" width="160"></p>

# Kinora Replay

**Record your gameplay, then film it again like a director.** Kinora records what you play, lets
you fly a keyframed cinematic camera through the replay, and renders smooth, frame-exact video:
paths, orbits, follow cams, speed ramps and color grading, with no lag in the final cut.

Kinora Replay (Kinora for short) is a cinematic replay and camera mod for **Minecraft 26.2**
(NeoForge 26.2.0.88, Java 25, client only).

## Quick install

1. **Minecraft 26.2 with NeoForge 26.2.0.88 or newer.** Any launcher works (CurseForge, Prism,
   the official launcher with the NeoForge installer). Java 25 is needed; launchers that manage Java
   pick it for you.
2. **Kinora Replay:** download `kinora-replay-<version>-<Minecraft version>.jar` (for example
   `kinora-replay-0.1.0-26.2.jar`) from the
   [releases](https://github.com/bearylaw/minecraft-kinora-replay-mod/releases) and put it in the
   profile's `mods` folder. It is client only: servers do not need it.
3. **FFmpeg (separate download, needed for video).** Kinora does not ship FFmpeg. Without it you can
   record, watch, edit and render image sequences (PNG / EXR), but not MP4 / MOV / MKV / WebM.
   - Download the **"essentials"** build from [gyan.dev](https://www.gyan.dev/ffmpeg/builds/)
     (Windows; it has every format except AV1) or FFmpeg from your package manager (macOS / Linux).
   - Put `ffmpeg.exe` (`ffmpeg` on macOS / Linux) in the profile's **`kinora/tools/`** folder, for
     example `C:\Users\<you>\curseforge\minecraft\Instances\<profile>\kinora\tools\ffmpeg.exe`.
     Make the folder if it is not there yet; a `kinora/tools/bin/` folder from the zip works too.
   - Or: install FFmpeg on the PATH, or set `ffmpegPath` in `config/kinora-client.toml`.
   - Check: the render dialog names the FFmpeg it found and marks formats it cannot encode.
4. **Optional:** Sodium and Iris (shader packs) are supported and need no setup. For the Vulkan
   backend, set `earlyWindowControl = false` in `config/fml.toml`.

Then press **F6** in a world to record, and use the **Replays** button on the title screen to watch
and film. See [Using Kinora in the game](#using-kinora-in-the-game).

---

Kinora records what you play into a compact `.kinora` file. You can open the recording later, fly
through it freely, and film it again with keyframed camera shots: paths, orbits, follow cams,
look-at, shake, speed ramps, freeze frames and reverse. It then renders the result offline, frame by
frame, to video or images. Renders are exact: the same project gives the same frames every time.

This README is written so that **a person or an AI assistant can make shots without knowing the
code.** The section [Making shots (for people and AI assistants)](#making-shots-for-people-and-ai-assistants)
is the complete guide: workflow, coordinate conventions, every project field, ready-made shot
recipes and a worked example.

- [Quick install](#quick-install)
- [Status](#status)
- [Using Kinora in the game](#using-kinora-in-the-game)
- [Making shots (for people and AI assistants)](#making-shots-for-people-and-ai-assistants)
- [Rendering](#rendering)
- [Building and developing](#building-and-developing)
- [Repository layout](#repository-layout)

---

## Status

| Area | State |
| --- | --- |
| Recording (`.kinora` format, snapshots, markers, replay buffer, crash recovery) | Working |
| Playback (seek, speed, free / spectate / orbit camera, scene overrides) | Working |
| Camera editor (shots, keyframes, rigs, look-at, shake, templates, sequence, undo) | Working |
| Offline renderer (PNG sequence, FFmpeg video, queue, stop and resume) | Working, verified |
| Bit-identical renders (vanilla, resource packs) | Verified |
| Sodium + Iris shaders | Working; the shader's own temporal anti-aliasing adds faint noise between runs |
| Motion blur, supersampling | Working (CPU blend) |
| Sound in renders (positional, follows speed changes, optional music) | Working |
| Grading and lens effects (exposure, contrast, saturation, white balance, vignette, grain, chromatic aberration, bloom, letterbox) | Working |
| World time and weather tracks | Working |
| 360° (equirectangular, cube map) and stereo 3D (side by side, top and bottom) | Working |
| Depth of field (physically based, autofocus, rack focus) | Working |
| Automatic event markers, clip export, photo mode, titles, auto-director, motion trails, project versions, share packages, render report | Working |
| Public API for other mods (`kinora-api`), sample mod | Working, verified end to end |
| 16-bit PNG and OpenEXR sequences, depth pass, transparent sky, GPU video encoding (NVIDIA / AMD / Intel) | Working |
| Live-cut directing, camera paths kept out of blocks, continuous sounds in renders, 360° metadata | Working |
| Tiled renders beyond the GPU's texture limit | Working |
| Entity-id and normal passes | Not yet |

Progress notes: [`docs/STATUS.md`](docs/STATUS.md). Design: [`docs/design.md`](docs/design.md).
File format: [`docs/format.md`](docs/format.md). Notes on Minecraft's internals:
[`docs/internals/`](docs/internals/).

---

## Using Kinora in the game

### Recording

| Key | Action |
| --- | --- |
| F6 | Start / stop recording |
| F7 | Add a marker |
| F8 | Save the replay buffer (the last minutes, if enabled in settings) |

Recordings go to `<game folder>/kinora/replays/`. While recording, Kinora adds markers by itself
for deaths, kills, bursts of damage, explosions, chat, advancements, dimension changes and mining
streaks. N / M jump between markers in the replay. The **Replays** button on the title screen (film
icon next to the menu) opens the browser.

### Watching a replay

A replay opens **paused**, with the camera just behind the recorded player. All replay keys can be
changed under Esc → **Replay keys** (saved in `kinora/keys.json`). They are kept out of the game's
Controls screen on purpose: they only act inside a replay, and listing them there would mark them as
clashing with gameplay keys. While a replay is open (flying or in the editor), **other key bindings are
switched off**: other mods' keys and vanilla actions (inventory, hotbar, chat...) do not react. Only
Kinora's keys, the movement keys that fly the camera, screenshot and fullscreen work. Mods that read
raw keyboard events instead of key bindings are not covered.

| Key | Action |
| --- | --- |
| K or P | Play / pause |
| J / L | Back / forward 5 s (Shift: 30 s) |
| , / . | One tick back / forward (Shift: a quarter tick) |
| [ / ] | Slower / faster; \ resets to 1x |
| N / M | Previous / next marker |
| F | Free camera (WASD, Space / Shift up / down, mouse wheel sets speed) |
| G | Spectate the entity under the crosshair (again: free camera) |
| O | Orbit the entity under the crosshair |
| Z / C / X | Roll left / right / reset |
| E or Tab | Open the camera editor |
| B | Photo: a 4K still of the current view (Shift: 8K, Ctrl: 16K) |
| R | **Live cut**: direct while the replay plays (see the camera editor) |
| V | Show / hide player names (also in the replay menu; renders follow it, and it is remembered) |
| Esc | Replay menu: editor, settings, replay keys, **Export clip...** (save a stretch as its own small replay), player names, mod elements, exit |

### The camera editor

The first time the editor opens, a short guide explains the steps; the **?** button shows it again.

- **New Shot** (Ctrl+N) starts a 5-second shot at the current replay moment.
- Hold the **right mouse button** over the world and use WASD to fly. Press **I** to key the camera at
  the playhead.
- **Space** plays the shot. **Home** / **End** jump to the shot's start and end. The arrow keys step
  one frame (with Shift, one second).
- **F** switches between seeing the shot's camera and flying freely.
- Ctrl+Z / Ctrl+Y undo and redo, Ctrl+C / Ctrl+V copy keys, Delete removes the selection, Ctrl+S saves.
- **Templates** inserts a ready-made move: orbit, dolly zoom, crane up, fly-through, whip pan,
  reveal, push-in or turntable.
- **Ctrl+K** opens the command palette: type a few letters of any action (templates, rigs, shake,
  look-at, "add track: vignette", select a shot, render...) and press Enter.
- **Add track...** in the inspector keys a look, lens or world track (grading, depth of field,
  letterbox, time of day, weather...) at the playhead. Selecting a key shows its **value** fields:
  one number, x / y / z for the camera path, or yaw / pitch for the direction.
- The shot's **camera path is drawn in the world**: a blue line, a dot and a direction arrow per
  key (the selected key in orange), and the camera at the playhead while flying. **H** hides it.
  Where the camera would be inside a block, the line turns **red**; Ctrl+K → **Path: move it out of
  blocks** adds keys that steer those stretches to the nearest clear place across the path.
- One-value tracks (FOV, grading, time of day...) show their **curve** in their timeline row. A key
  set to **Bezier** gets **Handles**: automatic, ease (the value comes to rest at the key) or linear,
  and for one-value tracks the handles as numbers (In / Out seconds and change of value). Bezier
  handles of the camera path are drawn in the world in yellow.
- The selected shot in the strip has an orange outline. With many tracks, scroll the track names to
  scroll the timeline; scroll the inspector when it holds more than fits.
- Under the shot's **Length** and **Speed**, **Replay start** and **Replay end** say which stretch of
  the replay the shot shows (m:ss.ss from the replay's start; plain seconds work too). Type a new
  start to move the shot; type a new end to make it longer or shorter at the same speed.
- **Titles (n)...** next to Add track lists the shot's titles: type the text, choose top / middle /
  low, or add one. Timing, size, colour and fades are in the project file (`overlays`).
- Ctrl+K → **Auto-director** adds three suggested shots of the subject (an orbit, a push-in and a
  crane reveal), each started from the side with the clearest view. Shots following or looking at a
  subject show its recent movement as a green trail.
- Ctrl+K → **Save a version** keeps a named copy of the project; **Restore version** brings one back
  (undoable). **Share** packs the replay, project and versions into one `.kinorapack` in
  `kinora/shared`; the receiver drops it onto the replay browser.
- Ctrl+K → **Look: give every shot this shot's look** copies the grading, lens effects and shake to
  all shots (keyed changes stretched to each shot's length); **Look: copy** / **Look: paste** do it
  for one shot. Camera moves, timing, depth of field and world tracks stay as they are.
- **View: Sequence** plays all shots in order with their transitions.
- **Live cut** (R in the replay, or Ctrl+K → Live cut): the replay plays and the project's first nine
  shots are your cameras. Press **1-9** to cut to a camera as the action happens; **R** again finishes,
  **Backspace** cancels. Each stretch between cuts becomes a shot of its camera showing exactly that
  replay time (its moves continue from the matching moment), and these shots become the edit. The
  project as it was is saved as a version first, and Ctrl+Z undoes.
- **Render** opens the render dialog.

Projects are saved per replay in `kinora/projects/<replay id>.kinoraproj` and autosaved every 30 s.

---

## Making shots (for people and AI assistants)

A shot is a JSON description of a camera move over a stretch of the replay. You can make shots in
the editor, but writing the project file directly is just as supported, and it is the easiest way
for an AI assistant. Kinora's dev client can then load the file and render it, driven by a small
script, with no clicks or keystrokes sent to the desktop.

### The workflow

1. **Pick a replay.** List them in `run/kinora/replays/` (dev client) or `<game>/kinora/replays/`.
2. **Find out what is in it.** Run a discovery script. It writes the entities (with ids and
   positions) at the moments you choose, and exports the current project:

   ```text
   open 2026-10-02_17-27-11_Test_Arena.kinora
   waitfor replay 120
   seek 5
   wait 2
   entities entities-5s.json
   seek 20
   wait 2
   entities entities-20s.json
   project export project.json
   ```

   Example output (`run/kinora/dev/entities-20s.json`):

   ```json
   { "replaySeconds": 20.0, "replayTicks": 400.0,
     "entities": [
       { "id": 1, "type": "minecraft:player", "name": "Dev", "recordedPlayer": true,
         "x": 48.3, "y": -60.0, "z": 10.3, "yaw": 0.0 },
       { "id": 3, "type": "minecraft:skeleton", "name": "Skeleton", "x": 32.5, "y": -59.0, "z": 73.5, "yaw": 0.0 } ] }
   ```

   Entity **ids are stable within a replay**: the same id means the same mob every time the replay is
   opened. Use them for follow, orbit and look-at targets. The recorded player is marked
   `"recordedPlayer": true`.

   You can also take still screenshots at chosen camera positions: `camera x y z yaw pitch`, then
   `screenshot name`.
3. **Write the project** (`.kinoraproj`, JSON; see the [reference](#project-file-reference) and the
   [recipes](#shot-recipes)).
4. **Render it** with a script, for example `film.txt`:

   ```text
   open 2026-10-02_17-27-11_Test_Arena.kinora
   waitfor replay 120
   project load D:/path/to/my-film.kinoraproj
   render sequence video 1920 1080 60 1 libx264 mp4
   waitfor rendered 600
   ```

   Run it in the dev client:

   ```powershell
   tools\dev\kinora-run.ps1 -Script path\to\film.txt
   ```

   The command prints the script log and exits with 0 on success. The video goes to
   `run/kinora/renders/`. `render shot ...` renders only the selected shot; select one with
   `shot <name>`.
5. **Look at the result** by pulling stills from the video, then adjust and render again:

   ```bash
   run/kinora/tools/ffmpeg.exe -v error -ss 4.0 -i run/kinora/renders/My_film_sequence.mp4 -frames:v 1 still_4s.png
   ```

   ```bash
   run/kinora/tools/ffprobe.exe -v error -count_frames -show_entries stream=nb_read_frames,r_frame_rate -of compact run/kinora/renders/My_film_sequence.mp4
   ```

   For fast iteration, render a small PNG sequence first (`render shot png 640 360 30`) and look at
   a few frames.

### Conventions (read this before placing a camera)

| Thing | Convention |
| --- | --- |
| Positions | Minecraft world coordinates in blocks; **Y is up**. Superflat worlds have the ground at about y = -60. |
| Yaw | Degrees. **0 = facing south (+Z), 90 = west (-X), 180 or -180 = north (-Z), -90 = east (+X).** Yaw is the direction the camera looks. |
| Pitch | Degrees, **positive looks down**: 0 is level, 90 is straight down, -90 straight up. 5 to 20 suits most shots. |
| Roll | Degrees, positive tilts clockwise. |
| FOV | Vertical field of view in degrees. 70 is Minecraft's default; 30 to 50 looks like a long lens, 90 and up is wide. |
| Shot time | Seconds of output video, from 0 to the shot's `duration`. Keyframe `time` uses this. |
| Replay time | **Ticks, 20 per second**, the same numbers as `replayTicks` in `entities.json`. The `time` track maps shot time to replay time. |
| Direction to a point | yaw = atan2(-dx, dz) in degrees; pitch = atan2(-dy, sqrt(dx² + dz²)) in degrees, where (dx, dy, dz) = target - camera. Or simply use `lookAt`. |
| Entity offsets (follow rig) | In the target's frame: **+z ahead of it, +x to its left, +y up**, measured from its feet. `{ "x": 0, "y": 2, "z": -5 }` is 5 blocks behind and 2 above. |
| Orbit angle | Degrees. The camera sits where a viewer at the target facing that yaw would look: 0 puts the camera south of the target, 90 west. It increases with `orbitSpeed` (degrees per second) unless an `orbit.angle` track is given. |

### Project file reference

A minimal project. Every field not shown has a sensible default:

```json
{
  "name": "My film",
  "shots": [
    {
      "id": "intro",
      "name": "Intro",
      "duration": 5,
      "tracks": {
        "time": { "keys": [ { "time": 0, "value": [200], "interpolation": "LINEAR" },
                            { "time": 5, "value": [300], "interpolation": "LINEAR" } ] },
        "camera.position": { "keys": [ { "time": 0, "value": [10, -55, 20] },
                                       { "time": 5, "value": [30, -52, 20] } ] },
        "camera.rotation": { "keys": [ { "time": 0, "value": [0, 15] } ] }
      }
    }
  ],
  "sequence": [ { "shotId": "intro" } ]
}
```

**Project**

| Field | Meaning |
| --- | --- |
| `name` | Used in output file names. |
| `shots` | The shots. |
| `sequence` | The edit: clips in order, `{ "shotId", "transition", "transitionDuration" }`. A shot not listed here can still be rendered alone. |
| `render` | Render settings the dialog starts from (see [Rendering](#rendering)). Optional. |

**Shot**

| Field | Default | Meaning |
| --- | --- | --- |
| `id` | random | Any unique string; clips refer to it. |
| `name` | "Shot" | Shown in the editor; `shot <name>` selects it in scripts. |
| `duration` | 5 | Length in seconds of output video. |
| `tracks` | `{}` | Keyframed values by track id (below). |
| `rig` | path | How the camera is placed: `mode` `PATH`, `FOLLOW` or `ORBIT`. |
| `lookAt` | off | Aims the camera at an entity or a point. |
| `shake` | steady | Seeded camera shake. |
| `notes` | "" | Free text. |

**Tracks** (by id). A track has `keys`. Each key has `time` (shot seconds) and a `value` array. Kind and size come from the id.

| Track id | Value | Effect |
| --- | --- | --- |
| `time` | `[replayTicks]` | Which replay moment each shot moment shows. Two keys with a straight line give normal or changed speed; see the recipes for slow motion, freezes and reverse. **No `time` track means the shot starts at replay tick 0.** One key pins the start and plays at normal speed from it. |
| `camera.position` | `[x, y, z]` | Camera path (PATH rig). |
| `camera.rotation` | `[yaw, pitch]` | View direction, interpolated on the sphere. If missing, the camera looks along its direction of travel. |
| `camera.roll` | `[degrees]` | Roll. |
| `camera.fov` | `[degrees]` | Field of view (default 70). |
| `lookat.weight` | `[0..1]` | Blend between the keyed rotation (0) and the look-at aim (1). Default 1. |
| `shake.intensity` | `[multiplier]` | Scales the shake preset. Default 1. |
| `orbit.angle` | `[degrees]` | Orbit position around the target (ORBIT rig). |
| `orbit.radius` | `[blocks]` | Orbit distance (default 6). |
| `orbit.height` | `[blocks]` | Height above the target's aim point (default 2). |
| `grade.exposure` | `[stops]` | 0 neutral, +1 doubles the light. |
| `grade.contrast` | `[factor]` | 1 neutral; 1.1 to 1.3 for punch. |
| `grade.saturation` | `[factor]` | 1 neutral, 0 black and white, 1.2 vivid. |
| `grade.temperature` | `[-1..1]` | Negative cool (blue), positive warm (orange). |
| `grade.tint` | `[-1..1]` | Negative green, positive magenta. |
| `fx.vignette` | `[0..1]` | Darkens the corners; 0.4 to 0.6 is cinematic. |
| `fx.grain` | `[0..1]` | Film grain; repeats exactly between renders. |
| `fx.chromatic` | `[thousandths of the width]` | Colour fringing towards the edges; 2 to 4 is subtle. |
| `fx.bloom` | `[0..1]` | Glow around light brighter than 80 %. |
| `fx.letterbox` | `[aspect]` | Black bars down to this aspect ratio: 2.39 (scope), 1.85; 0 off. |
| `world.time` | `[ticks]` | Time of day, 0 to 24000 (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight). Fractional values and keys sweep the sun. Negative: as recorded. |
| `world.weather` | `[0..2]` | 0 clear, 1 rain, 2 thunder; values in between fade. Negative: as recorded. |
| `dof.aperture` | `[f-number]` | Turns on depth of field: 1.4 very shallow, 2.8 portrait, 8 to 16 almost everything sharp. Blur also grows with a narrower FOV (a longer lens), as on a real camera. |
| `dof.focus` | `[blocks]` | Focus distance. Without it, the lens focuses on the look-at target, then the rig target, then whatever is in the middle of the frame. Key it to rack focus. |

**Keyframe** fields (all optional except `time` and `value`):

| Field | Default | Meaning |
| --- | --- | --- |
| `interpolation` | `CENTRIPETAL` for `camera.position`, `camera.rotation` and `time`; `CATMULL_ROM` for other values | How the segment *starting* at this key moves: `HOLD`, `LINEAR`, `CATMULL_ROM` (smooth, may overshoot), `CENTRIPETAL` (smooth, never overshoots or loops; on value tracks a monotone curve), `BEZIER` (uses `inValue` / `outValue` handles). |
| `easing` | `LINEAR` | Timing of the segment starting at this key: `SINE_IN`, `SINE_OUT`, `SINE_IN_OUT`, `QUAD_*`, `CUBIC_*`, `EXPO_*`, or `CUSTOM` with `easingCurve: [x1, y1, x2, y2]` (CSS cubic-bezier). |
| `label` | | Shown in the editor. |

Track options: `"constantSpeed": true` on `camera.position` moves at an even speed along the whole
path, ignoring key timing in between. `"rotationMode": "FREE"` on `camera.rotation` lets yaw run
past 360° (keys 0 → 720 spin twice); the default `SHORTEST` takes the shortest turn.

**Rig** (`"rig": { ... }`)

| Field | Default | Meaning |
| --- | --- | --- |
| `mode` | `PATH` | `PATH`: keyed position. `FOLLOW`: stays at `offset` from `targetEntity`. `ORBIT`: circles `targetEntity`. |
| `targetEntity` | none | Entity id from `entities.json`. |
| `offset` | `{x:0, y:2, z:-5}` | FOLLOW offset in the target's frame (see conventions). |
| `relative` | true | FOLLOW: turn the offset with the target's facing. false keeps it world-aligned. |
| `damping` | 0.3 | Seconds the camera lags behind the target (smooths jitter). 0 is rigid. |
| `orbitSpeed` | 20 | ORBIT: degrees per second when there is no `orbit.angle` track. Negative goes the other way. |
| `avoidCollisions` | true | Pull the camera in front of blocks between it and the target. |

FOLLOW and ORBIT aim at the target automatically. If the target id does not exist at that replay
moment, the shot falls back to its `camera.position` keys.

**LookAt** (`"lookAt": { ... }`)

| Field | Default | Meaning |
| --- | --- | --- |
| `enabled` | false | Turn aiming on. |
| `targetEntity` | none | Aim at this entity (at about eye height). |
| `point` | none | Or aim at a fixed point `{x, y, z}` (used when no entity is set). |
| `offset` | 0,0,0 | Added to the aim point. |
| `smoothing` | 0.15 | Seconds of smoothing on the aim; 0 locks on exactly. |
| `lead` | 0 | Seconds to aim ahead of a moving target (negative: behind). |

**Overlays** (`"overlays": [ ... ]` in a shot): titles and captions drawn over the video, untouched
by the grade.

| Field | Default | Meaning |
| --- | --- | --- |
| `text` | "" | The text; `\n` for more lines. |
| `image` | "" | Or a PNG, relative to `kinora/projects`. |
| `start`, `end` | 0, 3 | Shot seconds when it shows. |
| `x`, `y` | 0.5, 0.85 | Centre, as a fraction of the frame (0.85 is low, like a caption). |
| `scale` | 1 | Text: 1 is 1/18 of the frame height. Image: 1 is a quarter of the height. |
| `color` | `0xFFFFFFFF` | ARGB as an integer. |
| `fadeIn`, `fadeOut` | 0.4 | Seconds. |
| `font` | "default" | A font installed on the system, or the default sans-serif. |
| `shadow` | true | Soft drop shadow. |

**Shake** (`"shake": { ... }`): `preset` `STEADY`, `HANDHELD` (subtle), `JOG`, `EARTHQUAKE`,
`DRONE` (slow drift), or `CUSTOM` with `position` (blocks), `rotation` (degrees) and `frequency` (Hz).
`seed` picks a different but repeatable pattern. Scale it over time with the `shake.intensity` track.

**Sequence clip**: `{ "shotId": "b", "transition": "DISSOLVE", "transitionDuration": 1.0 }`.
`transition` is how this clip comes in from the previous one:

- `CUT` (default)
- `DISSOLVE`: the shots overlap by `transitionDuration` and cross-fade.
- `DIP_TO_BLACK`: fades out and in over `transitionDuration`, with no overlap.
- `WHIP_PAN`: a fast pan that overlaps the two shots.

The edit's length is the sum of the shot durations minus the overlaps.

### Shot recipes

Replace the coordinates, ids and ticks with values from your replay. All snippets go inside a shot.

**Establishing dolly**: slide sideways past the action while aiming at its middle.

```json
"duration": 6,
"tracks": {
  "time": { "keys": [ { "time": 0, "value": [20], "interpolation": "LINEAR" }, { "time": 6, "value": [140], "interpolation": "LINEAR" } ] },
  "camera.position": { "keys": [ { "time": 0, "value": [22, -55.5, 63], "easing": "SINE_IN_OUT" }, { "time": 6, "value": [61, -55.5, 63] } ] }
},
"lookAt": { "enabled": true, "point": { "x": 41.5, "y": -58, "z": 73.5 }, "smoothing": 0 }
```

**Orbit a character**, tightening as it goes:

```json
"rig": { "mode": "ORBIT", "targetEntity": 1, "orbitSpeed": 35, "damping": 0.4 },
"tracks": {
  "time": { "keys": [ { "time": 0, "value": [200], "interpolation": "LINEAR" }, { "time": 6, "value": [320], "interpolation": "LINEAR" } ] },
  "orbit.radius": { "keys": [ { "time": 0, "value": [7] }, { "time": 6, "value": [4.5] } ] },
  "orbit.height": { "keys": [ { "time": 0, "value": [3] }, { "time": 6, "value": [1.5] } ] }
}
```

**Over-the-shoulder follow**: `"rig": { "mode": "FOLLOW", "targetEntity": 1, "offset": { "x": 1.2, "y": 2.0, "z": -3.5 }, "damping": 0.4 }`.
Add `"shake": { "preset": "HANDHELD" }` for a camera-operator feel.

**Slow motion**: make replay time advance slower than shot time. 2 s of action (40 ticks) over 4 s is half speed:

```json
"time": { "keys": [ { "time": 0, "value": [320], "interpolation": "LINEAR" }, { "time": 4, "value": [360], "interpolation": "LINEAR" } ] }
```

**Speed ramp** (normal speed, then slow, then normal): give `time` several keys. The default
interpolation for `time` never overshoots, so time never runs backwards by accident:

```json
"time": { "keys": [ { "time": 0, "value": [100] }, { "time": 2, "value": [140] }, { "time": 5, "value": [155] }, { "time": 7, "value": [195] } ] }
```

**Freeze frame**: two keys with the same replay tick, `"interpolation": "HOLD"` on the first. The world
stops while the camera keeps moving (bullet time).

**Reverse**: let the `time` values decrease (`[300]` then `[240]`). Kinora renders such frames in
replay order and puts them back in output order, so this costs no extra seeking.

**Crane up and reveal**: rise and pull back while the look-at stays on the subject:

```json
"camera.position": { "keys": [ { "time": 0, "value": [44, -58, 8], "easing": "CUBIC_IN_OUT" }, { "time": 6, "value": [44, -42, -6] } ] }
```

with `"lookAt": { "enabled": true, "targetEntity": 1 }`.

**Push in**: move toward the subject and narrow the FOV from 70 to 50 for a long-lens feel.

**Dolly zoom (vertigo)**: move away from the subject while the FOV narrows in step, so the subject
stays the same size while the background stretches. Pair `camera.position` (backing away) with
`camera.fov` keys from 80 to 30, plus a look-at.

**Top-down shot**: `camera.rotation` `[0, 90]` (straight down) from high above. For a slow spin, use
yaw keys `[0, 90]` → `[90, 90]`.

**Warm cinematic look**: `grade.temperature` 0.3, `grade.contrast` 1.15, `fx.vignette` 0.5,
`fx.grain` 0.3, `fx.letterbox` 2.39. A key on each at time 0 is enough. See
[`docs/examples/graded.kinoraproj`](docs/examples/graded.kinoraproj), which also fades to black and white.

**Sunset timelapse**: `world.time` from 11500 to 13200 across the shot, plus `world.weather` 0 → 1
for rain rolling in. See [`docs/examples/sunset.kinoraproj`](docs/examples/sunset.kinoraproj).

**360° video**: any shot rendered with `renderset {"projection":"EQUIRECTANGULAR"}` at a 2:1 size
(for example 4096 x 2048). The camera's heading becomes the front of the panorama; its pitch and
roll are ignored, because viewers look around themselves. For YouTube, run the file through
Google's Spatial Media Metadata Injector so it is recognised as 360°.

**Shallow focus portrait**: `camera.fov` 30 to 40 and `dof.aperture` 1.4 to 2 with an orbit or a
follow rig. Focus follows the target. **Rack focus**: hold `dof.focus` on a near subject, then ease
it to a far one. See [`docs/examples/depth-of-field.kinoraproj`](docs/examples/depth-of-field.kinoraproj).

**Making it look good**

- Use ease in and out (`SINE_IN_OUT`, `CUBIC_IN_OUT`) on the first key of a move. Linear starts and stops look robotic.
- Keep pitch modest (5 to 20 degrees) and the horizon level (no roll) unless the shot is meant to feel dramatic.
- Shots of 3 to 8 seconds work well. Cut or dissolve between them instead of making one long move.
- For a film look, render at 24 fps with motion blur (`blur` 8 to 16 samples, 180° shutter).
- Keep the camera within the area the recorded player had loaded. Chunks the player never loaded do
  not exist in the replay, and show as empty space.
- Watch for walls between the camera and the subject. FOLLOW and ORBIT pull in front of them; PATH
  shots do not.
- To check framing quickly, place the camera with `camera x y z yaw pitch` and take a `screenshot`
  before writing keys.

### Worked example

[`docs/examples/test-arena-film.kinoraproj`](docs/examples/test-arena-film.kinoraproj) is a three-shot
film made from a hand-written project file:

1. a 6 s dolly past a line of mobs with a fixed look-at point;
2. a 6 s orbit of the recorded player (entity 1) whose radius and height shrink, joined to shot 1
   with a 1 s dissolve;
3. a 4 s half-speed handheld follow, joined with a dip to black.

[`tools/dev/scripts/film.txt`](tools/dev/scripts/film.txt) renders it to a 15 s, 900-frame 1080p60
MP4 in about 30 s.

[`docs/examples/time-tricks.kinoraproj`](docs/examples/time-tricks.kinoraproj) shows three time
remaps:

- a reversed follow shot;
- bullet time: the world frozen with a `HOLD` key while an orbit keeps moving;
- a speed ramp.

Render it with [`tools/dev/scripts/time-tricks.txt`](tools/dev/scripts/time-tricks.txt).

### Script commands

Scripts are plain text, one command per line; `#` starts a comment. They run inside the dev client
(development builds only), with input going through the game's own window callbacks.

Run a script with `tools\dev\kinora-run.ps1 -Script <file>`, or copy it to
`run/kinora/dev/script.txt` while the client is running (it is picked up within a second). Results:

- `run/kinora/dev/script.log`: every step and any `FAIL` lines
- `run/kinora/dev/script.done`: `PASS` or `FAIL <n>`
- `run/kinora/dev/screenshots/`

| Command | Does |
| --- | --- |
| `open <file>` | Opens a replay (name relative to `kinora/replays`, an absolute path, or `latest` for the newest). |
| `waitfor <condition> [seconds]` | Waits until the condition holds (default 60 s). |
| `expect <condition>` | Fails the script if the condition does not hold now. Conditions: `replay` (loaded), `screen <Name>`, `noscreen`, `playing`, `paused`, `rendered` (no render running), `moved <blocks>` (camera moved since `mark`). |
| `wait <seconds>` | Waits. |
| `seek <seconds>` | Moves replay time (seconds from the replay's start). |
| `entities [file]` | Writes the entities at the current moment to `run/kinora/dev/<file>` (default `entities.json`). |
| `project load <file>` | Replaces the open replay's project with a `.kinoraproj` file and selects its first shot. Undoable in the editor. A relative path is relative to `kinora/dev` (the repo's examples are `../../../docs/examples/...` from the dev client). |
| `project export <file>` | Writes the project as JSON (relative paths go to `run/kinora/dev/`). |
| `shot <name>` | Selects a shot by name. |
| `names on\|off` | Shows or hides player names (as V does). |
| `pathcheck [fix]` | Checks the selected shot every 0.05 s: where its camera is inside a block (`B`) or a block hides its subject (`H`). `fix` first moves a keyed path out of blocks. Seek near the shot first, so its chunks are loaded. |
| `clip <start> <end>` | Exports seconds start..end of the open replay to `kinora/replays/script_clip.kinora`. |
| `photo [width]` | A still of the current view (default 3840 wide), then `waitfor rendered`. |
| `renderset {json}` | Overrides render settings for the following renders, for example `{"projection":"EQUIRECTANGULAR","audio":false}` or `{"musicPath":"D:/music/theme.mp3","musicVolume":0.6}`. Any field in [render settings](#rendering) can be set. |
| `render shot\|sequence png\|png16\|exr\|video [w h fps [blur [codec container]]]` | Renders the selected shot or the whole edit. Example: `render sequence video 1920 1080 24 16 libx264 mp4`. The codec can be `libx264`, `libx265`, `h264_nvenc` / `hevc_nvenc` (NVIDIA GPU), `prores_ks` (container `mov`), `libvpx-vp9` (`webm`) or `ffv1` (`mkv`). A `renderset` with `pixelFormat` or `quality` wins over the codec's defaults. Follow it with `waitfor rendered <seconds>`. |
| `camera x y z yaw pitch` | Places the free camera, for screenshots or for keying in the editor. |
| `screenshot <name>` | Saves `run/kinora/dev/screenshots/<name>.png`. |
| `key <combo>` | Presses a key: `space`, `e`, `ctrl+z`, `shift+left`, `f6`, ... |
| `down <key>` / `up <key>` | Holds or releases a key. |
| `type <text>` | Types text. |
| `click x y [button]`, `mousedown x y [button]`, `mouseup [button]`, `move x y`, `drag x1 y1 x2 y2`, `scroll x y n` | Mouse input. Coordinates are GUI-scaled, or percentages such as `35% 40%`. |
| `button <label>` | Clicks the button whose label matches (an exact match wins). |
| `cycle <label> until <text>` | Clicks a cycling button until its label contains the text. |
| `mark`, `state` | Remembers the camera position; logs the screen, game time and time of day, replay time, editor and camera. |
| `property <name> [value]` | Sets a system property (`kinora.dev.renderTrace` logs every captured frame). |
| `samerenders [pixels]` | Checks that the last two renders match file by file; up to `pixels` differing pixels per image pass. |
| `log <text>`, `quit` | Writes to the log; closes the game. |

More scripts are in [`tools/dev/scripts/`](tools/dev/scripts/): `editor-smoke.txt` (the client
smoke test), `discover.txt`, `film.txt`, `time-tricks.txt`, `render-video.txt`, `render-determinism.txt`,
`render-resume.txt`, `render-dialog.txt`, `render-look.txt`, `graded.txt`, `sunset.txt`, `views.txt` and `dof.txt`.

---

## Rendering

From the editor, **Render** opens the dialog. It has:

- a preset;
- what to render: the selected shot or the whole edit;
- size and frame rate (23.976 to 120 fps);
- format: H.264 / H.265 / AV1 MP4, H.264 / H.265 on the GPU, ProRes 422 HQ or 4444 (with alpha) MOV,
  lossless FFV1 MKV, VP9 WebM, or a PNG (8 or 16-bit) or OpenEXR sequence;
- quality (CRF, CQ for GPU encoders, or the ProRes profile);
- motion blur samples, supersampling, view (360°, stereo), sound;
- **Extra**: a depth pass and / or a transparent sky;
- the file name.

**Render now** starts the render at once. **Add to queue** saves the job for later, and **Queue**
lists jobs so you can resume a stopped one or open its folder.

- **Video needs FFmpeg.** Kinora looks in the path set in Kinora's settings, then `kinora/tools/`,
  then the PATH. The dialog says which FFmpeg it found and marks formats that FFmpeg cannot encode.
  The gyan.dev "essentials" build has everything except AV1. Image sequences need nothing extra.
- **Esc stops a render.** It resumes from the queue where it stopped: video continues as a new part
  and the parts are joined without re-encoding at the end.
- **Exact timing.** Frame *i* is at exactly *i* x den / num seconds. A render waits until the chunks
  around the camera are built before taking each frame. While rendering there is no frame cap and no
  vsync, sound is muted, and the window shows the frames scaled down with a progress bar.
- **Same input, same frames.** The level clock, animated textures, entity animation and snapshot
  restores are tied to replay time, and a render always starts from a freshly restored world, so
  rendering the same shot twice gives the same frames. The exception is a rare single pixel where
  the water of two chunk sections meets (up to 2 pixels in a few frames on OpenGL, 4 on Vulkan).
  With Iris, Kinora drives the shader's clock (`frameTimeCounter`, `frameCounter`) from the video's
  time. Shader packs that use temporal anti-aliasing still vary very slightly between runs.
- **360° MP4 / MOV** files carry spherical metadata (equirectangular), so YouTube and 360° players
  show them as 360° video without extra tools.
- **OpenGL and Vulkan** both work. To use Vulkan with NeoForge, turn off NeoForge's early loading
  window (`earlyWindowControl = false` in `config/fml.toml`): it is OpenGL-only and stops Vulkan from
  starting.
- **Sodium and Iris** are supported: Kinora asks Sodium when its chunk builder is done. Resource
  packs need no special handling.
- **Speed** on the dev machine, flat test world: 1080p H.264 at about 45 fps on the CPU (x264 is the
  limit) and about 85 fps on the GPU; PNG at about 37 fps; 720p with Complementary shaders at about
  40 fps.
- **GPU encoders** ("H.264 on the NVIDIA GPU (fast)", and AMD / Intel versions) appear only when they
  work on the machine: Kinora tries each on one small frame. They take about a third of the time of
  x264 for slightly bigger files at the same look.

**Deep output and passes.**
- **16-bit PNG** keeps the precision that motion blur and dissolves create (8-bit frames mixed in 16
  bits): smooth gradients for grading later.
- **OpenEXR** frames are linear light, half float, ZIP-compressed, premultiplied when there is alpha.
- **Depth pass** (`"passes": ["COLOR", "DEPTH"]`): beside the output, a `<name>_depth` folder with one
  EXR per frame. Each pixel holds its distance from the camera in blocks along the view axis, in one
  float channel named Y so every EXR reader opens it; the sky is 1e10. With motion blur it is the depth
  of the middle sample. Normal view only.
- **Transparent sky** (`"transparentSky": true`): everything not drawn (the sky, the void) becomes
  transparent, with soft edges from supersampling and motion blur. Clouds are geometry and stay; turn
  them off in the video settings for a clean cut-out. It needs a format with alpha: PNG, EXR,
  ProRes 4444 (`"pixelFormat": "yuva444p10le"`), FFV1 (`rgba`) or VP9 (`yuva420p`).

**Sound.** Renders include the replay's sounds as the camera hears them: Minecraft's distance
falloff (16 blocks for most sounds), panned by direction, with your sound-category volume settings
(master excepted).
- Slow motion lowers the pitch like a slowed tape (`pitchMode` `FOLLOW`). Use `PRESERVE` to keep
  the pitch, or `MUTE` for sound only at normal speed.
- Below 1/8 and above 8x speed, sounds are left out. Freezes are silent.
- Sounds that play on and change (rolling minecarts, bees, elytra wind, biome ambience) follow
  their source tick by tick, looping as the game loops them. The game's own music is left out (it
  is picked at random); add yours with `musicPath`.
- `musicPath` (any format FFmpeg reads) is mixed in at `musicVolume`.
- Video gets AAC (MP4), Opus (WebM), FLAC (MKV) or 24-bit PCM (ProRes MOV).
- Image sequences get an `audio.wav` beside the frames.

**Other render settings** (JSON field names, for `renderset` or a project's `render`):

| Field | Meaning |
| --- | --- |
| `projection` | `NORMAL`, `EQUIRECTANGULAR` (360°), `CUBEMAP` (3 x 2 grid: front, right, back / left, up, down), `STEREO_SIDE_BY_SIDE` or `STEREO_TOP_BOTTOM` (half resolution per eye). |
| `stereoEyeDistance` | Blocks between the eyes (default 0.064). Larger exaggerates depth for distant scenes. |
| `audio`, `pitchMode`, `gameVolume`, `musicPath`, `musicVolume`, `audioBitrateKbps` | Sound (above). |
| `motionBlurSamples`, `shutterAngle` | Motion blur: renders per frame, and the shutter in degrees (180 is film-like). |
| `supersampling` | 1 to 4: renders larger and scales down for smoother edges. |
| `output` | `VIDEO`, `PNG_SEQUENCE`, `PNG16_SEQUENCE` or `EXR_SEQUENCE`. |
| `tiles` | Renders each frame as tiles x tiles pieces and joins them: 0 (default) tiles only frames wider than 16384 (or the GPU's limit), 1 never, 2-8 always. Up to about 22000 x 22000 per frame. Joined tiles match an untiled render to within rounding (PSNR ~70 dB). Not with the depth pass; depth of field is computed per tile. |
| `videoCodec`, `container`, `pixelFormat`, `quality`, `extraArgs` | FFmpeg encoder, e.g. `libx264` / `mp4` / `yuv420p` / 18, `h264_nvenc` / `mp4` / `yuv420p` / 21, `prores_ks` / `mov` / `yuva444p10le` / 4. |
| `passes`, `transparentSky` | Extra images (above). |

After each render, the queue shows a short **report**: where the time went (capturing, waiting for
chunks, waiting for the encoder or the GPU copy, seeking), how long the encoder thread spent on each
step (effects, grading, titles, mixing, output), and what would make it faster.

Render jobs are JSON files in `kinora/queue/`. Outputs go to `kinora/renders/` unless the settings name
another folder. `{project}`, `{shot}` and `{date}` in the file name are filled in.

---

## For mod developers: the API

Other mods become **replay-aware** through `kinora-api` (LGPL-3.0):
- record their client-side state into a data track and get it back at the right replay moment;
- use a reproducible random source and the render clock;
- let users hide their HUD in renders;
- listen to recording, replay and render events.

Read [`docs/api.md`](docs/api.md), which includes the replay-aware mod checklist. [`sample-mod/`](sample-mod/)
is a complete example: a key throws sparkles, and replays and renders show them at the right moment.

## Building and developing

| Tool | Version |
| --- | --- |
| JDK | 25 (and 21, which NeoForm's tooling needs for `runClient`) |
| Gradle | 9.2.1 via the wrapper |
| NeoForge | 26.2.0.88 (pinned in `gradle.properties`) |

```bash
./gradlew build
```

builds and runs all tests (`kinora-core` has the format, timeline, camera, project and render tests).

**The dev client:**

| Command | Does |
| --- | --- |
| `tools\dev\mc-devclient.ps1` | Starts the client and joins the test world `run/saves/Test Arena`. |
| `tools\dev\mc-devclient.ps1 -NoJoin -GradleArgs @("-Preplay=<abs path>")` | Opens a replay straight from the title screen. |
| `tools\dev\kinora-run.ps1 -Script <file>` | Runs a dev script and prints its log (see [Script commands](#script-commands)). |
| `tools\dev\kinora-run.ps1 -Script <file> -Join` | The same, joining the test world first (for recording tests). |
| `tools\dev\mc-devserver.ps1` (`-Stop`) | Starts (stops) a local dedicated server on port 25566 for multiplayer tests: flat creative world, offline mode, the dev player is operator. |
| `tools\dev\kinora-run.ps1 -Script <file> -Server localhost:25566` | Runs a script on that server (`mp-record.txt`, then `mp-play.txt` without `-Server`). |
| `tools\dev\kinora-run.ps1 -Script <file> -Vulkan` | Runs on the Vulkan backend (turns NeoForge's early window off for the run). |
| `tools\dev\kinora-run.ps1 -Script <file> -Shaders` | The same with Sodium, Iris and the shader pack from `run/compat`, `run/shaderpacks` and `run/config/iris.properties`. |
| `.\gradlew.bat :kinora-inspector:run --args="validate <file.kinora>"` | Inspects a recording without the game: `info`, `index`, `stats`, `records`, `markers`, `snapshots`, `validate`, `recover`, `thumbnail`, and `clip FILE START END OUT`. |
| `python tools/dev/check-lang.py` | Lists translation keys the code uses but `en_us.json` lacks. |

Notes for development:

- Prefer dev scripts to `tools/dev/mc-input.ps1`. Scripts send nothing to the desktop.
- Dev runs with Iris need `-PnoGlValidation` until [IrisShaders/Iris#3304](https://github.com/IrisShaders/Iris/issues/3304)
  is fixed. Iris breaks a draw check that only runs in development; players are not affected.
  `kinora-run.ps1 -Shaders` adds it.
- When the game shows "Mods loaded with warnings" (Sodium and Iris use deprecated metadata), dev
  scripts continue past it. They never continue past errors.
- More on the dev tools: [`tools/dev/README.md`](tools/dev/README.md).

**Branches.** `main` targets the newest Minecraft version. Before `main` moves to a new Minecraft
version, the old one gets a branch named after it (`26.2`, ...) for fixes; see
[`UPGRADING.md`](UPGRADING.md).

## Repository layout

| Path | What |
| --- | --- |
| `kinora-core/` | Plain Java, no Minecraft: `.kinora` format, recording writer, timeline and camera maths, project model, render planning, frame assembly, PNG writer, FFmpeg pipe. |
| `kinora-api/` | The API other mods compile against (LGPL-3.0). |
| `sample-mod/` | "Sparkles": how a mod integrates with the API. |
| `kinora-mc/` | The NeoForge mod: capture, playback, camera, editor, renderer, UI, mixins. |
| `tools/inspector/` | `kinora-inspect`, a command-line tool for `.kinora` files. |
| `tools/dev/` | Dev client scripts and in-game test scripts. |
| `tools/branding/` | `KinoraIcon.java`, which draws the mod icon (`kinora-mc/src/main/resources/kinora.png`). |
| `docs/` | Design, file format, internals, API guide, mixin list, status, examples. |
| `UPGRADING.md`, `CHANGELOG.md` | Moving to a new Minecraft version; what changed. |
| `CONTRIBUTING.md`, `SECURITY.md` | How to contribute; how to report security problems. |
| `run/` | The dev client's game folder: replays, renders, FFmpeg, compat mods, test world. |

## License

Kinora Replay is under the **Kinora Replay License** ([`LICENSE`](LICENSE)): free to use, also for
videos you earn money with, and free to read and build for yourself, but **not to repost**. Builds
are published only on GitHub, Modrinth and CurseForge; modpacks refer to the Modrinth or CurseForge
project instead of carrying a copy. Contributions are welcome as pull requests; see
[`CONTRIBUTING.md`](CONTRIBUTING.md). Security problems: [`SECURITY.md`](SECURITY.md).

The API (`kinora-api`) is LGPL-3.0-only ([`LICENSE-API`](LICENSE-API)), so other mods may use it
freely, and the sample mod is MIT ([`sample-mod/LICENSE`](sample-mod/LICENSE)). Third-party notices
are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
