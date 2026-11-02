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
# Every suite runs even after a failure; the script exits 1 if any suite failed.
# A suite's exit code is checked directly: piping it into tail would hide it, and
# tail would also drop all but its last few FAIL lines.
failed=""
run() {  # run <name> <java args...>: full output to $OUT/tests/<name>.log, FAIL lines + summary to screen
  local name="$1"; shift
  echo "== $name"
  if java -cp "$OUT/tests:$CP" "$@" > "$OUT/tests/$name.log" 2>&1; then rc=0; else rc=$?; fi
  grep -E '^(FAIL|cannot load)' "$OUT/tests/$name.log" || true
  tail -1 "$OUT/tests/$name.log"
  if [ "$rc" -ne 0 ]; then echo "   exit $rc (log: $OUT/tests/$name.log)"; failed="$failed $name"; fi
}
for t in CaptainTest PlannerTest ReaderTest AdvisorTest TradeTest; do run "$t" "$t"; done
run LoadAll LoadAll "$OUT/claude_captain.jar"
if [ -n "$failed" ]; then echo "TESTS FAILED:$failed"; exit 1; fi
echo "ALL TESTS PASSED"
