# Development

The plugin watches IDE activity and reports heartbeats through the
`tokitoki` CLI, which owns the API key, the offline queue, and the upload.
It also runs a periodic AI usage scan, and asks the CLI for today's figure to
show in the status bar. Same design as the VS Code extension; the
platform-specific part is how events are collected.

```text
IDE events -> ActivityTracker (50ms debounce, 2 min throttle per file)
           -> tokitoki heartbeat --entity FILE ...
                     |
       local queue (~/.tokitoki) -> Tokitoki server
```

## Layout

| Path | What |
| --- | --- |
| `TokitokiService` | The plugin as one app-level object: tracker, sync timer, key state, today's figure |
| `tracking/ActivityTracker` | Collects editor events, resolves what file they are about, throttles, emits heartbeats |
| `tracking/Listeners` | The listeners `plugin.xml` declares (focus, save, tabs, VFS, run/debug, tool windows) |
| `tracking/LineChanges`, `Language`, `HeartbeatThrottler` | Pure rules, unit-tested |
| `cli/TokitokiCli` | Shared CLI resolution, seeding, and every command the plugin runs |
| `statusbar/` | Today's time in the status bar |
| `settings/` | Two switches, Kotlin UI DSL |
| `project/ProjectFile` | The `.tokitoki` project identity file |

## What counts as activity

Typing, caret moves, scrolling, clicking, switching tabs (including to a
diff), returning to the IDE window, opening a tool window, running or
debugging, saving, and creating, renaming or deleting files. Everything
funnels into one 50ms debounce and one throttler: one heartbeat per file
every 2 minutes, with writes and file or category changes passing
immediately. Activity with no text editor behind it — a tool window, the
terminal — is credited to the file the user was last in.

A heartbeat carries the IDE's file type translated to the shared language
vocabulary (`tracking/Language`), the category (`coding`, `debugging`, or
`code reviewing` when a diff is selected), and the lines the user typed
since the last heartbeat, kept apart from what agents and completions wrote.

## The shared CLI

Every Tokitoki client on a machine invokes one shared CLI:

```text
~/.tokitoki/bin/tokitoki                    macOS, Linux
%USERPROFILE%\.tokitoki\bin\tokitoki.exe    Windows
```

The plugin resolves the shared binary first and falls back to the bundled
copy, extracted from its resources into the IDE's system directory. On
startup it seeds the shared location when the shared binary is missing or
reports an older release version — staged and renamed into place, never a
downgrade — then asks the CLI to update itself at most once a day.

The `.tokitoki` segment is a build stamp, not a constant. A dev build is
stamped `.tokitoki-dev` and talks to `http://localhost:9093`, the same
values stamped into the CLI it bundles, so it looks for, seeds and runs
`~/.tokitoki-dev/bin/tokitoki` and never touches the production CLI, API key
or queue. The startup log line prints both.

## Build and run

Requirements: JDK 21, Go (for the sibling `../tokitoki-cli` checkout).

```sh
./gradlew test          # unit tests
./gradlew runIde        # a sandbox IDE with the plugin, built against the oldest supported platform
./gradlew buildPlugin   # build/distributions/tokitoki-jetbrains-<version>.zip
./gradlew verifyPlugin  # compatibility check against the recommended IDE range
```

Every local build is a dev build: `scripts/build-cli.sh` compiles
`../tokitoki-cli` for all six platforms with dev stamps and a synthetic
`9999.0.<epoch>` version, so each `runIde` build seeds the shared dev slot.
The bundled binaries live under `build/cli/` and are packed into the plugin
jar as `cli/<os>-<arch>/tokitoki`.

Release builds (`-PtokitokiRelease=true`, what CI passes) instead download
the CLI release pinned by tag and SHA-256 in `scripts/cli-release-pins.sh`
and stamp the production server and data dir. `./gradlew buildInfo` prints
which kind of build you have.

Strings live in `messages/TokitokiBundle.properties` with `zh_CN` and `ja`
variants; the IDE's language pack selects one.
