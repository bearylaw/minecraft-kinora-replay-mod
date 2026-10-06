![Kinora Replay](https://raw.githubusercontent.com/bearylaw/minecraft-kinora-replay-mod/main/docs/curseforge/images/banner.png)

**Kinora Replay** records what you play and lets you film it again like a movie director. Open
a recording later, fly a camera through it, place a few camera points, and Kinora renders a
smooth, perfectly timed video with no lag and no dropped frames, even if your game stutters.

> **⚠️ Read this first: making videos needs one free extra program, FFmpeg.**
> Kinora does not include it. Without it you can still record, watch, edit and save image
> sequences, but not MP4 videos. Setting it up takes about 2 minutes:
> **[jump to the FFmpeg setup](#ffmpeg)**.

---

## How it works

![Record, film, render](https://raw.githubusercontent.com/bearylaw/minecraft-kinora-replay-mod/main/docs/curseforge/images/how-it-works.png)

1. **Record.** Press **F6** while you play. Press **F6** again to stop.
2. **Film.** On the title screen, click **Replays** (the film icon) and open your recording. Press
   **E** to open the camera editor, fly to a good spot and press **I** to place a camera point.
   Move, place another one, and the camera glides between them.
3. **Render.** Click **Render**, choose a size and format, and click **Render now**. The video is
   saved in your Minecraft folder under `kinora/renders`.

---

## Installation

1. **Minecraft 26.2 with NeoForge 26.2.0.88 or newer.** The CurseForge app sets this up for you.
2. **Kinora Replay.** Install it like any other mod. It is client only: servers do not need it.
3. **FFmpeg, for video.** See the next section.

<a id="ffmpeg"></a>

## Setting up FFmpeg (for video)

FFmpeg is a free program that turns Kinora's frames into a video file. You only do this once
per Minecraft profile.

![FFmpeg setup in four steps](https://raw.githubusercontent.com/bearylaw/minecraft-kinora-replay-mod/main/docs/curseforge/images/ffmpeg-setup.png)

### Windows

1. **Download.** Go to **[gyan.dev/ffmpeg/builds](https://www.gyan.dev/ffmpeg/builds/)**. Under
   *release builds*, click **ffmpeg-release-essentials.zip**.
2. **Unzip.** Right-click the downloaded `.zip` and choose **Extract All**. Inside, open the
   `ffmpeg-…-essentials_build` folder, then the **`bin`** folder. You only need **`ffmpeg.exe`**.
3. **Open your Minecraft folder.** Start Minecraft with Kinora installed once, then close it.
   - **CurseForge app:** right-click your profile → **Open Folder**.
   - **Prism Launcher:** right-click the instance → **Folder**.
   - **Official launcher:** Installations → hover your installation → the folder icon.
4. **Copy `ffmpeg.exe` into `kinora/tools`.** In that folder, open **`kinora`**, then
   **`tools`**, and put `ffmpeg.exe` there. If there is no `tools` folder yet, just create it.
   It should end up like this:

   ```
   <your profile folder>
   ├─ mods
   └─ kinora
      └─ tools
         └─ ffmpeg.exe
   ```

5. **Check.** In the game, open a replay, press **E**, click **Render**. The window now says
   **FFmpeg: …kinora/tools/ffmpeg.exe**, and the video formats (MP4 and others) can be picked.

### macOS and Linux

Install FFmpeg with your package manager (`brew install ffmpeg` on macOS,
`sudo apt install ffmpeg` on Ubuntu / Debian). Kinora finds it on its own.

### Other ways

- Already have FFmpeg somewhere else? Set its path under **Kinora's settings → FFmpeg path**
  (or `ffmpegPath` in `config/kinora-client.toml`).
- An FFmpeg that is on your system PATH is found automatically too.
- Copying the whole `bin` folder into `kinora/tools` (so you get `kinora/tools/bin/ffmpeg.exe`)
  also works.

---

## Using Kinora

### Recording

- **F6**: start / stop recording
- **F7**: add a marker at an important moment
- **F8**: save the last few minutes (the replay buffer, turn it on in the settings)

Kinora also adds markers by itself for deaths, kills, explosions, advancements and more, so you
can jump straight to the action.

### Watching a replay

A replay opens paused, with the camera behind you.

- **K**: play / pause, **J / L**: back / forward 5 seconds
- **[ / ]**: slower / faster
- **N / M**: previous / next marker
- **F**: free camera (fly with WASD, Space and Shift)
- **G**: follow the player or mob you are looking at, **O**: orbit around it
- **B**: take a 4K photo
- **E**: open the camera editor, **Esc**: replay menu

All replay keys can be changed under **Esc → Replay keys**.

![Watching a replay with the free camera](https://raw.githubusercontent.com/bearylaw/minecraft-kinora-replay-mod/main/docs/curseforge/images/replay.jpg)

### The camera editor

The first time you open it, a short guide explains everything (the **?** button shows it again).

- **New Shot** starts a 5-second shot at the current moment.
- Hold the **right mouse button** and use **WASD** to fly. Press **I** to place a camera point.
- **Space** plays the shot. The camera's path is drawn in the world as a blue line.
- **Templates** add ready-made moves: orbit, dolly zoom, crane up, fly-through, reveal and more.
- **Ctrl+K** finds any action by name. **Ctrl+Z** undoes. Your project saves itself.

![The camera editor with its timeline](https://raw.githubusercontent.com/bearylaw/minecraft-kinora-replay-mod/main/docs/curseforge/images/editor.jpg)

![A camera path drawn in the world](https://raw.githubusercontent.com/bearylaw/minecraft-kinora-replay-mod/main/docs/curseforge/images/camera-path.jpg)

### Rendering

Click **Render** in the editor. Pick the size (up to 4K and beyond), frame rate (up to 120 fps)
and format, then **Render now**, or **Add to queue** to render later. **Esc** stops a render;
you can resume it from the queue.

![The render window](https://raw.githubusercontent.com/bearylaw/minecraft-kinora-replay-mod/main/docs/curseforge/images/render-dialog.jpg)

---

## Features

- 🎬 **Cinematic camera**: keyframed paths, orbits, follow cams, look-at, camera shake, speed
  ramps, slow motion, freeze frames and reverse
- 🎞️ **Perfect renders**: every frame is rendered at its exact time, so videos are smooth no
  matter how fast your PC is; the same project gives the same frames every time
- 🎨 **Looks**: color grading, exposure, vignette, film grain, bloom, letterbox, depth of field
  with autofocus, motion blur
- 🌅 **World control**: change the time of day and weather in your shots
- 🔊 **Sound**: renders include the game sounds as the camera hears them, plus optional music
- 🌐 **360° and 3D** video, ready for YouTube
- ✂️ **Live cut**: switch between up to nine cameras while the replay plays, like a live TV
  director
- 📷 **Photo mode**: 4K, 8K or 16K stills from any moment
- 📦 **Share**: pack a replay and its project into one file for a friend
- 🎥 **Pro formats**: MP4 (H.264, H.265, AV1), GPU encoding (NVIDIA / AMD / Intel), ProRes, WebM,
  PNG and OpenEXR sequences, depth pass, transparent sky

![Far terrain in a replay](https://raw.githubusercontent.com/bearylaw/minecraft-kinora-replay-mod/main/docs/curseforge/images/far-terrain.jpg)

---

## Compatibility

- **Sodium** and **Iris** (shader packs): supported, no setup needed
- **Distant Horizons**: far terrain shows up in replays
- **Multiplayer**: record on any server; servers do not need Kinora
- **Vulkan**: works; set `earlyWindowControl = false` in `config/fml.toml`

---

## FAQ

**The render window says "FFmpeg not found".**
`ffmpeg.exe` is not in the right place. It must be directly in `kinora/tools` inside your
*profile* folder (the one that also has `mods` in it), not in the zip's folder. See
[the FFmpeg setup](#ffmpeg).

**Some formats are greyed out.**
Your FFmpeg does not support them. The "essentials" build has everything except AV1.

**Do I need FFmpeg for screenshots or PNG sequences?**
No. Photos and PNG / OpenEXR image sequences work without it.

**Where are my recordings and videos?**
In your profile folder: recordings in `kinora/replays`, videos in `kinora/renders`.

**Can I use Kinora in my modpack?**
Yes, as long as the pack downloads it from this CurseForge page (do not put the file itself in
your pack).

**Can I use it for videos I make money with?**
Yes. What you make with Kinora is yours.

---

## Links

- **Source code and bug reports:** [GitHub](https://github.com/bearylaw/minecraft-kinora-replay-mod)
  ([report a bug](https://github.com/bearylaw/minecraft-kinora-replay-mod/issues))
- **License:** free to use and to make videos with, including monetised ones. Please don't
  re-upload the mod; see the [license](https://github.com/bearylaw/minecraft-kinora-replay-mod/blob/main/LICENSE).

*Kinora Replay is not an official Minecraft product and is not approved by or associated with
Mojang or Microsoft.*
