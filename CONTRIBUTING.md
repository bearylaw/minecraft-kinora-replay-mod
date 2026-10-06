# Contributing to Kinora Replay

Bug reports, ideas and pull requests are welcome.

## Reporting bugs

Open an [issue](https://github.com/bearylaw/minecraft-kinora-replay-mod/issues) with:

- the Kinora, Minecraft and NeoForge versions, and other mods installed (Sodium, Iris, Distant
  Horizons, ...);
- what you did, what you expected and what happened;
- `logs/latest.log` (and the crash report, if there is one).

If a replay will not open, `kinora-inspect validate <file.kinora>` (see
[Building and developing](README.md#building-and-developing)) often says why. Attach the recording
only if you are happy for it to be public: it contains your world, chat and player names.

Security problems go through [SECURITY.md](SECURITY.md), not public issues.

## Pull requests

1. Fork the repository and branch from `main` (or from a version branch such as `26.2` for a fix
   to an older Minecraft version).
2. Build and test: `./gradlew build` and `python tools/dev/check-lang.py`. CI runs both.
3. Test in the game what you changed; the dev scripts in `tools/dev/` help (see
   [`tools/dev/README.md`](tools/dev/README.md)).
4. Keep a pull request to one change, and describe what it fixes or adds and how you tested it.
   User-facing changes get a line in [`CHANGELOG.md`](CHANGELOG.md).

For larger features, open an issue first so we can agree on the approach before you spend time
on it.

### Code style

Match the code around your change: naming, comment density, and the split between `kinora-core`
(plain Java, no Minecraft, unit tested), `kinora-api` (stable, for other mods) and `kinora-mc`
(the NeoForge mod). New mixins are listed in [`docs/mixins.md`](docs/mixins.md); new UI text goes
in `en_us.json`.

## License of contributions

Kinora Replay is source-available, not open source: see [`LICENSE`](LICENSE). By opening a pull
request you agree to section 3 of it: you confirm you have the right to submit the contribution
and grant the copyright holder a licence to use and relicense it. This covers every part of the
repository, including `kinora-api` (LGPL-3.0-only) and `sample-mod` (MIT).

Your fork exists to prepare pull requests. Please do not publish builds of it or upload it to mod
platforms; the license does not allow that.
