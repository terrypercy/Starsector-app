# Source Directory (Java, package `claudecaptain`)

When writing or editing mod code in this directory, follow these standards.

## Game Version Warning

Check `docs/engine-reference/starsector/VERSION.md` and the existing code before using any
Starsector API call that `src/` does not already use. Do not guess signatures.

## Coding Standards

- Java source level 8; everything must load in the game's script class loader:
  no reflection, no `java.io.File`, file access only through `Global.getSettings()` under
  `saves/common/`.
- All public APIs require doc comments.
- Keep game-free logic in pure classes (the `*Math` classes, `DialogRules`, `Plans`,
  `Rules`) so `tests/` can exercise it without the game. Classes that touch `Global` stay thin.
- Gameplay values are data-driven (`captain_rules.txt`, `dialog_rules.txt`) -- except the
  locked safety values listed in `SUMMARY.md`, which are locked in code on purpose and must
  not become file-editable.
- Every mod-initiated pause goes through `Governor.modPause()`.
- Architecture decisions go in `docs/architecture/` as ADRs.
- Commits reference the story ID or design document, and bump the version in
  `mod_info.json` when the shipped jar changes.

## File Routing

No engine specialist exists for Starsector. Code goes to `specialists.code` in
`project.yaml` (`lead-programmer`), gameplay mechanics to `gameplay-programmer`, the panel
(`Hud`, `CaptainHud`) to `ui-programmer`.

## Tests

Tests live in `tests/` -- not in `src/`. They are plain Java classes with a `main` method,
run by `tools/test.sh` (which builds first). `tests/Mock.java` provides the fakes.
Every new rule or formula gets a test; a bug fix gets a test that reproduces the live case.

## Verification-Driven Development

Write tests first when adding systems. For panel changes, the player supplies a screenshot
from the live game. Compare expected output to actual output before marking work complete.
