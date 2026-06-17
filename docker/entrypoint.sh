#!/bin/bash
set -e

mkdir -p /root/.local/share/opencode
mkdir -p /root/.openmate

echo "=== OpenMate Bridge Container ==="
echo "opencode path: $(which opencode)"
echo "opencode version: $(opencode --version 2>/dev/null || echo 'unknown')"
echo "Workspace: $(pwd)"
echo "================================"

exec openmate
