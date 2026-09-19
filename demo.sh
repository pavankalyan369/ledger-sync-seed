#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")"

# Use the Windows Gradle wrapper when running from Git Bash on Windows.
GRADLEW="./gradlew.bat"

echo "==> starting fresh DynamoDB Local"
docker compose down
docker compose up -d

echo
echo "==> preparing clean SQL database"
rm -f \
  data/ledger.mv.db \
  data/ledger.trace.db

echo
echo "==> running SQL migration"
"$GRADLEW" run --args="migrate"

echo
echo "==> ingesting corpus-a"
"$GRADLEW" run --args="ingest fixtures/corpus-a.jsonl"

echo
echo "==> proving repeated ingestion is idempotent"
"$GRADLEW" run --args="ingest fixtures/corpus-a.jsonl"

echo
echo "==> writing submission reports"
rm -rf output
"$GRADLEW" run --args="report output"

echo
echo "==> backfilling SQL into DynamoDB"
"$GRADLEW" run --args="backfill"

echo
echo "==> proving backfill is idempotent"
"$GRADLEW" run --args="backfill"

echo
echo "==> checking SQL and DynamoDB consistency"
"$GRADLEW" run --args="check"

echo
echo "==> running tests"
"$GRADLEW" clean test

echo
echo "==> running dependency-free verifier"
./verify.sh

echo
echo "==> demo complete"