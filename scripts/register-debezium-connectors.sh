#!/usr/bin/env bash
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8085}"

for file in debezium/*-outbox-connector.json; do
  echo "Registering $file"
  curl -sS -X POST "$CONNECT_URL/connectors"     -H "Content-Type: application/json"     --data @"$file" || true
  echo
done

echo "Done."
