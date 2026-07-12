#!/usr/bin/env bash
#
# install-bundled-icons.sh — bundle the GNOME/Adwaita icons the app uses under an
# app-id-prefixed name so a host icon theme cannot override them (which otherwise
# breaks the app's layout).
#
# The list of icons is driven entirely by the Icons enum
# (src/main/java/org/subsound/ui/components/Icons.java), the single source of truth.
# Each icon "foo" is installed as "<APP_ID>.foo.svg" into <hicolor>/scalable/actions/ .
# The copied name still ends in "-symbolic" for symbolic icons, so GTK keeps recoloring
# them. We install into the standard hicolor theme (which already declares
# scalable/actions), so no custom theme or index.theme is needed.
#
# Source SVGs come from the build/host icon themes (the org.gnome.Sdk image during a
# flatpak build, or the host Adwaita theme locally), so no GNOME icons are committed
# into the repo.
#
# Usage:
#   install-bundled-icons.sh <TARGET_HICOLOR_DIR>
#
# The flatpak manifest calls it with /app/share/icons/hicolor.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"

ICONS_JAVA="$REPO_ROOT/src/main/java/org/subsound/ui/components/Icons.java"
CONSTANTS_JAVA="$REPO_ROOT/src/main/java/org/subsound/configuration/constants/Constants.java"
REPO_THEME="$REPO_ROOT/src/main/resources/icons/hicolor"

if [[ $# -lt 1 ]]; then
    echo "usage: install-bundled-icons.sh <TARGET_HICOLOR_DIR>" >&2
    exit 2
fi
TARGET_THEME="$1"
DEST="$TARGET_THEME/scalable/actions"

# App id (reverse-DNS prefix), parsed from Constants.java so it stays in sync.
APP_ID="$(grep -oP 'APP_ID\s*=\s*"\K[^"]+' "$CONSTANTS_JAVA")"
if [[ -z "$APP_ID" ]]; then
    echo "error: could not parse APP_ID from $CONSTANTS_JAVA" >&2
    exit 1
fi

# Enumerate icon names straight out of the Icons enum: lines like  Name("icon-name"),
mapfile -t NAMES < <(grep -oP '^\s*[A-Za-z_][A-Za-z0-9_]*\("\K[^"]+' "$ICONS_JAVA" | sort -u)
if [[ ${#NAMES[@]} -eq 0 ]]; then
    echo "error: no icon names parsed from $ICONS_JAVA" >&2
    exit 1
fi

# Where to look for the source SVGs, in priority order. The repo's own theme comes
# first so app-provided custom icons (e.g. checkmark-circle-symbolic) win.
SOURCE_ROOTS=(
    "$REPO_THEME"
    /usr/share/icons/Adwaita
    /usr/share/icons/hicolor
)

mkdir -p "$DEST"

copied=0
missing=()
for name in "${NAMES[@]}"; do
    dest_file="$DEST/${APP_ID}.${name}.svg"
    src=""
    for root in "${SOURCE_ROOTS[@]}"; do
        [[ -d "$root" ]] || continue
        src="$(find "$root" -type f -name "$name.svg" ! -path "$DEST/*" 2>/dev/null | head -1)"
        [[ -n "$src" ]] && break
    done
    if [[ -z "$src" ]]; then
        missing+=("$name")
        continue
    fi
    install -m 0644 -D "$src" "$dest_file"
    copied=$((copied + 1))
done

# Refresh the icon cache (best effort; not fatal if unavailable).
if command -v gtk4-update-icon-cache >/dev/null 2>&1; then
    gtk4-update-icon-cache -q -t -f "$TARGET_THEME" || true
fi

echo "install-bundled-icons: copied $copied icon(s) to $DEST (prefix: $APP_ID)"
if [[ ${#missing[@]} -gt 0 ]]; then
    echo "install-bundled-icons: no source SVG found for: ${missing[*]}" >&2
    echo "  (these fall back to the un-prefixed name at runtime)" >&2
fi