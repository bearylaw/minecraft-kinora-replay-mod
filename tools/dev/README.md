# Driving Minecraft from a terminal

PowerShell scripts that let a person — or an agent session — see and control a running Minecraft
client, so visual bugs can be diagnosed and fixed without a human relaying screenshots. Nothing has
to be installed; the scripts use built-in Windows APIs.

They are the same files in every mod built from
[minecraft-mod-starter-template](https://github.com/bearylaw/minecraft-mod-starter-template). The mod id and version
are read from `gradle.properties` (see `mc-common.ps1`), so a fix made in one mod copies to the others
unchanged.

Reproducing a visual bug by hand is not repeatable. Start here.

There are two ways to run the game with the mod in it:

| | The dev client | A launcher installation |
| --- | --- | --- |
| Script | `mc-devclient.ps1` | `mc-restart.ps1` |
| Runs | `gradlew runClient`: the mod straight from the build output | the built jar, copied into the installation's `mods` |
| Needs | nothing but the repository | NeoForge installed into the launcher, and one manual start |
| Game directory | `run/` in the repository | `%APPDATA%\.minecraft\<mod_id>` |
| Use it for | everyday work, and any agent session | checking the jar players will actually get |

## The dev client

The window opens on the **secondary monitor** when there is one (`-Screen primary` to change), and
is moved there without taking focus, so it does not land on the screen you are working on.

```powershell
tools\dev\mc-devclient.ps1
```

Stops a dev client that is already running, compiles, launches `gradlew runClient` in the background
and joins the world named by `dev_world` in `gradle.properties` from the title screen's quick-play
path (`-World` picks another save in `run/saves`). It returns once the player has actually joined — not merely once a window exists —
and sizes the window to 1600 × 900 so screenshots have room for detail. A failed build or a crash
ends it with an error instead of a wait.

The world has to exist first: create it once through the game's own menus, as a superflat creative
world with cheats on, under the name `dev_world` gives. `gradlew runClient -Pworld="<name>"` joins a world the same way from a terminal.

The dev client is a different game directory from a launcher installation, so point the other
scripts at it where they need one:

```powershell
$env:MC_GAME_DIR = "$PWD\run"
un"
tools\dev\mc-testarena.ps1
```

The game's log is `run/logs/latest.log`; Gradle's own output is in `%TEMP%\mc-devclient-<mod_id>.log`.

## One-time setup for a launcher installation

1. Install NeoForge 26.2 into the Minecraft Launcher, and create an installation that uses it with
   its **game directory** set to `%APPDATA%\.minecraft\<mod_id>`, using the `mod_id` from
   `gradle.properties`. A folder of its own keeps this mod's worlds, configs and logs apart from real
   saves and from other mods. To use a different folder, set `MC_GAME_DIR`.
2. Start that installation from the launcher and create a **superflat** world in creative with
   cheats on. Run the game **windowed or borderless**: exclusive fullscreen captures black.
3. Build, then restart into the world with the mod deployed:

   ```powershell
   .\gradlew.bat build
   tools\dev\mc-restart.ps1
   ```

   The first restart captures the launcher's command line from the running game. After that the game
   can be restarted even when it is not running.
4. Build the test arena:

   ```powershell
   tools\dev\mc-testarena.ps1
   ```

## Seeing

```powershell
tools\dev\mc-capture.ps1 -Out shot.png
```

Captures **only Minecraft's client area**, never the rest of the desktop, and scales the result down
(`-MaxWidth`, default 1280), because a full-resolution frame costs far more to read than it adds.
`-FullWindow` includes the title bar; `-Focus` raises the window first. The window has to be visible
and unobscured: this reads pixels off the screen, which is the only way to photograph an OpenGL window
from outside the process.

`-Crop "x,y,width,height"` grabs one region at native resolution. Reading the F3 readout alone costs a
fraction of a whole frame:

```powershell
tools\dev\mc-capture.ps1 -Out pos.png -Crop "0,245,700,130" -MaxWidth 0
```

Crop coordinates are **client pixels**, not the coordinates of an already-scaled screenshot. Scale
them up by `clientWidth / MaxWidth` when reading them off one.

## Seeing motion

A still shows where something is, not how it moves. `mc-input.ps1` can photograph in the middle of a
sequence, so the frames are taken as quickly as the game shows the input before them:

```powershell
tools\dev\mc-input.ps1 -Do @("click:right", "burst:16:50:C:\shots\cast.png:500,150,600,600")
```

`burst:<count>:<interval>:<file>[:<region>]` takes `count` frames `interval` milliseconds apart and
lays them out as one numbered contact sheet, left to right and top to bottom, each labelled with its
time. One sheet costs about as much to look at as one screenshot, and a gesture, a flash or a swing
reads off it at a glance. The region, in client pixels, keeps the frames large where the action is.
`shot:<file>` takes a single frame the same way.

The frames are paced by a stopwatch, but each one takes a few tens of milliseconds to copy off the
screen, so intervals much below 50 ms are not honoured.

## Controlling without touching the desktop: in-game scripts

Kinora's dev client runs scripts inside the game (`kinora-mc/.../dev/DevScript.java`, active only
outside production). Prefer this to `mc-input.ps1`: nothing goes to the operating system, so it cannot
type into another window, and it works while the game is in the background.

```powershell
tools\dev\mc-devclient.ps1 -NoJoin -GradleArgs @("-PkinoraScript=D:/path/tools/dev/scripts/editor-smoke.txt")
```

or `tools\dev\kinora-run.ps1 -Script <file> [-Shaders]`, which also waits and prints the log; or,
with a client already running, copy a script to `run/kinora/dev/script.txt`; it is picked up
within a second. Results: `run/kinora/dev/script.log` (each step, `state` lines, `FAIL` lines),
`run/kinora/dev/script.done` (`PASS` or `FAIL <n>`) and `run/kinora/dev/screenshots/`.

Commands are listed in the `DevScript` Javadoc: `open`, `waitfor`/`expect` (`replay`, `screen <Name>`,
`noscreen`, `playing`, `paused`, `moved <blocks>`), `wait`, `key ctrl+z`, `down`/`up`, `type`, `move`,
`click`, `drag`, `mousedown`/`mouseup`, `scroll`, `mark`, `state`, `screenshot`, `log`, `quit`.
Coordinates are GUI-scaled, or percentages (`35% 40%`). Input goes through the window's own GLFW
callbacks, so it takes the same path real input does. Keys held with `down` are also visible to code
that polls key state through `DevScript.held`.

The full command list and the shot-making workflow are in the main [README](../../README.md#script-commands).

## Controlling with OS input

```powershell
tools\dev\mc-input.ps1 -Do "key:f3","sleep:500","move:200:-40","click:left"
```

Steps run in order:

| Step | Meaning |
| --- | --- |
| `key:w`, `key:w:down`, `key:w:up` | tap, hold, release |
| `key:r`, `key:f3`, `key:escape` | named keys |
| `type:hello` | literal text |
| `cmd:/tp @s 10 64 20 180 0` | open chat, clear it, run a command |
| `move:200:-40` | relative mouse motion, for looking around (~29 px per degree at default sensitivity, so a half turn is ~5200 px) |
| `moveto:930:370` | absolute pointer position in **client pixels**, for menus |
| `click:left`, `click:right:down` | mouse buttons, tapped or held |
| `wheel:1` | hotbar scroll |
| `sleep:250` | milliseconds |
| `shot:C:\shots\a.png` | capture one frame, mid-sequence |
| `burst:12:60:C:\shots\b.png[:x,y,w,h]` | capture a contact sheet; see [Seeing motion](#seeing-motion) |

Input is synthesised with `SendInput`, at the level a real driver produces, which is what GLFW and
Minecraft's grabbed-cursor raw mouse input accept. `SendKeys` and posted window messages are both
ignored by the game.

**This steals focus.** Real input events go to the foreground window, so the script raises Minecraft
first. Keep hands off the keyboard while a sequence runs.

## Restarting after a code change

```powershell
.\gradlew.bat build
tools\dev\mc-restart.ps1
```

A mod jar can only be swapped while the game is down, so testing a change means a restart every time.
This flushes the world to disk, stops the game, removes older jars of this mod, deploys
`build/libs/<mod_id>-<version>.jar`, relaunches with the exact command line the launcher used, rejoins
the most recently played world via `--quickPlaySingleplayer`, and puts the window back at the size and
position it had.

The launch command line holds a session token, so it is kept in `%TEMP%` and never written into the
repository. It survives a reboot.

## The test arena

```powershell
tools\dev\mc-testarena.ps1
```

Installs `tools/dev/testarena` into the open world as a datapack and runs it. After that,
`/function devarena:build` on its own rebuilds the whole arena in a second, which is the point: an
experiment that wrecked the geometry costs one command to undo, and two people running the same
command get the same arena.

It is one file, `data/devarena/function/build.mcfunction`, and it is meant to be edited: add the
stations your mod's bugs need. Absolute coordinates on purpose, because a bug is only worth reporting
if somebody else can stand where you stood.

The arena expects **superflat** and open sky. It replaces the grass over `x 0..79, z 0..79` with its
own floor at `y = -60`. Stations run north to south in sixteen-block rows, with a strip of coloured
concrete on the floor in front of each:

| Row | Colour | Station |
| --- | --- | --- |
| `z 0..15` | light grey | Plaza and spawn, `40 -59 8` |
| `z 16..31` | orange | Seven target walls: stone, planks, glass, obsidian, sand, wool, deepslate |
| `z 32..47` | lime | Open field with rings 3 and 6 blocks out, for judging reach |
| `z 48..63` | blue | A sealed dark room with a door (west), and a pool three deep (east) |
| `z 64..79` | red | A roofed pen of mobs that stand still: zombie, skeleton, husk, pig, iron golem, armour stand |

The build also pins the world to noon with weather and natural spawning off, so nothing changes
underneath an experiment. `/summon` still works.

Two things that cost time:

- **26.2 renamed every game rule**, to snake case and in several cases outright: `doDaylightCycle` is
  `advance_time`, `doWeatherCycle` is `advance_weather`, `doMobSpawning` is `spawn_mobs`, `doInsomnia`
  is `spawn_phantoms`. The old names do not parse, and the error points at the rule name without
  saying it has moved.
- **A structure left overhead will black out the whole arena.** Sky light stops at it, block light
  still works, and the symptom — an unlit arena under a blue sky — looks exactly like a broken light
  engine. Clear the airspace before blaming anything else.

## Measuring a sound

```powershell
tools\dev\mc-soundlength.ps1 block.beacon.activate entity.ender_eye.launch
```

How long a sound event actually is, in seconds, read out of the game's own asset store: the event is
resolved through `sounds.json`, then each file's length is taken from its Ogg headers. Needs no running
game.

It exists because *"that sound is too long"* is not actionable and *"that sound is 2.95 seconds and
you fire three times a second"* is. Two things it tells you that are easy to get wrong by ear:

- **An event can have several files behind it**, picked at random, and they differ in length. All of
  them are listed, because the longest is the one that decides how the event feels.
- **Pitch is playback rate.** A sound played at 1.6 finishes in 1/1.6 of the length shown, and pitch
  stops at 2.0 — so half the file is the shortest a vanilla sound can be made without shipping audio.

## A debug command of your own

Screenshots show that something is wrong; they rarely show why. The portal gun's `/portaldebug` put
portals at exact coordinates and switched each rendering mechanism on and off separately, which was
the only practical way to tell one fault from another. Give a mod with anything non-trivial to see a
command like it early — register it from the main class on `RegisterCommandsEvent` — rather than after
the first evening lost to guessing.

Two things help with that in practice:

- **A client command** for what only a client sees — an animation, a render state, an effect. Register
  it on `RegisterClientCommandsEvent`, and only when `!FMLEnvironment.isProduction()`, so it never
  ships. Give anything it triggers an optional delay: the `cmd:` step waits after sending a command,
  so something that fired at once would be half over before a `burst` took its first frame.
- **A `minecraft:mannequin` as a stand-in for a player.** It is drawn by the player renderer, holds
  items, and stands wherever it is summoned, so whatever a player does can be watched from the side or
  the front, as often as needed:

  ```
  /summon minecraft:mannequin ~ ~ ~3 {Rotation:[90f,0f],equipment:{mainhand:{id:"minecraft:stick",count:1}}}
  ```

## Where things live

| Path | What |
| --- | --- |
| `run\logs\latest.log` | the dev client's log |
| `run\saves\` | the dev client's worlds |
| `%APPDATA%\.minecraft\<mod_id>\logs\latest.log` | a launcher installation's log |
| `%APPDATA%\.minecraft\<mod_id>\config\<mod_id>-client.toml` | client config |
| `%APPDATA%\.minecraft\<mod_id>\saves\<world>\serverconfig\<mod_id>-server.toml` | server config, per world |
| `%APPDATA%\.minecraft\<mod_id>\crash-reports\` | first place to look when the window vanishes |

A config value already written to disk **wins over a new default in the code**, and is clamped to the
range the *running* jar declares. Raising a limit means editing the TOML too; NeoForge reloads it
live, so no restart is needed for that.

## Gotchas that have already cost time

- **PowerShell formats decimals with the local separator.** `"{0:F2}" -f -326.5` yields `-326,50` on a
  German system, which Minecraft's command parser rejects, and the player simply does not move. Format
  with `[System.Globalization.CultureInfo]::InvariantCulture`.
- **`$args` is an automatic variable.** Naming a function parameter `$args` silently swallows what you
  pass it.
- **PowerShell variables are case-insensitive**, so a loop variable `$vk` overwrites a table named
  `$VK` — which is why an early version of `mc-input.ps1` could only ever send one key per call.
- **`-Do` takes an array, and `powershell -File` will not build one for you.** Launched that way,
  `-Do "a","b"` arrives as the single string `a,b`, which `mc-input.ps1` types into chat as one line.
  Call it in the session you are already in: `& tools\dev\mc-input.ps1 -Do @("a","b")`.
- **In an array, `,` binds tighter than `+`.** `@("click:right", "shot:" + $path)` is the array
  `"click:right", "shot:"` with `$path` appended as a third element, and the step gets no file. Build
  such a step in its own parentheses or as one interpolated string: `("shot:$path")`.
- **`New-Object T($a * $b, $c)` is parsed as a command**, where the comma binds first too. Work sizes
  out into variables before passing them.
- **The scripts are the eyes, not the judgement.** A contact sheet shows the shape of a movement, not
  whether it reads as fluent at full speed; that still needs a person watching.
