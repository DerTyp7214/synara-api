#!/bin/bash

cd "$(git rev-parse --show-toplevel)" || exit 0

mapfile -d '' staged < <(git diff --cached --name-only -z --diff-filter=ACMR -- '*.kt' '*.kts')
if [ "${#staged[@]}" -eq 0 ]; then
    exit 0
fi

files=()
for f in "${staged[@]}"; do
    if [ -n "$(git diff --name-only -- "$f")" ]; then
        echo "Kotlin formatting skipped for $f (has unstaged changes)"
    else
        files+=("$f")
    fi
done
if [ "${#files[@]}" -eq 0 ]; then
    exit 0
fi

root="$(git rev-parse --show-superproject-working-tree)"
if [ -z "$root" ]; then
    root="$(git rev-parse --show-toplevel)"
fi
style="$root/.editorconfig"
if [ ! -f "$style" ]; then
    echo "Kotlin formatting skipped: code style $style not found"
    exit 0
fi

if ! command -v ktlint > /dev/null 2>&1; then
    echo "Kotlin formatting skipped: ktlint not found"
    exit 0
fi

echo "Formatting ${#files[@]} Kotlin file(s)..."
if ! ktlint --format --editorconfig="$style" --log-level=error "${files[@]}"; then
    echo "ktlint reported findings it could not fix, committing what it formatted"
fi
git add -- "${files[@]}"
echo "Formatted and restaged ${#files[@]} file(s)"

exit 0
