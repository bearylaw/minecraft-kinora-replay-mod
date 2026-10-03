# Upgrading Kinora to a new Minecraft version

Kinora is split so that a Minecraft update touches as little as possible:

| Module | Minecraft-dependent? |
| --- | --- |
| `kinora-core` (format, timeline, camera maths, render planning, encoder, audio, projects) | No. Untouched by updates. |
| `kinora-api` | No. Its surface stays stable for other mods. |
| `kinora-mc` | Yes, and the dependence is concentrated in the places listed below. |

## Steps

0. **Branch**: if the new Minecraft version needs code changes, first make sure a branch named after
   the current version exists (`git branch 26.2 main`, pushed), so the old version can still get
   fixes. `main` then moves on. If the mod runs unchanged, widen `minecraft_version_range` instead
   and release; no branch is needed. Fixes for an old version are made on `main` and copied over
   with `git cherry-pick`; new features go to the newest version only.
1. **Versions**: in `gradle.properties`, update `minecraft_version`, `minecraft_version_range`,
   `neo_version` (and `loader_version_range` if NeoForge requires it). Update ModDevGradle in
   `kinora-mc/build.gradle` and `sample-mod/build.gradle` if needed.
2. **Build** (`./gradlew build`) and fix compile errors. Renames are the usual cause; the 26.x renames
   Kinora already went through are listed in [`docs/internals/`](docs/internals/).
3. **Decompiled sources**: refresh `build/mcsrc` / `build/neosrc` and re-read the places Kinora
   depends on:
   - `docs/internals/networking.md`: the Netty pipeline handler names, `Connection.configurePacketHandler`,
     protocol states and packet ids. **Recordings store raw packet frames tagged with the protocol
     version**, so new versions read old recordings only as far as the packets are compatible. Playing
     older recordings would need a translation layer in `adapter/` (not built).
   - `docs/internals/timing-camera.md`: `Minecraft.runTick`, `DeltaTracker.Timer`, `Camera.update`.
   - `docs/internals/rendering.md`: `GameRenderer.render` order, `RenderTarget`, readback, chunk
     readiness, the frame-rate limiter, sprite animation.
   - `docs/mixins.md`: every injection point, with what to check.
4. **Packets the playback filter knows** (`playback/PlaybackFilter.java`): new local-player or UI
   packets may need adding to the drop list; renamed records need their constructors updated.
5. **State model** (`capture/StateModel.java`): new world-state packets (anything that sets lasting
   client state) must be added, or snapshots will miss them. Seeking then shows stale state; playing
   from the start stays correct.
6. **Test in the dev client** with the scripts in `tools/dev/scripts/`:
   - `editor-smoke.txt`
   - `record-events.txt` (with `-Join`)
   - `render-determinism.txt`: the two renders must be bit-identical
   - `render-video.txt`
   - `film.txt`
   - `sample-record.txt` then `sample-play.txt` (the API)
7. **Compatibility mods**: Sodium and Iris builds for the new version; re-run `render-look.txt` with
   `-Shaders`. Update the class names in `TerrainReadiness` and the Iris compat mixins if they moved.
8. Update the version numbers in the README, `docs/STATUS.md` and the CHANGELOG.
