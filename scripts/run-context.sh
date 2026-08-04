#!/usr/bin/env bash
set -euo pipefail

if [[ ($# -ne 3 && $# -ne 5) || ("$1" != "codex" && "$1" != "claude-code") ]]; then
  echo "Usage: scripts/run-context.sh codex <authorized-workspace> <current-directory> [--codex-config <snapshot.toml>]" >&2
  echo "   or: scripts/run-context.sh claude-code <authorized-workspace> <current-directory> [--target-file <project-relative-file>]" >&2
  exit 2
fi

if [[ $# -eq 5 ]]; then
  if [[ "$1" == "codex" && "$4" != "--codex-config" ]]; then
    echo "Codex accepts only --codex-config as its optional argument." >&2
    exit 2
  fi
  if [[ "$1" == "claude-code" && "$4" != "--target-file" ]]; then
    echo "Claude Code accepts only --target-file as its optional argument." >&2
    exit 2
  fi
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
java -cp "$task_build_dir" dev.agentconfig.workbench.cli.Main context "$@"
