#!/usr/bin/env bash
# check-commit-msg.sh — validate a commit subject against Conventional Commits.
# Usage: check-commit-msg.sh <path-to-commit-msg-file>

set -euo pipefail

# Allowed types come from the shared list (also read by the pr-title CI job), joined into a regex
# alternation: feat|fix|docs|...
types_file="$(dirname "$0")/commit-types.txt"
types="$(grep -vE '^[[:space:]]*#|^[[:space:]]*$' "$types_file" | paste -sd '|' -)"

subject="$(sed -n '/^[^#]/{p;q;}' "${1:?commit message file required}")"

# git's generated subjects (merges, reverts, fixups) aren't author-written.
case "$subject" in
    "Merge "* | "Revert "* | "fixup! "* | "squash! "*) exit 0 ;;
esac

if [ "${#subject}" -gt 72 ]; then
    echo "commit-msg: subject exceeds 72 characters (${#subject})." >&2
    echo "  $subject" >&2
    exit 1
fi

if ! printf '%s' "$subject" | grep -qE "^(${types})(\([^)]+\))?!?: .+"; then
    echo "commit-msg: subject must follow Conventional Commits." >&2
    echo "  expected: <type>(<scope>): <subject>" >&2
    echo "  types:    ${types//|/, }" >&2
    echo "  got:      ${subject:-<empty>}" >&2
    exit 1
fi
