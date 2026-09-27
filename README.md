# Folio build kit

Repository seed for an autonomous Claude Code build of **Folio**: a native Android hybrid note app (pen ink plus grid-snapped Markdown text) for the Xiaomi Pad 7 with Focus Pen. You steer and test; Claude Code builds.

## What is in here

| Path | Purpose |
|---|---|
| `BOOTSTRAP_PROMPT.md` | First message you paste into Claude Code |
| `CLAUDE.md` | Always-loaded project brain. Hard cap 200 lines, enforced by a hook |
| `.claude/rules/` | Path-scoped rules. They load only when Claude reads matching files |
| `.claude/skills/` | On-demand procedures: next-task loop, device testing, spikes, memory upkeep |
| `.claude/agents/` | Subagents: `reviewer`, `device-tester` (keeps screenshots and logs out of the main context) |
| `.claude/hooks/`, `.claude/settings.json` | Enforcement: adb guard, protected files, size limits, status injection at session start |
| `docs/architecture/` | The design. Source of truth for "how" |
| `docs/plan/` | Phased task plan and `STATUS.md` (the progress pointer) |
| `scripts/device/` | The only path by which Claude touches the tablet |
| `scripts/auto/run-loop.sh` | Unattended mode: one fresh headless session per task |
| `docs/design/reference/` | Your two Notewise screenshots (visual direction) |

## One-time setup

### On each PC (Windows 11 and/or Arch)
1. JDK 21. Windows: `winget install EclipseAdoptium.Temurin.21.JDK`. Arch: `sudo pacman -S jdk21-openjdk`.
2. Android SDK. Easiest: install Android Studio once, open SDK Manager, install the latest stable SDK Platform, Build-Tools, Platform-Tools and "Command-line Tools (latest)". Set `ANDROID_HOME` to the SDK folder and add `$ANDROID_HOME/platform-tools` to `PATH`.
3. Accept SDK licenses yourself: `sdkmanager --licenses` (Claude will not accept licenses on your behalf).
4. `git` and `jq`. Windows: `winget install Git.Git jqlang.jq` (Claude Code runs its shell through Git Bash). Arch: `sudo pacman -S git jq`.
5. Claude Code installed and logged in.

### On the tablet (Xiaomi Pad 7, HyperOS)
1. Settings > About device > tap the OS version repeatedly until developer options unlock.
2. Settings > Additional settings > Developer options: enable **USB debugging**, **Wireless debugging**, **Install via USB**, **USB debugging (Security settings)** (HyperOS may ask for a Mi account sign-in for the last two), and **Stay awake**.
3. Pair once from the PC: Wireless debugging > "Pair device with pairing code", then run `adb pair <ip>:<pair-port>` and type the code. This is the only raw adb command you ever run. Claude uses the wrappers in `scripts/device/`.
4. During device tasks keep the tablet unlocked, on the same Wi-Fi, with Wireless debugging on.

Claude only ever installs `dev.folio.notes.debug`, which keeps its data in `Documents/Folio-Debug/`. Your real app (`dev.folio.notes`, library in `Documents/Folio/`) is never installed, read, or modified by Claude.

### Repository
```bash
mkdir folio && cd folio && git init
# copy the kit contents here, including the hidden .claude/, .gitattributes, .gitignore
find scripts .claude/hooks -name '*.sh' -exec chmod +x {} +   # Arch; harmless on Windows
git add -A && git commit -m "chore: import build kit"
claude    # accept the workspace trust prompt once, then paste BOOTSTRAP_PROMPT.md
```
Phase 0 runs interactively so you can watch the toolchain and device link come up.

## Daily use
- Interactive: run `claude` and say `Continue per CLAUDE.md`. The status snapshot is injected automatically.
- Unattended: `bash scripts/auto/run-loop.sh 15` runs up to 15 tasks, each in a fresh session. It stops on a Blocked item, on plan completion, or after two runs without progress. Logs go to `.device/loop-logs/`.
- Your jobs live in `docs/plan/STATUS.md`:
  - `## Blocked`: something only you can do (pair the tablet, accept a license, decide a trade-off). Do it, then delete the line or write your answer under it.
  - `## USER-CHECK`: things only a human can judge (pen feel, latency, visuals). Test on the tablet, then append `-> ok` or `-> fail: <what you saw>` to the line.
- Protected config (hooks, `settings.json`, device scripts) can only be changed by Claude if you start it with `FOLIO_UNLOCK_CONFIG=1 claude`.

## Renaming
The app name `Folio` and ids `dev.folio.notes` / `dev.folio.app` appear in `CLAUDE.md`, `scripts/device/_common.sh` and the docs. Search and replace before the first bootstrap if you want another name.
