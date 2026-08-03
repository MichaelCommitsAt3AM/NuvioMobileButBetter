#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="$ROOT_DIR/iosApp/Configuration/Version.xcconfig"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/bump-version.sh fork
      Cut a regular fork release: increments the fork number only,
      e.g. 0.3.1.1 -> 0.3.1.2. Use this for every release that is not
      immediately after an upstream merge.

  ./scripts/bump-version.sh sync-upstream <X.Y.Z>
      Cut a release right after merging upstream: sets the version to
      <X.Y.Z>.1, matching upstream's version and resetting the fork
      number.

  ./scripts/bump-version.sh sync-upstream [--ref <git-ref>]
      Same as above, but read <X.Y.Z> from MARKETING_VERSION in
      iosApp/Configuration/Version.xcconfig at <git-ref> instead of
      passing it explicitly. Defaults to upstream/<current-branch>
      when --ref is omitted.

In both cases:
  - CURRENT_PROJECT_VERSION (the Android versionCode / iOS build
    number) is always incremented by exactly 1 and is never reset.
    It must keep increasing monotonically regardless of what
    MARKETING_VERSION does, or installs/in-app updates stop working.
  - The working tree must be clean. The version bump is committed and
    tagged locally (tag name == the new MARKETING_VERSION). Nothing
    is pushed and nothing is built - this only manages the version.

This fork only ships Android releases. After running this script,
build and upload the Android release yourself, e.g.:
  NUVIO_ANDROID_DISTRIBUTION=full ./gradlew :androidApp:bundleFullRelease
  NUVIO_ANDROID_DISTRIBUTION=full ./gradlew :androidApp:assembleFullRelease
EOF
}

read_xcconfig_value() {
  local file="$1" key="$2"
  grep -E "^${key}=" "$file" | tail -n1 | cut -d'=' -f2- | tr -d '\r\n'
}

file_uses_crlf() {
  local file="$1"
  LC_ALL=C grep -qU $'\r' "$file"
}

# Writes multiple key=value pairs as one sed invocation. This must stay a
# single invocation: on this platform, each separate `sed -i` pass silently
# drops the `\r` from every *other* line it reads through (not just the one
# it edits), so two sequential calls end up clobbering each other's CRLF.
write_xcconfig_values() {
  local file="$1"
  shift
  local eol=""
  if file_uses_crlf "$file"; then
    eol="$(printf '\r')"
  fi

  local -a sed_args=(-i -E)
  local pair key value
  for pair in "$@"; do
    key="${pair%%=*}"
    value="${pair#*=}"
    # `.*` also consumes the line's existing `\r` (it is not a newline),
    # so the replacement re-adds it explicitly to keep CRLF intact.
    sed_args+=(-e "s/^(${key})=.*/\\1=${value}${eol}/")
  done

  sed "${sed_args[@]}" "$file"
}

require_clean_worktree() {
  if [[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]]; then
    echo "error: working tree is not clean. Commit or stash your changes before bumping the version." >&2
    exit 1
  fi
}

require_tag_available() {
  local tag="$1"
  if git -C "$ROOT_DIR" rev-parse --verify -q "refs/tags/${tag}" >/dev/null; then
    echo "error: tag '${tag}' already exists. Nothing was changed." >&2
    exit 1
  fi
}

current_marketing_version="$(read_xcconfig_value "$VERSION_FILE" MARKETING_VERSION)"
current_build_number="$(read_xcconfig_value "$VERSION_FILE" CURRENT_PROJECT_VERSION)"

if [[ ! "$current_build_number" =~ ^[0-9]+$ ]]; then
  echo "error: CURRENT_PROJECT_VERSION in $VERSION_FILE is not a plain integer ('$current_build_number')." >&2
  exit 1
fi

command="${1:-}"
new_marketing_version=""

case "$command" in
  fork)
    if [[ $# -gt 1 ]]; then
      echo "error: 'fork' takes no arguments." >&2
      usage
      exit 1
    fi

    if [[ "$current_marketing_version" =~ ^([0-9]+\.[0-9]+\.[0-9]+)\.([0-9]+)$ ]]; then
      base_version="${BASH_REMATCH[1]}"
      fork_number="${BASH_REMATCH[2]}"
    else
      echo "error: current MARKETING_VERSION ('$current_marketing_version') is not in X.Y.Z.N format." >&2
      echo "Run './scripts/bump-version.sh sync-upstream <X.Y.Z>' first to establish fork numbering." >&2
      exit 1
    fi

    new_marketing_version="${base_version}.$((fork_number + 1))"
    ;;

  sync-upstream)
    shift
    target_version=""
    ref=""

    while [[ $# -gt 0 ]]; do
      case "$1" in
        --ref)
          if [[ $# -lt 2 ]]; then
            echo "error: --ref requires a value." >&2
            exit 1
          fi
          ref="$2"
          shift 2
          ;;
        -h|--help)
          usage
          exit 0
          ;;
        *)
          if [[ -n "$target_version" ]]; then
            echo "error: unexpected argument '$1'." >&2
            usage
            exit 1
          fi
          target_version="$1"
          shift
          ;;
      esac
    done

    if [[ -z "$target_version" ]]; then
      if [[ -z "$ref" ]]; then
        ref="upstream/$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)"
      fi

      echo "Reading upstream version from '$ref'..." >&2
      upstream_xcconfig="$(git -C "$ROOT_DIR" show "$ref:iosApp/Configuration/Version.xcconfig" 2>/dev/null)" || {
        echo "error: could not read iosApp/Configuration/Version.xcconfig at ref '$ref'." >&2
        echo "Fetch upstream first (git fetch upstream), or pass the version explicitly:" >&2
        echo "  ./scripts/bump-version.sh sync-upstream <X.Y.Z>" >&2
        exit 1
      }

      target_version="$(printf '%s\n' "$upstream_xcconfig" | grep -E '^MARKETING_VERSION=' | tail -n1 | cut -d'=' -f2- | tr -d '\r\n')"
      # Defensive: strip a trailing .N fork segment if the ref happens to carry one.
      if [[ "$target_version" =~ ^([0-9]+\.[0-9]+\.[0-9]+)\.[0-9]+$ ]]; then
        target_version="${BASH_REMATCH[1]}"
      fi
    fi

    if [[ ! "$target_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "error: resolved version '$target_version' is not a plain X.Y.Z version." >&2
      exit 1
    fi

    new_marketing_version="${target_version}.1"
    ;;

  -h|--help|"")
    usage
    exit 0
    ;;

  *)
    echo "error: unknown command '$command'." >&2
    usage
    exit 1
    ;;
esac

require_clean_worktree
require_tag_available "$new_marketing_version"

new_build_number=$((current_build_number + 1))

write_xcconfig_values "$VERSION_FILE" \
  "MARKETING_VERSION=${new_marketing_version}" \
  "CURRENT_PROJECT_VERSION=${new_build_number}"

git -C "$ROOT_DIR" add "$VERSION_FILE"
git -C "$ROOT_DIR" commit -m "chore: bump version to ${new_marketing_version} (build ${new_build_number})"
git -C "$ROOT_DIR" tag "$new_marketing_version"

cat <<EOF

Bumped: ${current_marketing_version} (build ${current_build_number}) -> ${new_marketing_version} (build ${new_build_number})
Committed and tagged '${new_marketing_version}' locally.

Next steps:
  git push origin HEAD "${new_marketing_version}"
  NUVIO_ANDROID_DISTRIBUTION=full ./gradlew :androidApp:bundleFullRelease
  NUVIO_ANDROID_DISTRIBUTION=full ./gradlew :androidApp:assembleFullRelease
  Upload the AAB/APK to a GitHub Release for tag ${new_marketing_version}.
  (This fork does not build or publish iOS releases.)
EOF
