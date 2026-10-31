#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_output="$(mktemp -d)"
trap 'rm -rf "$test_output"' EXIT
javac -encoding UTF-8 -d "$test_output" app/src/main/java/com/petricho/threedays/StudyEngine.java tests/StudyEngineTest.java
java -cp "$test_output" StudyEngineTest
