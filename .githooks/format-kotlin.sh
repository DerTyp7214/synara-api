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
style="$root/.idea/codeStyles/Project.xml"
if [ ! -f "$style" ]; then
    echo "Kotlin formatting skipped: code style $style not found"
    exit 0
fi

formatter=""
if [ -n "$IDEA_FORMATTER" ] && [ -x "$IDEA_FORMATTER" ]; then
    formatter="$IDEA_FORMATTER"
elif [ -x "$HOME/.local/share/JetBrains/Toolbox/scripts/idea" ]; then
    formatter="$HOME/.local/share/JetBrains/Toolbox/scripts/idea"
elif command -v idea > /dev/null 2>&1; then
    formatter="$(command -v idea)"
fi
if [ -z "$formatter" ]; then
    echo "Kotlin formatting skipped: IntelliJ not found"
    exit 0
fi

echo "Formatting ${#files[@]} Kotlin file(s)..."
if "$formatter" format -s "$style" "${files[@]}"; then
    git add -- "${files[@]}"
    echo "Formatted and restaged ${#files[@]} file(s)"
else
    echo "Kotlin formatting failed (is IntelliJ open?), committing unformatted"
fi

exit 0
