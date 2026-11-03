# ADR-0002: Automatic restocks buy supplies; fuel ceiling 1.5x

Status: Accepted (player's decision, 2026-11-02)

## Context
`ask_before_supplies = true` made every automatic restock only quote supplies, so hunts stopped
at markets waiting for the player (Jaunzeme hunt, Tartessus, Jan 24 c214). The fuel ceiling
(median x 1.35 = 33.6) refused Tartessus fuel at 34.4/unit and the hunt would not depart.

## Decision
The player said: "Let's just make sure fuel and supplies stay topped up."
- `ask_before_supplies` default -> false (still LOCKED). Restocks buy supplies under the supply ceiling.
- `fuel_price_factor` 1.35 -> 1.5 (still LOCKED).
- Unchanged: emergency_price_factor 2x, credit spend caps, reserve_credits, supplies kept out of trade goods.

## Consequences
Hunts and trips no longer stall at a market for a supply quote. Fuel costs up to ~11% more per unit
at the ceiling. The player can revert with a new build.
