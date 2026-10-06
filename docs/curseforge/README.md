# CurseForge page

- [`description.md`](description.md): the project description. In CurseForge's description editor,
  switch the format to **Markdown** and paste the whole file.
- [`images/`](images/): the pictures it shows. They load from this repository's `main` branch on
  GitHub, so they appear only once the repository is public (or upload them to the project's
  CurseForge gallery and replace the URLs).
- Summary (the short line under the project name):
  Record your gameplay, then film it again like a director: fly a keyframed cinematic camera
  through the replay and render smooth, frame-exact video.
- Category: Utility & QoL.

The banner, the "how it works" strip, the FFmpeg guide and the screenshot crops are made by
[`tools/branding/CurseForgeImages.java`](../../tools/branding/CurseForgeImages.java) from the dev
client's screenshots (`run/kinora/dev/screenshots`):

```
java tools/branding/CurseForgeImages.java
```
