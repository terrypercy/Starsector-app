---
name: java-guardrail-review
description: "Check a Claude Captain change against the player's locked safety rules (Rules.LOCKED, in-game approval popups, control/pause/#since gates, fuel and supply gates, no cheats). Verdict CLEAR / NEEDS DECISION / NOT ASSESSED. Run after /code-review and before any release."
argument-hint: "[git range, e.g. main..HEAD | file paths]"
user-invocable: true
allowed-tools: Read, Glob, Grep, Write, Bash(git diff*), Bash(git log*), Bash(git show*), Bash(git rev-parse*)
model: sonnet
---

# Java Guardrail Review

The agent broke the player's rules repeatedly in live play; 1.0.9 moved them into code. This
review makes sure no change quietly undoes that. Rules: `.claude/java/process.md` §4.

## Phase 1: The change

Default range: `main..HEAD` plus uncommitted changes (`git diff main...HEAD`, `git diff`). Use
the argument if given. If the range is empty or cannot be read, the verdict is
**NOT ASSESSED — no change to review** and stop.

## Phase 2: Derive the guard-rail set from the code (not from a list)

Read these from the **base** side of the range (`git show <base>:<path>`) and from the head:

1. **Locked keys**: the `LOCKED` set in `src/Rules.java` and each key's default in the defaults
   text (lines marked `LOCKED`).
2. **Approval call sites**: every place `AskPopup` is opened (`grep -n "AskPopup" src/`), and the
   commands it guards.
3. **Gates**: `control player` refusal, `#since` / `require_since` checks, `Governor.modPause`
   and every other call that pauses or unpauses the game, hunt/trip fuel and supply gates
   (`Preflight`, `BountyRunner`, `TripRunner`, `SupplyMath`), save-before calls.
4. **Money paths**: every call that buys, sells, pays or grants credits or cargo
   (`grep -nE "addCredits|subtract|buy|sell|addCommodity|removeCommodity" src/`).

If `src/Rules.java` has no `LOCKED` set, or any of steps 1–3 finds nothing, record
`NOT SOURCEABLE — <which>`; the verdict cannot be CLEAR.

## Phase 3: Compare

For each item, base vs head:

| Finding | Kind |
|---|---|
| a key removed from `LOCKED`, or its default made looser (higher price factor, lower margin, `true` → `false` on a safety flag, ...) | LOOSENS |
| an `AskPopup` call site removed, or a guarded command reachable by a new path without it | BYPASSES |
| a new pause/unpause not through `Governor.modPause()`, or an unpause of a player pause | BYPASSES |
| a gate condition removed or relaxed; `control player` accepted in any form | LOOSENS |
| a new money path (buy/sell/pay/grant) with no approval and no ceiling/keep-list check | BYPASSES |
| a credit/bounty grant of any kind | CHEAT |
| a key added to `LOCKED`, a stricter default, a new approval | TIGHTENS (report, no ADR needed) |

For each LOOSENS / BYPASSES / CHEAT finding, look for an ADR in `docs/architecture/` with
Status `Accepted` that names it. Proposed does not count.

## Phase 4: Verdict

| Verdict | When |
|---|---|
| **NEEDS DECISION** | any LOOSENS / BYPASSES / CHEAT finding without an Accepted ADR. List each with file:line, base vs head, and which rule. |
| **NOT ASSESSED** | no NEEDS DECISION finding, but a Phase 1/2 input could not be read (name it) |
| **CLEAR** | everything derived, nothing loosened without an Accepted ADR |

NEEDS DECISION outranks NOT ASSESSED, which outranks CLEAR. A CHEAT finding is never cleared by
an ADR: it is reported as a defect to remove.

## Phase 5: Report

Show the verdict, the derived set (counts: N locked keys, N approval sites, N gates, N money
paths), and every finding including TIGHTENS. Ask "May I write this to
production/qa/guardrail-[date].md?" before writing.

For NEEDS DECISION: say what the player must decide, and offer `/architecture-decision` to draft
the ADR. Do not change the code to "fix" it without the player's choice.
