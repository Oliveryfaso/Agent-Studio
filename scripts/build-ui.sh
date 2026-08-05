#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "Usage: scripts/build-ui.sh" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)

cd -- "$repo_root"
npm ci --prefix ui --cache "$repo_root/build/npm-cache"
npm run build --prefix ui

if [[ ! -f "$repo_root/ui/dist/index.html" || -L "$repo_root/ui/dist/index.html" ]]; then
  echo "UI build did not produce a regular index.html." >&2
  exit 1
fi

echo "Built local UI: $repo_root/ui/dist"
