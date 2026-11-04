# ADR-0003: One-off 20 story points

Status: Accepted (player decision, 2026-11-03 21:53 Toronto)

## Context
The no-cheats rule and the locked `allow_cheats` switch stop the agent from granting story points.
The player explicitly asked: "Cheat in 20 story points."

## Decision
Add `bonussp`, which grants exactly 20 story points once per save (persistent flag
`claude_captain_bonus_sp_used`) behind the in-game approval popup. `allow_cheats` stays locked off;
no other cheat is enabled. Same pattern as the 1.1.14 `undmod` one-off.

## Consequences
The guard rail is unchanged for everything else; a second use in the same save is refused.
