#!/usr/bin/env bash
# Runs the in-memory pipeline against fixtures/corpus-a.jsonl.
# Uses the project's Gradle runtime classpath so external dependencies
# such as the AWS SDK are available.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> running self-check"
./gradlew.bat selfCheck "$@"