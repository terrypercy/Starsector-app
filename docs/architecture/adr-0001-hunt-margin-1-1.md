# ADR-0001: Bounty hunt margin lowered to 1.1x

Status: Accepted (player's decision, Nov 2 2026)

## Context
`hunt_margin` is a locked guard rail: the mod hunts only targets whose DP x margin is under our DP.
At 1.25x every open bounty (250-269 DP vs our 297) was refused. The player asked to do those
bounties and to "reformat to 1.1x strength".

## Decision
Default and locked value of `hunt_margin` changes from 1.25 to 1.1. It stays locked (file edits ignored).

## Consequences
Targets up to ~270 DP qualify for our 297 DP fleet. Fights are closer, so losses and supply cost rise;
the save-before-hunt and the stronger-fleet pause in dialogs (same margin) still apply.

## Superseded (Nov 2 2026, later)
The first 1.1x fight (Nessus Shepherd, 271 DP vs our 297) disabled 12 of our ships and cost ~1,100 crew;
the player reloaded and set `hunt_margin` back to 1.25. Status: Superseded.
