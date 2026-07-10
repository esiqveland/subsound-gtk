#!/usr/bin/env bash
# Extract translatable strings from Java sources into the .pot template,
# then merge into every .po catalog listed in LINGUAS (creating missing ones).
set -euo pipefail
cd "$(dirname "$0")/.."

DOMAIN=io.github.subsoundorg.Subsound
POT=po/$DOMAIN.pot

find src/main/java -name '*.java' | LC_ALL=C sort > po/POTFILES.tmp
trap 'rm -f po/POTFILES.tmp' EXIT

xgettext \
    --language=Java --from-code=UTF-8 \
    -k \
    --keyword=tr:1 \
    --keyword=trn:1,2 \
    --keyword=trc:1c,2 \
    --flag=tr:1:java-printf-format \
    --flag=trn:1:java-printf-format \
    --flag=trn:2:java-printf-format \
    --flag=trc:2:java-printf-format \
    --add-comments=TRANSLATORS: \
    --package-name=Subsound \
    --msgid-bugs-address=https://github.com/subsoundorg/subsound-gtk/issues \
    --files-from=po/POTFILES.tmp \
    --sort-by-file \
    -o "$POT"

while read -r lang; do
    if [ -z "$lang" ]; then
        continue
    fi
    if [ -f "po/$lang.po" ]; then
        msgmerge --update --backup=none "po/$lang.po" "$POT"
    else
        msginit --no-translator --locale="$lang" --input="$POT" --output-file="po/$lang.po"
    fi
done < po/LINGUAS
