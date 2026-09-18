# Tokitoki for JetBrains IDEs

Automatic coding time tracking plus AI usage analytics for Claude Code,
Codex, Copilot, and more, on your dashboard at
[tokitoki.dev](https://tokitoki.dev). Works in every JetBrains IDE:
IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, CLion, RubyMine, PhpStorm,
DataGrip, Android Studio.

## Features

- **Automatic time tracking.** Just code. Tokitoki records which files,
  projects and languages you work in, including debugging and code review
  time. No timers to start, no forms to fill.
- **AI usage analytics.** Your local AI coding agents (Claude Code, Codex,
  Copilot CLI, Gemini, Amp, Goose, OpenCode and more) are scanned and synced
  automatically, so tokens, models and costs show up next to your coding
  time.
- **Today in the status bar.** Active time for the project you have open,
  from the same figure the dashboard shows, on every machine with the same
  key. Click it to open the dashboard, signed in.
- **Works offline.** Activity is queued locally and uploaded when you are
  back online.
- **Open source.** The plugin, the CLI it bundles, and the other clients are
  Apache-2.0 at [github.com/tokitoki-dev](https://github.com/tokitoki-dev).

## Quick start

1. Install the plugin from the JetBrains Marketplace.
2. Run **Tools > Tokitoki > Set API Key** and paste the key from
   [tokitoki.dev](https://tokitoki.dev). The plugin also asks on first use.
3. That's it. The status bar shows today's time; click it for the dashboard.

## Menu

Tools > Tokitoki:

| Action | What it does |
| --- | --- |
| Open Dashboard | Opens your web dashboard, signed in |
| Set API Key | Stores your API key for every Tokitoki client on this machine |
| Show API Key Status | Shows the configured key, masked, and checks it against the server |
| Set Project Name | Pins the name this project reports, in its `.tokitoki` file |
| Sync AI Usage Now | Scans your AI coding agents and uploads their usage right away |

## Settings

Settings > Tools > Tokitoki has two switches: show the status bar item, and
show today's time in it (off keeps the icon and moves the figure to the
tooltip). Tracking and uploading need no configuration.

To pin a stable project name across machines and editors, run **Set Project
Name**. It writes the first line of a `.tokitoki` file in the project root;
an optional second line overrides the branch. Editing the file by hand works
just as well, and the next heartbeat picks it up.

## Privacy

Tokitoki records activity metadata only: file paths, project and branch
names, language, timestamps, cursor position, and the number of lines you
typed. **Never your code.** AI usage sync reads token counts and model names
from your local agent data. Everything is queued in `~/.tokitoki` and
uploaded over HTTPS with your API key; delete your data anytime from the
dashboard.

## Links

- [Dashboard](https://tokitoki.dev)
- [Source & issues](https://github.com/tokitoki-dev/tokitoki-jetbrains)
- [Development](DEVELOPMENT.md) · [Releasing](RELEASING.md)
- [All Tokitoki clients](https://github.com/tokitoki-dev): VS Code, macOS,
  Windows, CLI
