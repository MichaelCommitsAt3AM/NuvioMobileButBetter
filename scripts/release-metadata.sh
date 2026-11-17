#!/usr/bin/env bash

set -euo pipefail

version_file="${VERSION_FILE:-iosApp/Configuration/Version.xcconfig}"
target_ref="${1:-HEAD}"

if ! git cat-file -e "${target_ref}^{commit}" 2>/dev/null; then
    echo "Unknown release target: ${target_ref}" >&2
    exit 1
fi

read_value() {
    local commit="$1"
    local key="$2"
    git show "${commit}:${version_file}" \
        | sed -nE "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\\1/p" \
        | head -n 1
}

# Derives the release title from Major.Minor.Patch.Fork: a Fork segment of 1
# means this release is an upstream sync (see scripts/bump-version.sh), any
# other value is a regular fork update.
release_title() {
    local version="$1"
    if [[ "$version" =~ ^([0-9]+\.[0-9]+\.[0-9]+)\.([0-9]+)$ ]]; then
        local base="${BASH_REMATCH[1]}"
        local fork="${BASH_REMATCH[2]}"
        if [[ "$fork" == "1" ]]; then
            printf '%s - Sync with upstream' "$base"
        else
            printf '%s - Fork update %s' "$base" "$fork"
        fi
    else
        printf '%s' "$version"
    fi
}

current_version=""
current_build=""
current_bump=""
previous_version=""
previous_build=""
previous_bump=""

while IFS= read -r commit; do
    version="$(read_value "$commit" MARKETING_VERSION)"
    [[ -n "$version" ]] || continue

    if [[ -z "$current_version" ]]; then
        current_version="$version"
        current_build="$(read_value "$commit" CURRENT_PROJECT_VERSION)"
        current_bump="$commit"
    elif [[ "$version" != "$current_version" ]]; then
        previous_version="$version"
        previous_build="$(read_value "$commit" CURRENT_PROJECT_VERSION)"
        previous_bump="$commit"
        break
    fi
done < <(git log "$target_ref" --format='%H' -- "$version_file")

if [[ -z "$current_bump" || -z "$previous_bump" ]]; then
    echo "Could not find two distinct version bumps in ${version_file}." >&2
    exit 1
fi

if [[ ! "$current_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?(-[0-9A-Za-z.-]+)?$ ]]; then
    echo "Invalid release version: ${current_version}" >&2
    exit 1
fi

if [[ ! "$current_build" =~ ^[0-9]+$ || ! "$previous_build" =~ ^[0-9]+$ ]]; then
    echo "Could not read CURRENT_PROJECT_VERSION from ${version_file}." >&2
    exit 1
fi

if (( 10#$current_build <= 10#$previous_build )); then
    echo "CURRENT_PROJECT_VERSION must increase from ${previous_build} to ${current_build}." >&2
    exit 1
fi

printf 'version=%s\n' "$current_version"
printf 'tag=%s\n' "$current_version"
printf 'title=%s\n' "$(release_title "$current_version")"
printf 'build=%s\n' "$current_build"
printf 'release_commit=%s\n' "$(git rev-parse "${target_ref}^{commit}")"
printf 'current_bump=%s\n' "$current_bump"
printf 'previous_version=%s\n' "$previous_version"
printf 'previous_build=%s\n' "$previous_build"
printf 'previous_bump=%s\n' "$previous_bump"
