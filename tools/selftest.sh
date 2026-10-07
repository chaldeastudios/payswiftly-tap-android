#!/usr/bin/env bash
# Compiles and runs the NFC protocol self-test. Needs only a JDK (11+); no Android SDK.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" app/src/main/java/ke/co/payswiftly/tap/Type4Tag.java tools/Type4TagSelfTest.java
java -cp "$OUT" Type4TagSelfTest
