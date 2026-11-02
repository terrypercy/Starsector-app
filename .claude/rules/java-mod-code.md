---
paths:
  - "src/**"
  - "tests/**"
  - "data/**"
  - "stubs/**"
---

# Java Mod Code Rules (Claude Captain)

Full process: `.claude/java/process.md`. Starsector API facts: `docs/engine-reference/starsector/VERSION.md`.

- Java 8 source only (`--release 8`).
- NO `java.lang.reflect`, NO `java.io.File`; file access only via `Global.getSettings()` under `saves/common/`.
- Decisions and formulas go in pure classes testable from `tests/` without the game; `Global` stays in thin wrappers.
- Every new rule or formula gets checks in the matching `tests/*Test.java`; a live bug gets a check that reproduces it.
- Every mod pause goes through `Governor.modPause()`; never unpause a player's pause.
- `Rules.LOCKED` keys, approval popups, `control player` refusal, `#since` gate, fuel/supply gates and the no-cheats rule are guard rails: removing, weakening or bypassing one needs an Accepted ADR and the player's decision (`/java-guardrail-review`).
- New dialog handling is a `dialog_rules.txt` rule before it is code.
- `stubs/` are compile-time only and never packaged; regenerate with `tools/gen_stubs.py`, never hand-add real logic.
- Bump `mod_info.json` `version` when the shipped jar changes.
