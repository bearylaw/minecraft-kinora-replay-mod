# The Kinora API: making a mod replay-aware

Kinora replays a recorded session by feeding the recorded network packets back into the client.
Anything your mod draws because of **server data** (blocks, entities, packets) replays by itself.
What needs the API is **client-side state**: effects started by a key press, HUD state, animations
driven by the wall clock, randomness. Without the API those either vanish from replays or differ
from one render to the next.

The API is a small LGPL-3.0 library, `kinora-api`, package `dev.kinora.api`. The Kinora mod carries
it at runtime. A complete, working example is [`sample-mod/`](../sample-mod/) ("Sparkles").

## Setting up

```groovy
dependencies {
    compileOnly project(':kinora-api')   // or the published kinora-api jar
}
```

In `neoforge.mods.toml`, declare Kinora as optional:

```toml
[[dependencies.yourmod]]
    modId="kinora"
    type="optional"
    versionRange="[0.1,)"
    ordering="NONE"
    side="CLIENT"
```

When Kinora is **not** installed the API classes do not exist. Keep every Kinora call in one class
and touch it only after `ModList.get().isLoaded("kinora")`, like the sample's `KinoraIntegration`.

When Kinora is installed, every call is safe at any time:
- Calls made before Kinora has started are held and bound when it starts, so mod load order does not matter.
- Calls made while nothing records or replays are cheap no-ops or return "inactive".

## What is there

| Call | Use |
| --- | --- |
| `Kinora.isLoaded()`, `isRecording()`, `isReplaying()`, `isRendering()` | State checks. While replaying, do not react to input as if the player were playing: the "player" is a camera. |
| `Kinora.replayTime()` | `ReplayTime(tick, partialTick)` of the replay, or null. Use it instead of the system clock for anything shown in replays. |
| `Kinora.renderClock()` | During offline renders: `RenderClock(frame, subFrame, subFrames, outputSeconds, framesPerSecond)`. Drive animations from `outputSeconds` while rendering. |
| `Kinora.registerTrack(id, version, handler)` | A data track: write bytes while recording, get them back at the right replay moment (below). |
| `Kinora.random(salt)` | A random source that repeats exactly for the same replay moment, so renders are reproducible. Outside replays, an ordinary random source. |
| `Kinora.registerHideable(id, langKey)`, `Kinora.isHidden(id)` | Declares a HUD element or effect users can hide in replays and renders (Esc → "Mod elements..."). Check `isHidden` before drawing it. |
| `Kinora.addListener(KinoraListener)` | Callbacks: recording start/stop, replay start/stop, seek, render start/finish, frame begin/end, sub-frame. |

## Data tracks

```java
DataTrack track = Kinora.registerTrack("yourmod:sparkles", 1, new TrackHandler() {
    @Override public void onData(byte[] data, int version, ReplayTime time) {
        // Called in replays at the moment the data was written. Re-create the effect from it.
    }
    @Override public byte[] captureState() {
        // Called at every snapshot while recording: your complete state now (or null).
        return ...;
    }
    @Override public void restoreState(byte[] state, int version) {
        // Called after a seek, with the state from the nearest snapshot before it.
    }
    @Override public void reset() {
        // Called before restoring, and on seeks: forget everything.
    }
});

// While playing (recording or not):
track.write(bytes);   // returns false and does nothing when not recording
```

- Write on the game thread. Data is timestamped with the current recording tick.
- The `version` you register is stored in each recording and passed back to `onData` / `restoreState`,
  so newer versions of your mod can still read old replays.
- A replay keeps its own table of track ids, so the order in which mods register does not matter.
- Keep records small: they are stored with every recording. A few bytes per event is typical.

## The replay-aware mod checklist

1. **Never use `System.nanoTime()` / `currentTimeMillis()` for visuals.** In replays use
   `Kinora.replayTime()`, and during renders `Kinora.renderClock().outputSeconds()`. Otherwise
   animations run at render speed, not video speed.
2. **Use `Kinora.random(salt)` for visual randomness** (particles, jitter). Seed per use, e.g. with
   an entity id, so two renders of the same shot match.
3. **Do not create client-side state from input during replays.** Check `Kinora.isReplaying()`. The
   camera operator's key presses are not the recorded player's.
4. **Record client-only state with a data track**, and implement `captureState` / `restoreState` so
   seeking (jumping back, scrubbing) shows the right state.
5. **Make effects a function of their inputs.** Store the inputs (position, count, seed), not the
   result, and rebuild the effect in `onData`.
6. **Register HUD elements as hideables** and check `Kinora.isHidden` before drawing them.
7. **Expect time to jump.** `onSeek` and `reset()` happen often in the editor; never assume ticks
   arrive one by one.
8. **Tick-based animation, partial-tick interpolation.** Advance state on the tick, and interpolate
   with the partial tick the game passes to rendering. Kinora controls both exactly during renders,
   including slow motion and motion-blur sub-frames.

## Events during renders

- Frames are rendered in replay-time order, not output order, for reverse and freeze time remaps and
  for dissolves.
- `onFrameBegin(clock)` comes before the first image of an output frame. `onSubFrame(clock, time)`
  comes before every image: motion-blur samples, 360° cube faces, stereo eyes and dissolve layers.
  `onFrameEnd(clock)` comes after the last image of the primary layer.
- `onRenderStart()` and `onRenderFinish(completed)` bracket a render. `completed` is false when the
  render was stopped or failed.
