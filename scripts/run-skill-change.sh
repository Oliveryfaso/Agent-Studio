#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage:" >&2
  echo "  scripts/run-skill-change.sh preview <workspace> <request.intent> [--diff]" >&2
  echo "  scripts/run-skill-change.sh apply <workspace> <state-root> <request.intent> <approval-token>" >&2
  echo "  scripts/run-skill-change.sh rollback <workspace> <state-root> <transaction-id>" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action=$1
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
task_build_dir="$repo_root/build/core-skill-change/classes"

case "$task_build_dir" in
  "$repo_root"/build/core-skill-change/classes) ;;
  *) echo "Refusing unexpected build path: $task_build_dir" >&2; exit 1 ;;
esac

request_file=""
case "$action" in
  preview)
    [[ $# -eq 3 || ($# -eq 4 && "$4" == "--diff") ]] || usage
    request_file=$3
    ;;
  apply)
    [[ $# -eq 5 ]] || usage
    request_file=$4
    ;;
  rollback)
    [[ $# -eq 4 ]] || usage
    ;;
  *) usage ;;
esac

if [[ -n "$request_file" && (! -f "$request_file" || -L "$request_file") ]]; then
  echo "Request must be a regular non-symbolic-link file." >&2
  exit 2
fi

if [[ -d "$task_build_dir" ]]; then
  rm -rf -- "$task_build_dir"
fi
mkdir -p -- "$task_build_dir"
cd -- "$repo_root"
source_list="build/core-skill-change/sources.txt"
find "core/src/main/java" -name '*.java' ! -name '._*' -type f -print \
  | LC_ALL=C sort > "$source_list"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror \
  -d "$task_build_dir" "@$source_list"

case "$action" in
  preview)
    if [[ $# -eq 4 ]]; then
      java -cp "$task_build_dir" dev.agentconfig.workbench.cli.Main \
        skill-change-preview codex "$2" --export diff < "$request_file"
    else
      java -cp "$task_build_dir" dev.agentconfig.workbench.cli.Main \
        skill-change-preview codex "$2" < "$request_file"
    fi
    ;;
  apply)
    java -cp "$task_build_dir" dev.agentconfig.workbench.cli.Main \
      skill-change-apply codex "$2" "$3" --approve "$5" < "$request_file"
    ;;
  rollback)
    java -cp "$task_build_dir" dev.agentconfig.workbench.cli.Main \
      skill-change-rollback codex "$2" "$3" "$4"
    ;;
esac
