# CurseForge page

- [`description.html`](description.html): the project description, ready to paste. In CurseForge's
  description editor, keep the format on **WYSIWYG**, click the **`</>`** (source code) button, paste
  the whole file there, then switch the source view off again to check the result.
- [`description.md`](description.md): the same text in Markdown, the source of the HTML. Pasting it
  into the normal editor turns it into escaped text (`\*\*`); use the HTML instead.
- [`images/`](images/): the pictures, loaded from this repository's `main` branch on GitHub.
- Summary (the short line under the project name):
  Record your gameplay, then film it again like a director: fly a keyframed cinematic camera
  through the replay and render smooth, frame-exact video.
- Category: Utility & QoL.
- Release notes per version: `changelog-<version>.md` / `.html`, pasted the same way as the description.

After editing `description.md`, rebuild the HTML (then re-add the `<br>` after the first bold
line of the warning box):

```
npx -y marked@15 -i docs/curseforge/description.md -o docs/curseforge/description.html
```

The banner, the "how it works" strip, the FFmpeg guide and the screenshot crops are made by
[`tools/branding/CurseForgeImages.java`](../../tools/branding/CurseForgeImages.java) from the dev
client's screenshots (`run/kinora/dev/screenshots`):

```
java tools/branding/CurseForgeImages.java
```
