---
name: java-release
description: "Release a new Claude Captain build: test gate, guard-rail review, version bump, build, change summary for the player, install only on the player's explicit OK, then a live check. Replaces the studio's /release-checklist for this mod."
argument-hint: "[version, e.g. 1.0.10]"
user-invocable: true
allowed-tools: Read, Glob, Grep, Write, Edit, AskUserQuestion, Bash(git log*), Bash(git diff*), Bash(git rev-parse*), Bash(git status*)
model: sonnet
---

# Java Release

Rules: `.claude/java/process.md` §5. Every step reports its result; a step that could not run
says **NOT ASSESSED** and blocks the release — it is never skipped silently.

## Steps

1. **Range.** The installed build is in `SUMMARY.md` ("Installed in the live game: build ...").
   The release range is `<installed>..HEAD`. If no installed build is recorded, ask the player
   which build is installed; until answered, step 5's summary is NOT ASSESSED.
2. **Test gate.** Run `/java-test`. PASS continues. FAIL stops. NOT ASSESSED (expected in a
   cloud session) stops here until the player runs `tools/test.sh` on their PC and the pasted
   output reaches PASS.
3. **Guard rails.** Run `/java-guardrail-review` on the range. CLEAR continues. NEEDS DECISION
   stops until each finding has an Accepted ADR. NOT ASSESSED stops.
4. **Version.** Propose the next version (argument, else patch +1 from `mod_info.json`). Show
   the edit to `mod_info.json` `version` and `project.yaml` `project.version`; ask
   "May I write these?" Commit only on the player's instruction.
5. **Change summary.** From `git log <range>`: player-facing changes, guard-rail changes
   (TIGHTENS included), new commands, anything that changes the save or the comm folder.
6. **Build.** The player builds on their PC: `C=<starsector-core> OUT=out bash tools/build.sh`
   (the agent may run it only where `starsector-core` exists and the player asked). The output
   must end `built .../claude_captain.jar` with no `FORBIDDEN` line.
7. **Install — the player's decision.** Show the steps: close Starsector; copy
   `out/claude_captain.jar` to `<Starsector>/mods/claude_captain/jars/` along with the repo's
   `mod_info.json` and `data/`; start the game and check the load line names the new build.
   Ask the player explicitly whether to install. Never install, copy into the game folder, or
   edit `vmparams` without that OK; a "yes" given for another release does not carry over.
8. **Live check.** Run `/java-live-check plan` for the release's stories. After the first session
   on the new build, `/java-live-check record`, then offer to update `SUMMARY.md` (installed
   build, version, what changed).

## Report

```
Release [version]: [READY TO INSTALL | INSTALLED | BLOCKED at step N — reason]
Range: [installed]..[head]   Tests: [verdict]   Guard rails: [verdict]
```
