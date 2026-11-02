# Java / Starsector structure

The studio framework (everything else under `.claude/`) is built for Godot, Unity and Unreal.
This folder, plus the `java-*` / `starsector-*` agents, skills and rule, is the separate track
for this Java Starsector mod. It sits beside the studio and edits none of its files.

Read `process.md` before any implementation, test, release or live-play work.

## What is in the Java track

| Kind | File | Job |
|---|---|---|
| Process | `.claude/java/process.md` | How every step works for this mod: test, live check, release, guard rails |
| Script | `.claude/java/scripts/test-run.sh` | Runs `tools/test.sh`, prints per-suite rows (observations only) |
| Template | `.claude/java/templates/live-check.md` | Record of one live play session |
| Agent | `.claude/agents/java-specialist.md` | Java 8 code quality, testable pure classes |
| Agent | `.claude/agents/starsector-specialist.md` | Starsector API, class loader, stubs, game patches |
| Skill | `/java-test` | Run the suite and give PASS / FAIL / NOT ASSESSED |
| Skill | `/java-guardrail-review` | Check a change against the locked safety rules |
| Skill | `/java-live-check` | File the player's live-play evidence; close or reopen stories |
| Skill | `/java-release` | Version bump, build, install only on the player's OK, then live check |
| Rule | `.claude/rules/java-mod-code.md` | Loads when `src/`, `tests/`, `data/` or `stubs/` is edited |

## Which step uses what

| Studio step | Use for this mod |
|---|---|
| `/start`, `/adopt`, `/reverse-document`, `/create-architecture`, `/architecture-decision` | Studio skill as is |
| `/create-epics`, `/create-stories`, `/story-readiness`, `/sprint-plan` | Studio skill as is |
| `/dev-story` | Studio skill; its specialist slots point at `java-specialist` / `starsector-specialist` (`project.yaml`) |
| `/code-review` | Studio skill, **then** `/java-guardrail-review` on the same change |
| `/smoke-check`, `/test-setup`, `/test-helpers` | **Use `/java-test` instead.** The studio versions only know gdUnit4 / Unity / Unreal runners |
| Run-and-observe screenshot (`.claude/docs/run-and-observe.md`) | **Use `/java-live-check` instead.** Only the player runs the game |
| `/story-done` | Studio skill; for Logic stories cite the `/java-test` report, for player-visible stories cite the live-check record |
| `/release-checklist`, `/launch-checklist` | **Use `/java-release` instead** |
| `/setup-engine` | Do not run. The engine is set by hand in `project.yaml` |

## Known framework mismatches (left as they are)

- Studio status scripts count only `.gd` / `.cs` / `.cpp` files and report 0 source files.
- Studio skills look for tests under `tests/unit/` and `tests/integration/`; this mod's tests are in `tests/`.

## The one exception: an edit to a studio file

`.claude/hooks/yaml-helper.sh` line `engine.name::Godot|Unity|Unreal|Starsector` adds
`Starsector` to the allowed engine names, so session start does not report a schema error.
**A studio upgrade that replaces `yaml-helper.sh` will undo it.** Re-apply that one line after
any upgrade (`UPGRADING.md` in the studio repo), and check nothing else under `.claude/` outside
this track was changed.
