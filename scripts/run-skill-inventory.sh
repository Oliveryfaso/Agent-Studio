#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: scripts/run-skill-inventory.sh <authorized-workspace>" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
task_build_dir="$repo_root/build/core-skill-inventory/classes"

case "$task_build_dir" in
  "$repo_root"/build/core-skill-inventory/classes) ;;
  *) echo "Refusing unexpected build path: $task_build_dir" >&2; exit 1 ;;
esac

if [[ -d "$task_build_dir" ]]; then
  rm -rf -- "$task_build_dir"
fi
mkdir -p -- "$task_build_dir"
cd -- "$repo_root"

source_list="build/core-skill-inventory/sources.txt"
find "core/src/main/java" -name '*.java' ! -name '._*' -type f -print \
  | LC_ALL=C sort > "$source_list"

javac --release 21 -encoding UTF-8 -Xlint:all -Werror \
  -d "build/core-skill-inventory/classes" "@$source_list"
java -cp "build/core-skill-inventory/classes" \
  dev.agentconfig.workbench.cli.Main skill-inventory codex "$1"
