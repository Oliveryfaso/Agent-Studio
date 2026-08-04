#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 || ($# -eq 2 && "$2" != "--git-metadata") ]]; then
  echo "Usage: scripts/run-cli.sh <authorized-workspace> [--git-metadata]" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
task_build_dir="$repo_root/build/core-cli/classes"

case "$task_build_dir" in
  "$repo_root"/build/core-cli/classes) ;;
  *) echo "Refusing unexpected build path: $task_build_dir" >&2; exit 1 ;;
esac

if [[ -d "$task_build_dir" ]]; then
  rm -rf -- "$task_build_dir"
fi
mkdir -p -- "$task_build_dir"

source_list="$repo_root/build/core-cli/sources.txt"
find "$repo_root/core/src/main/java" -name '*.java' ! -name '._*' -type f -print | LC_ALL=C sort > "$source_list"

javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$task_build_dir" "@$source_list"
java -cp "$task_build_dir" dev.agentconfig.workbench.cli.Main scan "$@"
