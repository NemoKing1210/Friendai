#!/bin/sh
# Checks the repository guides for internal links that no longer resolve.
#
#   scripts/check-docs.sh [file...]
#
# Defaults to AGENTS.md, ARCHITECTURE.md, CONTRIBUTING.md, README.md and MANUAL_CHECKLIST.md. Every
# relative link must point at an existing path, and every #anchor must match a heading of its file
# (GitHub slug rules: lowercase, punctuation dropped, spaces turned into hyphens). External links are
# not fetched: CI has no network dependency, and a moved web page is not this repository's problem.
set -eu

root=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

if [ "$#" -eq 0 ]; then
    set -- AGENTS.md ARCHITECTURE.md CONTRIBUTING.md README.md MANUAL_CHECKLIST.md
fi

errors=$(mktemp)
trap 'rm -f "$errors"' EXIT

# GitHub's heading slug: lowercase, drop everything that is not a letter, digit, space, hyphen or
# underscore, then replace spaces with hyphens.
slug() {
    printf '%s\n' "$1" \
        | tr '[:upper:]' '[:lower:]' \
        | sed -e 's/[^a-z0-9 _-]//g' -e 's/ /-/g'
}

anchors_of() {
    grep -E '^#{1,6} ' "$1" | sed -E 's/^#{1,6}[[:space:]]+//' | while IFS= read -r heading; do slug "$heading"; done
}

for file in "$@"; do
    if [ ! -f "$file" ]; then
        echo "check-docs: missing file $file" >>"$errors"
        continue
    fi
    file_anchors=$(anchors_of "$file")
    links=$(grep -oE '\[[^]]*\]\([^)]*\)' "$file" || true)
    printf '%s\n' "$links" | while IFS= read -r link; do
        [ -n "$link" ] || continue
        target=$(printf '%s\n' "$link" | sed -E 's/^\[[^]]*\]\((.*)\)$/\1/')
        target=${target#<}
        target=${target%>}
        case "$target" in
            '' | http://* | https://* | mailto:*) continue ;;
        esac
        path=${target%%#*}
        anchor=
        case "$target" in
            *'#'*) anchor=${target#*#} ;;
        esac
        resolved=
        if [ -n "$path" ]; then
            case "$path" in
                /*) resolved=$path ;;
                *) resolved=$(dirname -- "$file")/$path ;;
            esac
            if [ ! -e "$resolved" ]; then
                echo "check-docs: $file: '$target' points at a missing path" >>"$errors"
                continue
            fi
        fi
        [ -n "$anchor" ] || continue
        if [ -n "$path" ]; then
            if [ -f "$resolved" ] && [ "${resolved##*.}" = "md" ]; then
                checked_anchors=$(anchors_of "$resolved")
            else
                continue
            fi
        else
            checked_anchors=$file_anchors
        fi
        if ! printf '%s\n' "$checked_anchors" | grep -Fxq -- "$anchor"; then
            echo "check-docs: $file: '#$anchor' matches no heading in ${path:-$file}" >>"$errors"
        fi
    done
done

if [ -s "$errors" ]; then
    cat "$errors" >&2
    exit 1
fi
printf 'check-docs: %s file(s) checked, all internal links resolve\n' "$#"
