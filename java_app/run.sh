#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ ! -x "$ROOT_DIR/db_server" ]]; then
  echo "[NexusVault] Building C DBMS server..."
  make -C "$ROOT_DIR" db_server
fi

mkdir -p "$SCRIPT_DIR/out"
echo "[NexusVault] Compiling Java 21 sources..."
find "$SCRIPT_DIR/src" -name "*.java" -print0 | xargs -0 javac -d "$SCRIPT_DIR/out"

cd "$SCRIPT_DIR"
exec java -cp "$SCRIPT_DIR/out" com.nexusvault.Main "${1:-8090}" "${2:-8088}" "${3:-nexusvault.db}"
