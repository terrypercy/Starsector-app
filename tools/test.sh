#!/bin/bash
# Build, then run the unit tests, the ported mock tests and the class-load check.
# Usage: C=<starsector-core dir> OUT=<output dir> tools/test.sh   (run from the repo root)
set -e
: "${C:?set C to starsector-core}" "${OUT:?set OUT}"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"
C="$C" OUT="$OUT" bash tools/build.sh
CP="$C/starfarer.api.jar:$C/json.jar:$C/log4j-1.2.9.jar:$C/lwjgl.jar:$C/lwjgl_util.jar:$OUT/classes:$OUT/stubs"
rm -rf "$OUT/tests" && mkdir -p "$OUT/tests"
javac -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT/tests" tests/*.java
for t in CaptainTest PlannerTest ReaderTest AdvisorTest TradeTest; do
  echo "== $t"; java -cp "$OUT/tests:$CP" $t | tail -3
done
echo "== LoadAll"; java -cp "$OUT/tests:$CP" LoadAll "$OUT/claude_captain.jar"
