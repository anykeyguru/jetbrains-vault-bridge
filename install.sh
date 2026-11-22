#!/bin/sh
# Vault Bridge installer for JetBrains IDEs (macOS / Linux).
#
#   curl -fsSL https://raw.githubusercontent.com/anykeyguru/jetbrains-vault-bridge/main/install.sh | sh
#   curl -fsSL .../install.sh | sh -s -- --uninstall
#
# Environment:
#   VAULT_BRIDGE_ZIP=/path/to.zip|https://...   install from this zip (offline / manual)
#   VAULT_BRIDGE_VERSION=v0.2.0                 install a specific release tag (default: latest)
#   VAULT_BRIDGE_IDES="PyCharm GoLand"          only these products (case-insensitive prefix)
#
# Proxies: curl honours https_proxy / HTTPS_PROXY / no_proxy automatically.

set -eu

REPO="anykeyguru/jetbrains-vault-bridge"
PLUGIN_DIR_NAME="jetbrains-vault-bridge"
ASSET="vault-bridge.zip"
MIN_YEAR=2024
MIN_MINOR=3

say()  { printf '%s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
    sed -n '2,13p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//' || true
    say "Usage: install.sh [--uninstall] [--help]"
}

MODE=install
for arg in "$@"; do
    case "$arg" in
        --uninstall|-u) MODE=uninstall ;;
        --help|-h) usage; exit 0 ;;
        *) die "unknown argument: $arg (try --help)" ;;
    esac
done

# --- where JetBrains IDEs keep their per-version directories -----------------
case "$(uname -s)" in
    Darwin)
        CONFIG_BASE="$HOME/Library/Application Support/JetBrains"
        PLUGINS_BASE="$CONFIG_BASE"
        PLUGINS_SUBDIR="/plugins"
        ;;
    Linux)
        CONFIG_BASE="${XDG_CONFIG_HOME:-$HOME/.config}/JetBrains"
        PLUGINS_BASE="${XDG_DATA_HOME:-$HOME/.local/share}/JetBrains"
        PLUGINS_SUBDIR=""   # on Linux plugins live directly in the data dir
        ;;
    *) die "unsupported OS: $(uname -s) (use Settings > Plugins > Install Plugin from Disk)" ;;
esac

# Prints "<Product> <year> <minor>" for an IDE dir name like PyCharm2026.1,
# nothing for non-IDE dirs (Toolbox, consentOptions, Goland, ...).
parse_ide_dir() {
    printf '%s\n' "$1" | sed -n 's/^\([A-Za-z][A-Za-z]*\)\([0-9]\{4\}\)\.\([0-9][0-9]*\)$/\1 \2 \3/p'
}

product_selected() {
    [ -z "${VAULT_BRIDGE_IDES:-}" ] && return 0
    p_lc=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
    for want in $VAULT_BRIDGE_IDES; do
        w_lc=$(printf '%s' "$want" | tr '[:upper:]' '[:lower:]')
        case "$p_lc" in "$w_lc"*) return 0 ;; esac
    done
    return 1
}

# Executable name of the IDE, used for the "is it running" check.
product_exe() {
    case "$1" in
        IntelliJIdea*|IdeaIC*) echo idea ;;
        PyCharm*) echo pycharm ;;
        *) printf '%s' "$1" | tr '[:upper:]' '[:lower:]' ;;
    esac
}

is_running() {
    command -v pgrep >/dev/null 2>&1 || return 1
    exe=$(product_exe "$1")
    pgrep -x "$exe" >/dev/null 2>&1 && return 0
    pgrep -f "/MacOS/$exe( |\$)|/bin/$exe(\\.sh)?( |\$)" >/dev/null 2>&1
}

# Lists unique IDE dir names (one per line) found in the config and plugin bases.
list_ide_dirs() {
    for base in "$CONFIG_BASE" "$PLUGINS_BASE"; do
        [ -d "$base" ] || continue
        for d in "$base"/*/; do
            [ -d "$d" ] || continue
            basename "$d"
        done
    done | sort -u
}

RESTART=""
note_restart() {
    if is_running "$1"; then
        case " $RESTART " in *" $1 "*) ;; *) RESTART="$RESTART $1" ;; esac
    fi
}

# --- uninstall ---------------------------------------------------------------
if [ "$MODE" = uninstall ]; then
    removed=0
    # IDE dir names never contain spaces; word splitting is intended.
    # shellcheck disable=SC2046
    for name in $(list_ide_dirs); do
        parsed=$(parse_ide_dir "$name")
        [ -n "$parsed" ] || continue
        # shellcheck disable=SC2086
        set -- $parsed
        product_selected "$1" || continue
        target="$PLUGINS_BASE/$name$PLUGINS_SUBDIR/$PLUGIN_DIR_NAME"
        if [ -d "$target" ]; then
            rm -rf "$target"
            say "removed  $name  ($target)"
            removed=$((removed + 1))
            note_restart "$1"
        fi
    done
    [ "$removed" -gt 0 ] || say "Vault Bridge is not installed in any JetBrains IDE."
    [ -z "$RESTART" ] || warn "running IDE(s) need a restart:$RESTART"
    exit 0
fi

# --- install -----------------------------------------------------------------
command -v unzip >/dev/null 2>&1 || die "unzip is required"

TMP=$(mktemp -d 2>/dev/null || mktemp -d -t vault-bridge) || die "mktemp failed"
trap 'rm -rf "$TMP"' EXIT
trap 'exit 130' INT TERM

ZIP="$TMP/$ASSET"
src="${VAULT_BRIDGE_ZIP:-}"
if [ -z "$src" ]; then
    if [ -n "${VAULT_BRIDGE_VERSION:-}" ]; then
        tag="$VAULT_BRIDGE_VERSION"
        case "$tag" in v*) ;; *) tag="v$tag" ;; esac
        src="https://github.com/$REPO/releases/download/$tag/$ASSET"
    else
        src="https://github.com/$REPO/releases/latest/download/$ASSET"
    fi
fi

case "$src" in
    http://*|https://*|file://*)
        command -v curl >/dev/null 2>&1 || die "curl is required (or set VAULT_BRIDGE_ZIP to a local file)"
        say "Downloading $src"
        curl -fsSL --retry 3 --connect-timeout 20 -o "$ZIP" "$src" \
            || die "download failed: $src
       Behind a proxy? export https_proxy=http://proxy:port and retry,
       or download the zip in a browser and run with VAULT_BRIDGE_ZIP=/path/to/zip"
        ;;
    *)
        [ -f "$src" ] || die "VAULT_BRIDGE_ZIP: no such file: $src"
        cp "$src" "$ZIP"
        ;;
esac

unzip -tq "$ZIP" >/dev/null 2>&1 || die "not a valid zip archive: $src"
mkdir "$TMP/x"
unzip -q "$ZIP" -d "$TMP/x"
PAYLOAD="$TMP/x/$PLUGIN_DIR_NAME"
ls "$PAYLOAD"/lib/*.jar >/dev/null 2>&1 \
    || die "unexpected archive layout: expected $PLUGIN_DIR_NAME/lib/*.jar inside $src"
VERSION=""
for jar in "$PAYLOAD"/lib/"$PLUGIN_DIR_NAME"-*.jar; do
    [ -f "$jar" ] || continue
    VERSION=${jar##*/"$PLUGIN_DIR_NAME"-}
    VERSION=${VERSION%.jar}
done
say "Vault Bridge ${VERSION:-(unknown version)}"

installed=0
skipped=""
# shellcheck disable=SC2046
for name in $(list_ide_dirs); do
    parsed=$(parse_ide_dir "$name")
    [ -n "$parsed" ] || continue
    # shellcheck disable=SC2086
    set -- $parsed
    product=$1 year=$2 minor=$3
    product_selected "$product" || continue
    case "$product" in JetBrainsClient|JetBrainsGateway|Toolbox) continue ;; esac
    if [ "$year" -lt "$MIN_YEAR" ] || { [ "$year" -eq "$MIN_YEAR" ] && [ "$minor" -lt "$MIN_MINOR" ]; }; then
        skipped="$skipped $name"
        continue
    fi
    plugins="$PLUGINS_BASE/$name$PLUGINS_SUBDIR"
    mkdir -p "$plugins" || die "cannot create $plugins"
    rm -rf "${plugins:?}/$PLUGIN_DIR_NAME"
    cp -R "$PAYLOAD" "$plugins/$PLUGIN_DIR_NAME"
    say "installed  $name  ->  $plugins/$PLUGIN_DIR_NAME"
    installed=$((installed + 1))
    note_restart "$product"
done

[ -z "$skipped" ] || say "skipped (older than $MIN_YEAR.$MIN_MINOR):$skipped"
if [ "$installed" -eq 0 ]; then
    die "no JetBrains IDE $MIN_YEAR.$MIN_MINOR+ found under $CONFIG_BASE
       Start the IDE once (so it creates its config dir) and re-run,
       or install manually: Settings > Plugins > gear icon > Install Plugin from Disk"
fi
if [ -n "$RESTART" ]; then
    warn "restart these running IDE(s) to load the plugin:$RESTART"
else
    say "Done. Start (or restart) the IDE to load the plugin."
fi
say "Auto-updates: Settings > Plugins > gear icon > Manage Plugin Repositories, add"
say "  https://github.com/$REPO/releases/latest/download/updatePlugins.xml"
