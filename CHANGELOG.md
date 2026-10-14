# Changelog

## 0.0.4

- Bundled CLI v0.1.11: Claude Code and Codex **tool calls** — built-in
  tools, MCP tools and the programs a shell command runs — and **prompts**
  are recorded, so the dashboard shows which tools agents use and how long
  each question took. Tool arguments and prompt text never leave your
  machine. The first sync after updating reads existing transcripts once
  more to pick up their history.
- **Projects are named after their repository.** Heartbeats no longer send
  the IDE project's name: the CLI names the project from the file and the
  project root, the same way it names an AI agent's work, so editor time and
  AI usage land on one project, and a worktree or a subfolder counts toward
  its repository. A name pinned in `.tokitoki` still wins; the status bar,
  the tool window and **Set Project Name** all show the name in effect. The
  branch is read from the checkout itself.

## 0.0.3

- Compatible with IntelliJ IDEA 2026.2 and 2026.3 without warnings: the
  project selector and the message bundle no longer use platform APIs that
  are deprecated or scheduled for removal.

## 0.0.2

- Listed on the Marketplace as **Tokitoki Coding Time Tracker**, with a
  description that says what it tracks.
- Bundled CLI v0.1.10: a rotating run log, crash reports, error forwarding,
  and a stable machine label for uploads.

## 0.0.1

- First release. Automatic coding time tracking through the shared
  `tokitoki` CLI, AI usage sync every five minutes, today's active time for
  the open project in the status bar, and a Tokitoki tool window with the
  week at a glance.
- Activity counts typing, caret moves, scrolling, clicking, tab switches,
  window focus, tool windows, run and debug sessions, saves, and file
  operations. Debug sessions are recorded as **debugging**, a selected diff
  as **code reviewing**.
- Heartbeats carry the IDE's file type as the language and the lines you
  typed, kept apart from what agents and completions wrote.
- Tools > Tokitoki: Open Dashboard, Set API Key, Show API Key Status, Set
  Project Name, Sync AI Usage Now.
- Bundled CLI v0.1.9.
