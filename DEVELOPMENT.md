# Development status and implementation boundaries

This repository contains an early Paper plugin scaffold and editable configuration presets.

## First milestone: NethPot
- Dedicated arena ID: `nethpot-main`
- World: `nethpot_arena`
- WorldGuard regions: `ce_nethpot_lobby`, `ce_nethpot_wave1`, `ce_nethpot_wave2`, `ce_nethpot_final`, `ce_nethpot_audience`
- Three fixed phases: Wave 1, Wave 2, Final
- Countdown: 120-second registration and kit delivery 60 seconds before Wave 1
- Border sizes: 180 -> 100 -> 35 blocks
- Voting: GUI + chat planned; current prototype has only a basic chat vote method

## Not yet implemented
1. Runtime loading and validation of kit/arena YAML files.
2. Actual NethPot armor, offhand, potion effects and full inventory kit application (prototype currently uses a placeholder kit).
3. Arena/world/region settings loaded from arena YAML instead of hard-coded config.
4. Configured safe spawn points and teleport validation.
5. GUI voting, persistent audience points and duplicate-vote handling.
6. Complete duel/team/random-kit flows; current phase engine is Battle-Royale-oriented.
7. Consistent player cleanup on stop, disconnect, death and shutdown.
8. Integration/staging tests on the exact Paper/WorldGuard/WorldEdit versions.

Do not deploy this prototype as a finished public event system. Kit and arena YAML files remain presets/specifications until the runtime modules are complete.
