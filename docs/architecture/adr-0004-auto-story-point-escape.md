# ADR-0004: Automatic 1-story-point escape from lopsided intercepts

Status: Accepted (player decision, 2026-11-04 23:05 Toronto)

## Context
SUMMARY.md: "Never spend story points (the player clicks them)." On Nov 4 near Danu three IX Battlegroup fleets
(161, 161 and 465 DP against our 86) caught the fleet; each time the trip paused and Claude, with the player's OK,
clicked the 1-SP "Disengage by executing a series of special maneuvers" option by screen. The player then chose
"Escape with 1 SP; always allow" and asked that anything done by hand be built into the mod.

## Decision
`AutoDialog.spEscape` buys that option itself when all hold (locked rules, not file-editable):
- `sp_auto_escape = true`, only 1-point escapes;
- the enemy side (the battle's other side, else the fleet) is at least `sp_auto_escape_ratio = 2.0` x our DP;
- at least `sp_auto_escape_keep = 10` story points remain afterwards.
It spends the point through the game's own confirm path (preConfirm, spendStoryPoints with the bonus XP, confirm),
so nothing is granted for free, and logs a WARN so Claude reports it in chat.

## Consequences
Story points are still never spent for anything else. Intercepts under 2x, or with 10 or fewer points, still pause.
