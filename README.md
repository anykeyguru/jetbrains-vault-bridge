# Vault Bridge

Brings your [Obsidian](https://obsidian.md) vault into JetBrains IDEs (PyCharm, GoLand, IntelliJ IDEA,
WebStorm, DataGrip, RustRover, …), so you can link code to notes and browse them without leaving the IDE.

Works with any IntelliJ-based IDE **2024.3 or newer** (build 243+).

Jump to: [Quick start](#quick-start) · [Install](#install) · [Using it](#using-it) · [Troubleshooting](#troubleshooting)

## Features

- **Per-project vault settings**: point each project at its own vault (Settings > Tools > Vault Bridge).
- **Note index**: front matter, tags, and links are indexed in the background and kept up to date as files change. Re-index on demand from Tools > Vault Bridge.
- **Status bar widget**: shows the active vault and index state.
- **`[[wiki-links]]` in code comments**:
  - completion of note names inside `[[…]]`
  - Ctrl/Cmd+Click navigation to the note
  - gutter icon on lines that reference a note
  - highlighting of unresolved links
- **Search vault**: find notes by title, alias, or content.
- **Daily note / new note**: create today's daily note or a new note from the IDE.
- **Backlinks, Tags, and Graph tool windows**: see what links to the current note, browse by tag, and explore the link graph.
- **Diagnostics**: find broken links, duplicate titles, and other vault problems.

## Install

### One-liner (macOS / Linux)

```sh
curl -fsSL https://raw.githubusercontent.com/anykeyguru/jetbrains-vault-bridge/main/install.sh | sh
```

The script downloads the latest release, finds every JetBrains IDE 2024.3+ on the machine (by its config
directory), and installs the plugin into each one, replacing any older copy. It doesn't need a JDK, Gradle, or `gh`,
only `curl` and `unzip`. Restart the IDE afterwards; the script warns you if one is running.

Options (environment variables):

| Variable | Example | Meaning |
|---|---|---|
| `VAULT_BRIDGE_IDES` | `"PyCharm GoLand"` | Install only into these products (case-insensitive prefix) |
| `VAULT_BRIDGE_VERSION` | `v0.2.0` | Install a specific release instead of the latest |
| `VAULT_BRIDGE_ZIP` | `~/Downloads/vault-bridge.zip` | Install from a local file or another URL (offline) |

```sh
curl -fsSL https://raw.githubusercontent.com/anykeyguru/jetbrains-vault-bridge/main/install.sh | VAULT_BRIDGE_IDES=PyCharm sh
curl -fsSL https://raw.githubusercontent.com/anykeyguru/jetbrains-vault-bridge/main/install.sh | sh -s -- --uninstall
```

### Manual install from disk

1. Download `vault-bridge.zip` from the [latest release](https://github.com/anykeyguru/jetbrains-vault-bridge/releases/latest).
2. In the IDE, go to **Settings > Plugins > ⚙ > Install Plugin from Disk…**, pick the zip, and restart.

### Automatic updates

The plugin isn't on JetBrains Marketplace. Each release publishes a custom plugin repository instead.
Add it once:

**Settings > Plugins > ⚙ > Manage Plugin Repositories… > +**

```
https://github.com/anykeyguru/jetbrains-vault-bridge/releases/latest/download/updatePlugins.xml
```

After that, the IDE picks up new versions with its normal plugin update checks. You can also install
Vault Bridge from the Marketplace tab once the repository is added.

### Corporate proxy

- **install.sh** uses `curl`, which reads `https_proxy` / `HTTPS_PROXY` / `no_proxy`:
  `export https_proxy=http://proxy.corp:3128` before running the one-liner.
- If GitHub is blocked for scripts but works in the browser, download `vault-bridge.zip` manually and run
  `VAULT_BRIDGE_ZIP=~/Downloads/vault-bridge.zip sh install.sh`.
- **Auto-updates** go through the IDE's own proxy settings: **Settings > Appearance & Behavior > System Settings > HTTP Proxy**.

## Quick start

You need a JetBrains IDE 2024.3+ and an Obsidian vault on disk. Obsidian itself doesn't have to be running.

1. **Install** the plugin (see [Install](#install)) and restart the IDE.
2. **Choose the vault.** On the first project you open, a hint *Connect your Obsidian vault* appears: click
   **Choose vault…**. Or open **Settings > Tools > Vault Bridge**, or click **Vault: not set** in the status bar.
   Pick the folder that contains `.obsidian/` and click **OK**.
3. **Check the status bar**: it shows `Vault: <name> · N notes`.
4. **Link a note from code.** In any comment type `[[` and pick a note from the list:
   ```python
   # Retry policy is described in [[Payments/Retry strategy]]
   ```
   ```go
   // see [[Traefik#Middlewares]]
   ```
   A vault icon appears in the gutter. Click it, or **Cmd/Ctrl+Click** the link, to open the note.

The vault is set **per project** (stored in `.idea/workspace.xml`, not committed), so each project can use its own
vault, and the vault can live anywhere on disk.

## Using it

### Links in code

| What | How |
|---|---|
| Complete a note name | Type `[[` in a comment, string, Markdown or text file. No popup? Press **Ctrl+Space**. |
| Open the note | **Cmd/Ctrl+Click** or **Cmd/Ctrl+B** on the link. `[[Note#Heading]]` opens at the heading. |
| Gutter icon | Shown on comments with links; click to open, several links show a chooser. Can be turned off in settings. |
| Missing note | The link is highlighted. **Alt+Enter → Create note** creates it in the vault and opens it. |

Links resolve the way Obsidian does: by path if the link has a `/`, otherwise by file name, then by `aliases` from
front matter. If two notes share a name, the one in the same folder wins, then the one closest to the vault root.

### Actions

Under **Tools > Vault Bridge**, or press **Shift Shift** and type the name:

- **Search Vault…** — by title, alias, `#tag`, folder or text inside notes; Enter opens the note.
- **Open Daily Note** — uses the folder, date format and template from Obsidian's *Daily notes* settings.
- **New Note…** — `ideas/caching` creates `ideas/caching.md`.
- **Vault Diagnostics** — broken links, duplicate names, files that weren't indexed.
- **Re-index Vault** — rarely needed: edits, including those made in Obsidian, are picked up automatically.

No shortcuts are bound by default, to avoid clashing with your keymap. Add your own in **Settings > Keymap**
(search for "Vault").

### Tool windows

**View > Tool Windows**:

- **Vault Backlinks** — notes that link to the open note, with the surrounding line; plus its outgoing links.
- **Vault Tags** — nested tags (`project/alpha`) with counts; type to filter.
- **Vault Graph** — local graph around the current note (depth 1–3) or the whole vault; scroll to zoom, drag to pan,
  click to open.

## Troubleshooting

| Symptom | What to do |
|---|---|
| Status bar says `0 notes` | Make sure the folder is the vault root (contains `.obsidian/`), then **Re-index Vault**. |
| No completion popup after `[[` | Some languages block auto-popup in comments: press **Ctrl+Space**. |
| Link marked missing, but the note exists | Links use the file name, not the title. Fix the name or add the title to `aliases`. |
| No gutter icon | Check **Settings > Tools > Vault Bridge** and **Editor > General > Gutter Icons**. |
| Anything else | Run **Vault Diagnostics** and [open an issue](https://github.com/anykeyguru/jetbrains-vault-bridge/issues). |

## Build from source

Requires JDK 21. Gradle comes from the wrapper.

```sh
./gradlew test buildPlugin     # -> build/distributions/jetbrains-vault-bridge-<version>.zip
./gradlew runIde               # sandbox IDE with the plugin loaded
```

## Release process

1. Make sure `main` is green.
2. Tag and push:
   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```
3. The `Release` workflow builds with `-PpluginVersion=0.2.0` (taken from the tag; `pluginVersion` in
   `gradle.properties` is only the local default), runs the tests, and creates a GitHub Release with:
   - `jetbrains-vault-bridge-0.2.0.zip` and a stable copy, `vault-bridge.zip`
   - `updatePlugins.xml`, the custom repository descriptor pointing at this release's zip
   - `install.sh`

   Release notes are generated automatically. Tags with a hyphen (`v0.3.0-rc1`) become pre-releases. These
   don't count as "latest", so the installer and auto-updates ignore them.

## License

[Apache License 2.0](LICENSE)
