# Claude Captain -- built with Claude Code Game Studios

Claude Captain is a Starsector mod that lets Claude play a single-player campaign on the
player's behalf (see `SUMMARY.md`, `README.md`, `DESIGN.md`). It is developed with the
Claude Code Game Studios framework: 49 coordinated subagents, each owning a domain.

## Technology Stack

- **Engine**: Starsector 0.98a-RC8 (a mod, not a standalone game)
- **Language**: Java, compiled with `--release 8` against `starfarer.api.jar`
- **Version Control**: Git with trunk-based development
- **Build System**: `C=<starsector-core> OUT=<out> bash tools/build.sh` -> `claude_captain.jar`
- **Tests**: `C=<starsector-core> OUT=<out> bash tools/test.sh` (plain Java test classes in `tests/`)
- **Asset Pipeline**: none -- the mod ships code and text config only (`data/`)

> **Note**: The studio's engine specialists (Godot, Unity, Unreal) do not apply to this
> project. Code work routes to `lead-programmer` / `gameplay-programmer`; see
> `specialists` in `project.yaml`.

## Project Structure

@.claude/docs/directory-structure.md

This repo's actual layout differs from the template in three places:
- Code root is `src/` (flat, package `claudecaptain`), plus `data/scripts/plugins/`.
- Tests are in `tests/` directly, not `tests/unit/` or `tests/integration/`.
- `stubs/` holds compile-time stand-ins for the game's obfuscated UI classes; never packaged.

## Engine Version Reference

@docs/engine-reference/starsector/VERSION.md

## Mod Rules (non-negotiable)

These come from live play and outrank any skill's default behaviour:

- **The live game install is the player's.** Never copy a jar into the mods folder, edit
  `vmparams`, or change files under the game directory without the player's explicit OK.
- **Locked safety rules stay locked.** The guard rails listed in `SUMMARY.md` (keep list,
  price ceilings, in-game approval popups, `control player` refusal, pause handling, fuel
  and supply gates) are enforced in code on purpose. A change that loosens one needs the
  player's explicit decision, recorded as an ADR.
- **The approval popup is the player's.** The agent never clicks it, through screen control
  or any other route. A "yes" in chat is not an approval.
- **No cheats.** No commands that grant credits, pay bounties or otherwise bypass the game.
- **The game's class loader forbids reflection and `java.io` file access.** `tools/build.sh`
  fails the build if either appears; do not work around the check.

## Technical Preferences

`project.yaml` at the repo root is the primary config store -- engine, specialists,
naming, platform, performance, modes. Skills resolve it via `resolve_config`
(see `.claude/docs/config-resolution.md`).

`.claude/docs/technical-preferences.md` is the **legacy fallback**, read on demand
when a key is absent from `project.yaml`.

## Coordination Rules

@.claude/docs/coordination-rules.md

## Collaboration Protocol

**User-driven collaboration, not autonomous execution.**
Every task follows: **Question -> Options -> Decision -> Draft -> Approval**

- Agents MUST ask "May I write this to [filepath]?" before using Write/Edit tools
  (exempt: `active.md` appends, agent memory, a subagent's new file at the path
  its orchestrating skill named; see `.claude/rules/agent-memory.md`)
- Agents MUST show drafts or summaries before requesting approval
- Multi-file changes require explicit approval for the full changeset
- No commits without user instruction
- A skipped or dismissed question is not an answer: act on nothing, re-ask in
  plain text and wait
- Started outside this file's folder? Warn first: hooks, deny rules and the
  ask-first mode load only when Claude Code starts here

See `docs/COLLABORATIVE-DESIGN-PRINCIPLE.md` for full protocol and examples.

## Coding Standards

@.claude/docs/coding-standards.md

Project-specific overrides of the standards above:
- The CI command listed for Godot does not apply; the test gate is `tools/test.sh`.
- "Launched and observed" means the mod running in Starsector, observed by the player or
  through `events.txt` / `state.txt` / a screenshot. This container has no game install, so
  that step is the player's.

## Context Management

Read `.claude/docs/context-management.md` on demand -- it is a reference, not
session context. Two of its conventions are load-bearing and cited by name
elsewhere in the repo, so they are restated here rather than lost:

- **`production/session-state/active.md` is the session checkpoint.** The file is
  the memory, not the conversation. Read it first after any compaction, crash, or
  `/clear`.
- **Helpers in `.claude/scripts/` emit observations, never verdicts.** A script
  that scores or judges will eventually contradict a mode or override it cannot
  see. (Cited by `artifact-check.sh` and `adr-dep-graph.sh`.)
