# Vault Bridge

Brings your [Obsidian](https://obsidian.md) vault into JetBrains IDEs (PyCharm, GoLand, IntelliJ IDEA,
WebStorm, DataGrip, RustRover, …), so you can link code to notes and browse them without leaving the IDE.

Works with any IntelliJ-based IDE **2024.3 or newer** (build 243+).

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

## Configuration

Open **Settings > Tools > Vault Bridge** and set the vault path (the folder that contains `.obsidian/`).
The setting is stored per project. The status bar shows whether the vault has been indexed. Then write links in any
comment:

```python
# Retry policy is described in [[Payments/Retry strategy]]
```

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
