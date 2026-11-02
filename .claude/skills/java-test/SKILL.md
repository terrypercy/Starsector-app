---
name: java-test
description: "Run the Claude Captain test suite (tools/test.sh) and report PASS / FAIL / NOT ASSESSED per suite. The Java mod's replacement for /smoke-check. Use before closing a Logic story, before a release, or when asked to run the tests."
argument-hint: "[--core <starsector-core>] [quick]"
user-invocable: true
allowed-tools: Read, Glob, Grep, Write, Bash(bash .claude/java/scripts/test-run.sh*)
model: sonnet
---

# Java Test

Runs the mod's whole suite through `.claude/java/scripts/test-run.sh`, reads its rows and
gives one verdict. Rules: `.claude/java/process.md` §2.

**Output:** `production/qa/java-test-[YYYY-MM-DD].md` (skipped with `quick`).

## Phase 1: Run

Run `bash .claude/java/scripts/test-run.sh` (add `--core <path>` from the argument if given).
Keep every row. The script never judges; this skill does.

## Phase 2: Verdict

Apply the first row of this ladder that matches:

| # | Condition (from the rows) | Verdict |
|---|---|---|
| 1 | `build failed` | **FAIL** — build broken (quote the line) |
| 2 | `ran yes` and (`exit` ≠ 0, or any `fail` row, or a `suite` whose summary has a non-zero `failed`, or a `suite` exit that is a number other than 0) | **FAIL** |
| 3 | `ran no` | **NOT ASSESSED** — quote the reason (no starsector-core, no starfarer.api.jar, no javac) |
| 4 | `gate old` or `gate unknown` | **NOT ASSESSED** — `tools/test.sh` does not check suite exit codes, so its result proves nothing |
| 5 | `final` is not `ALL TESTS PASSED`, or fewer than 6 `suite` rows | **NOT ASSESSED** — the run did not finish normally (quote the last lines of the log) |
| 6 | otherwise | **PASS** |

FAIL outranks NOT ASSESSED; NOT ASSESSED outranks PASS. A NOT ASSESSED is never reported as a
pass, and the report names which row caused it.

## Phase 3: Report

Show the player:

```
Java test: [PASS | FAIL | NOT ASSESSED] — [one-line reason]
Suites: CaptainTest [summary] | PlannerTest [...] | ReaderTest [...] | AdvisorTest [...] | TradeTest [...] | LoadAll [...]
Failures: [each fail row, or none]
Log: [log row path]
```

Unless `quick`, ask "May I write this to production/qa/java-test-[date].md?" and on yes write the
same content plus the commit (`git rev-parse --short HEAD`, `+dirty` if uncommitted) and the raw
rows.

In this cloud container there is no `starsector-core`, so the expected result here is
NOT ASSESSED (row 3). Say so plainly and ask the player to run
`C=<starsector-core> OUT=out bash tools/test.sh` on their PC and paste the output; apply the same
ladder to the pasted output (rows 1, 2, 5, 6; `gate` from the repo's `tools/test.sh`).

## Next

- PASS → `/story-done` may cite this report for Logic acceptance criteria.
- FAIL → fix with `java-specialist`, then re-run.
- NOT ASSESSED → nothing downstream may treat the suite as passing.
