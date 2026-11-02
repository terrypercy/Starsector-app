#!/bin/bash
# java/test-run: run the mod's test suite (tools/test.sh) and report what happened as TSV rows.
#
# Usage: bash .claude/java/scripts/test-run.sh [--core <starsector-core>] [--out <dir>] [--log <file>]
#   --core  starsector-core folder (else $C, else engine.path in project.local.yaml / project.yaml)
#   --out   build output folder (else $OUT, else ./out)
#   --log   where the full output goes (else <out>/test-run.log)
#
# Observations only, never a verdict (CLAUDE.md: helpers in .claude/scripts emit
# observations). The java-test skill reads the rows and decides.
#
# Output rows (tab-separated), in this order; a row that cannot apply is left out:
#   core       <path|->          <--core|C|engine.path|unset>
#   core_jar   <present|absent>  <path to starfarer.api.jar>
#   ran        <yes|no>          <why not, or ->
#   exit       <code of tools/test.sh>
#   gate       <fixed|old|unknown>   whether tools/test.sh checks suite exit codes
#   suite      <name>  <summary line, or ->  <exit code, or - when none was printed (tools/test.sh prints one only on failure)>
#   fail       <a FAIL: / cannot load line>   (up to 20, then fail_more <n>)
#   build      <ok|failed|unknown>  <FORBIDDEN or javac error line, or ->
#   final      <ALL TESTS PASSED|TESTS FAILED:...|->
#   log        <path>
#
# Exit codes: 0 the helper finished (read the rows, whatever the tests did);
# 2 usage error. It never edits files outside --out and never prompts.

set -u
LC_ALL=C; export LC_ALL
usage() { echo "java/test-run: $1" >&2; exit 2; }
CORE=""; CSRC=""; OUTD=""; LOG=""
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    --core|--out|--log) [ $# -ge 2 ] || usage "$1 needs a value"
      case "$1" in --core) CORE="$2"; CSRC="--core" ;; --out) OUTD="$2" ;; --log) LOG="$2" ;; esac; shift 2 ;;
    *) usage "unknown argument: $1" ;;
  esac
done

row() { local IFS=$'\t'; printf '%s\n' "$*"; }

# Repo root: this script lives at <root>/.claude/java/scripts/.
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT" || exit 2

if [ -z "$CORE" ] && [ -n "${C:-}" ]; then CORE="$C"; CSRC="C"; fi
if [ -z "$CORE" ]; then
  for f in project.local.yaml project.yaml; do
    [ -f "$f" ] || continue
    v=$(awk '/^engine:/{e=1;next} e&&/^[^ \t#]/{e=0} e&&/^[ \t]+path:/{sub(/^[ \t]+path:[ \t]*/,"");sub(/[ \t]+#.*$/,"");gsub(/"/,"");print;exit}' "$f")
    if [ -n "$v" ]; then CORE="$v"; CSRC="engine.path"; break; fi
  done
fi
[ -n "$OUTD" ] || OUTD="${OUT:-out}"
mkdir -p "$OUTD" 2>/dev/null || usage "cannot create $OUTD"
OUTD="$(cd "$OUTD" && pwd)"
[ -n "$LOG" ] || LOG="$OUTD/test-run.log"

row core "${CORE:--}" "${CSRC:-unset}"
if grep -q 'failed="$failed $name"' tools/test.sh 2>/dev/null; then GATE=fixed
elif grep -q '| tail -3' tools/test.sh 2>/dev/null; then GATE=old
else GATE=unknown; fi

if [ -z "$CORE" ]; then
  row ran no "no starsector-core: pass --core, set C, or set engine.path in project.local.yaml"
  row gate "$GATE"; exit 0
fi
if [ -f "$CORE/starfarer.api.jar" ]; then row core_jar present "$CORE/starfarer.api.jar"
else row core_jar absent "$CORE/starfarer.api.jar"; row ran no "starfarer.api.jar not found under $CORE"; row gate "$GATE"; exit 0; fi
command -v javac >/dev/null 2>&1 || { row ran no "javac not on PATH"; row gate "$GATE"; exit 0; }

C="$CORE" OUT="$OUTD" bash tools/test.sh > "$LOG" 2>&1
RC=$?
row ran yes -
row exit "$RC"
row gate "$GATE"

# Per-suite rows: a '== Name' header, then the suite's summary line (last non-FAIL line before the next header).
awk -F'\t' '
  /^== /        { if (name != "") print "suite\t" name "\t" (sum==""?"-":sum) "\t" (code==""?"-":code); name=substr($0,4); sum=""; code=""; next }
  name != "" && /^   exit [0-9]+/ { code=$0; sub(/^   exit /,"",code); sub(/ .*/,"",code); next }
  name != "" && /^(FAIL|cannot load)/ { next }
  name != "" && /(passed|failed|loaded)/ { sum=$0 }
  END           { if (name != "") print "suite\t" name "\t" (sum==""?"-":sum) "\t" (code==""?"-":code) }
' "$LOG"

N=$(grep -cE '^(FAIL|cannot load)' "$LOG")
grep -E '^(FAIL|cannot load)' "$LOG" | head -20 | while IFS= read -r l; do row fail "$l"; done
[ "$N" -gt 20 ] && row fail_more "$((N - 20))"

B=$(grep -m1 -E '^FORBIDDEN|error:' "$LOG")
if [ -n "$B" ]; then row build failed "$B"
elif grep -q '^built ' "$LOG"; then row build ok -
else row build unknown -; fi

F=$(grep -m1 -E '^(ALL TESTS PASSED|TESTS FAILED:)' "$LOG")
row final "${F:--}"
row log "$LOG"
exit 0
