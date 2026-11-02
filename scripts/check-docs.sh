#!/bin/sh
# Checks the repository guides for internal links and tables that no longer hold together.
#
#   scripts/check-docs.sh [file...]
#
# Defaults to AGENTS.md, ARCHITECTURE.md, CONTRIBUTING.md, README.md, MANUAL_CHECKLIST.md and the pull
# request template. Every relative link must point at an existing path, every #anchor must match a
# heading of its file (GitHub slug rules: lowercase, punctuation dropped, spaces turned into hyphens),
# and every table row must have the same number of cells as its header. External links are not
# fetched: CI has no network dependency, and a moved web page is not this repository's problem.
set -eu

root=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

if [ "$#" -eq 0 ]; then
    set -- AGENTS.md ARCHITECTURE.md CONTRIBUTING.md README.md MANUAL_CHECKLIST.md .github/PULL_REQUEST_TEMPLATE.md
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

    # A row whose cell count differs from its header shifts every column after it. Escaped pipes
    # belong to the cell text, and a fenced block is not a table even when a diagram starts with one.
    awk -v name="$file" '
        function cells(s,   t) {
            t = s
            gsub(/\\\|/, "", t)
            return gsub(/\|/, "|", t) - 1
        }
        /^[[:space:]]*```/ { fenced = !fenced; open = 0; next }
        fenced { next }
        /^[[:space:]]*\|/ {
            n = cells($0)
            if (!open) { columns = n; open = 1; next }
            if (n != columns) {
                printf "check-docs: %s:%d: table row has %d cells, header has %d\n", name, FNR, n, columns
            }
            next
        }
        { open = 0 }
    ' "$file" >>"$errors"
done

if [ -s "$errors" ]; then
    cat "$errors" >&2
    exit 1
fi
printf 'check-docs: %s file(s) checked, links resolve and tables are well formed\n' "$#"
