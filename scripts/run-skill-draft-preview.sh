#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 && $# -ne 3 ]]; then
  echo "Usage: scripts/run-skill-draft-preview.sh <guided-request.intent> [--export <content|diff|prompt>]" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
request_file=$1
task_build_dir="$repo_root/build/core-skill-draft-preview/classes"

if [[ ! -f "$request_file" || -L "$request_file" ]]; then
  echo "Request must be a regular non-symbolic-link file." >&2
  exit 2
fi

case "$task_build_dir" in
  "$repo_root"/build/core-skill-draft-preview/classes) ;;
  *) echo "Refusing unexpected build path: $task_build_dir" >&2; exit 1 ;;
esac

if [[ -d "$task_build_dir" ]]; then
  rm -rf -- "$task_build_dir"
fi
mkdir -p -- "$task_build_dir"
cd -- "$repo_root"

source_list="build/core-skill-draft-preview/sources.txt"
find "core/src/main/java" -name '*.java' ! -name '._*' -type f -print \
  | LC_ALL=C sort > "$source_list"

javac --release 21 -encoding UTF-8 -Xlint:all -Werror \
  -d "$task_build_dir" "@$source_list"
if [[ $# -eq 1 ]]; then
  java -cp "$task_build_dir" dev.agentconfig.workbench.cli.Main \
    skill-draft-preview codex < "$request_file"
else
  java -cp "$task_build_dir" dev.agentconfig.workbench.cli.Main \
    skill-draft-preview codex "$2" "$3" < "$request_file"
fi
