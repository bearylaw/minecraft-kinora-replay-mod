# Third-party notices

## Bundled in the Kinora mod jar

| Component | Version | License | Use |
| --- | --- | --- | --- |
| [aircompressor](https://github.com/airlift/aircompressor) (`io.airlift:aircompressor-v3`) | 3.10 | Apache License 2.0 | Pure-Java zstd compression of `.kinora` replay files. Nested with jar-in-jar, unmodified. |

The Apache License 2.0 text: <https://www.apache.org/licenses/LICENSE-2.0>.

## Used but not bundled

| Component | License | How Kinora uses it |
| --- | --- | --- |
| Minecraft | Minecraft EULA | The game Kinora runs in. Nothing from it is redistributed. |
| NeoForge | LGPL-2.1 | The mod loader. Not bundled. |
| Gson | Apache License 2.0 | JSON for projects, jobs and metadata; provided by Minecraft at runtime. |
| FFmpeg | LGPL 2.1+ or GPL 2+, depending on the build | Optional, for video output. Never bundled or downloaded by Kinora: users install it and Kinora runs it as a separate program. |
| Sodium, Iris | Polyform Shield / LGPL-3.0 (see each project) | Optional compatibility. Kinora calls Sodium reflectively and applies optional mixins to Iris classes when they are installed; no code from either is included. |
| System fonts | As installed | Title overlays are drawn with fonts installed on the system (Java's sans-serif by default); no font files are bundled. |

## Build and test only

| Component | License |
| --- | --- |
| JUnit 6 | Eclipse Public License 2.0 |
| ModDevGradle, Gradle | Apache License 2.0 / LGPL-2.1 (NeoForge tooling) |

Kinora is a clean-room implementation: it contains no code from ReplayMod or other replay mods, and
its `.kinora` file format is its own (see `docs/format.md`).
