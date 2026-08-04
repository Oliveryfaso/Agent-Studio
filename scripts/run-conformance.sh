#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "Usage: scripts/run-conformance.sh" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
task_build_dir="$repo_root/build/conformance/classes"

case "$task_build_dir" in
  "$repo_root"/build/conformance/classes) ;;
  *) echo "Refusing unexpected build path: $task_build_dir" >&2; exit 1 ;;
esac

if [[ -d "$task_build_dir" ]]; then
  rm -rf -- "$task_build_dir"
fi
mkdir -p -- "$task_build_dir"

source_list="$repo_root/build/conformance/sources.txt"
find "$repo_root/core/src/main/java" "$repo_root/core/src/test/java" \
  -name '*.java' ! -name '._*' -type f -print | LC_ALL=C sort > "$source_list"

javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$task_build_dir" "@$source_list"

run_suite() {
  local suite_class=$1
  local suite_output
  local suite_count
  suite_output=$(java -cp "$task_build_dir" "$suite_class")
  printf '%s\n' "$suite_output" >&2
  suite_count=$(printf '%s\n' "$suite_output" | awk '/^PASS / { count++ } END { print count + 0 }')
  if [[ ! "$suite_count" =~ ^[1-9][0-9]*$ ]]; then
    echo "Conformance suite reported no cases: $suite_class" >&2
    exit 1
  fi
  printf '%s' "$suite_count"
}

codex_count=$(run_suite dev.agentconfig.workbench.CodexConformanceTests)
claude_count=$(run_suite dev.agentconfig.workbench.ClaudeConformanceTests)
analyzer_count=$(run_suite dev.agentconfig.workbench.AnalyzerAdversarialTests)
total_count=$((codex_count + claude_count + analyzer_count))

printf '{\n'
printf '  "schemaVersion": 1,\n'
printf '  "status": "PASS",\n'
printf '  "totalCases": %d,\n' "$total_count"
printf '  "profiles": [\n'
printf '    {"id":"codex-project-semantics-v1","hostId":"codex","status":"PASS","cases":%d},\n' "$codex_count"
printf '    {"id":"claude-code-project-semantics-v1","hostId":"claude-code","status":"PASS","cases":%d},\n' "$claude_count"
printf '    {"id":"instruction-analysis-v1","hostId":null,"status":"PASS","cases":%d}\n' "$analyzer_count"
printf '  ]\n'
printf '}\n'
