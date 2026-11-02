---
name: java-live-check
description: "Plan and record a live play session of Claude Captain: write what the player should try, then file their screenshots or events.txt/state.txt excerpts as evidence and mark each acceptance criterion SEEN / NOT SEEN / NOT ASSESSED. Replaces the studio's run-and-observe step, since only the player runs Starsector."
argument-hint: "[plan | record] [story path or id]"
user-invocable: true
allowed-tools: Read, Glob, Grep, Write, Edit, AskUserQuestion
model: sonnet
---

# Java Live Check

Rules: `.claude/java/process.md` §3. Template: `.claude/java/templates/live-check.md`.
The agent never runs, controls or clicks in the game; the player plays and supplies evidence.

## Mode `plan` (before the session)

1. Read the story (or stories) and their acceptance criteria. Read the build to be played
   (`git rev-parse --short HEAD`) and `mod_info.json` version.
2. Write "What to try": concrete steps the player can follow, and for each criterion what will
   show it — a panel line, a dialog, or specific event types in `events.txt`
   (e.g. `GOAL goal: DONE`, `WARN`, `ALERT`). Prefer evidence the mod already logs.
3. Ask "May I write this to production/qa/evidence/live-[date]-[story].md?" and on yes create
   it from the template, criteria rows set to `NOT ASSESSED`.

## Mode `record` (after the session)

1. Ask the player for evidence if none was given: screenshots (saved beside the record), or the
   event numbers / pasted lines from `saves/common/claude_captain/events.txt` and `state.txt`.
   A description in chat alone is recorded as such and counts as evidence only for criteria the
   player explicitly says they saw.
2. For each criterion: **SEEN** (the evidence shows it — cite file or event numbers),
   **NOT SEEN** (the session reached it and it did not happen, or happened wrongly),
   **NOT ASSESSED** (not reached, or the evidence does not show it either way).
3. Outcome:
   - every criterion SEEN → **DONE**;
   - any NOT SEEN → **REOPENED** (list them; the story goes back to In Progress);
   - otherwise any NOT ASSESSED → **INCOMPLETE** (the story stays Awaiting live check).
4. New problems: for each, propose a story and the reproducing test (see `.claude/java/process.md` §2).
5. Show the filled record, then ask "May I update production/qa/evidence/live-[date]-[story].md?"
   On DONE, also offer to update the story's status and, after a release, the installed build line
   in `SUMMARY.md`.

Never mark a criterion SEEN from what the code should do, from a test, or from an agent's
expectation. Only the session's evidence counts.
