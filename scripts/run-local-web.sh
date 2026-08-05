#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: scripts/run-local-web.sh <existing-trusted-state-root>" >&2
  exit 2
fi

state_root=$1
if [[ ! -d "$state_root" || -L "$state_root" ]]; then
  echo "State root must be an existing non-symbolic-link directory." >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
ui_root="$repo_root/ui/dist"
task_build_dir="$repo_root/build/core-local-web/classes"

if [[ ! -f "$ui_root/index.html" || -L "$ui_root/index.html" ]]; then
  echo "Built UI is missing. Run scripts/build-ui.sh first." >&2
  exit 2
fi

case "$task_build_dir" in
  "$repo_root"/build/core-local-web/classes) ;;
  *) echo "Refusing unexpected build path: $task_build_dir" >&2; exit 1 ;;
esac

if [[ -d "$task_build_dir" ]]; then
  rm -rf -- "$task_build_dir"
fi
mkdir -p -- "$task_build_dir"
cd -- "$repo_root"
source_list="build/core-local-web/sources.txt"
find "core/src/main/java" -name '*.java' ! -name '._*' -type f -print \
  | LC_ALL=C sort > "$source_list"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror \
  -d "$task_build_dir" "@$source_list"
java -cp "$task_build_dir" dev.agentconfig.workbench.localweb.LocalWorkbenchMain \
  --state-root "$state_root" --ui-root "$ui_root"
