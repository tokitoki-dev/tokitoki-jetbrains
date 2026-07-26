# Tokitoki JetBrains

JetBrains IDE integration for the local Tokitoki usage sync agent.

The plugin shells out to a bundled `tokitoki` CLI built from `tracklm-goagent`.
It does not upload directly from the IDE process.

## CLI Packaging

The plugin uses one fixed CLI resolution strategy:

1. Gradle runs `make cross` in `../tracklm-goagent`.
2. The platform binaries are copied into plugin resources under `cli/<os>-<arch>/`.
3. At runtime the plugin selects the current OS/arch resource, extracts it to
   the JetBrains system directory, marks it executable, and runs that file.

There is no PATH lookup, workspace lookup, or user-configured CLI path fallback.

## Features

- Sync on project startup and a configurable interval.
- Optional throttled sync after editor edits and file saves.
- `Tools > Tokitoki > Sync Now`.
- `Tools > Tokitoki > Set API Key` runs `tokitoki set key <API_KEY>`.
- `Tools > Tokitoki > Show API Key Status` runs `tokitoki get key`.
- Background service commands run `tokitoki service ...`.
- `TOKITOKI_BASE_URL` is passed to the bundled CLI from plugin settings.
- Repeated `provider=path` settings are passed as `--provider-dir`.

## Build

Build and package the JetBrains plugin:

```sh
cd ../tokitoki-jetbrains
./gradlew test buildPlugin
```

`buildPlugin` automatically runs `make cross` in `../tracklm-goagent` before
packaging resources.

The plugin ZIP is emitted under `build/distributions/`.

## Notes

This MVP maps JetBrains editor activity to local CLI sync triggers. It does not
implement WakaTime-style per-file heartbeat submission because the current
Tokitoki agent owns scanning, deduplication, persistence, and upload behavior.
