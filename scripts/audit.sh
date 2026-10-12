#!/usr/bin/env bash
# Nyx — wrapper de l'audit pré-compilation (règle permanente, RESTART.md §9).
# Usage : scripts/audit.sh [--strict]
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/audit.py --root . "$@"
