#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
task_build_dir="$repo_root/build/core-test/classes"

case "$task_build_dir" in
  "$repo_root"/build/core-test/classes) ;;
  *) echo "Refusing unexpected build path: $task_build_dir" >&2; exit 1 ;;
esac

if [[ -d "$task_build_dir" ]]; then
  rm -rf -- "$task_build_dir"
fi
mkdir -p -- "$task_build_dir"

source_list="$repo_root/build/core-test/sources.txt"
find "$repo_root/core/src/main/java" "$repo_root/core/src/test/java" \
  -name '*.java' ! -name '._*' -type f -print | LC_ALL=C sort > "$source_list"

javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$task_build_dir" "@$source_list"
test_mains=(
  dev.agentconfig.workbench.PhaseOneTests
  dev.agentconfig.workbench.ScanControlTests
  dev.agentconfig.workbench.GitMetadataTests
  dev.agentconfig.workbench.PlatformSafetyTests
  dev.agentconfig.workbench.CliHardeningTests
  dev.agentconfig.workbench.EffectiveContextTests
  dev.agentconfig.workbench.CodexProjectOptionsTests
  dev.agentconfig.workbench.ClaudeSemanticsTests
  dev.agentconfig.workbench.EffectiveContextAdvancedTests
  dev.agentconfig.workbench.InstructionIrTests
  dev.agentconfig.workbench.DirectiveAnalyzerTests
  dev.agentconfig.workbench.InstructionAnalysisTests
  dev.agentconfig.workbench.AnalysisCliTests
  dev.agentconfig.workbench.InspectionCliTests
  dev.agentconfig.workbench.CodexConformanceTests
  dev.agentconfig.workbench.ClaudeConformanceTests
  dev.agentconfig.workbench.AnalyzerAdversarialTests
  dev.agentconfig.workbench.ConversionPlanSchemaTests
  dev.agentconfig.workbench.ConversionPlannerTests
  dev.agentconfig.workbench.ConversionPreviewCliTests
)

for test_main in "${test_mains[@]}"; do
  java -cp "$task_build_dir" "$test_main"
done
