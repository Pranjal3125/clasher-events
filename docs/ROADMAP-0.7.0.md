# Clasher Events 0.7.0 — Admin Control Center

**Status:** In progress — implementation checklist  
**Base:** 0.6.0-SNAPSHOT  
**Target:** 0.7.0-SNAPSHOT  
**Branch:** `feature/v0.7.0-admin-control-center`

This is a feature-version upgrade focused on making event setup and administration faster without changing the approved gameplay rules or the eight existing game modes.

## Release goals

1. Make common admin tasks available through a single in-game GUI.
2. Guide arena setup and validate required locations before an event can start.
3. Add a diagnostic command that explains configuration and dependency problems.
4. Provide reusable event presets and safe configuration editing.
5. Keep existing event lifecycle, NethPot progression, kit timing, reconnect rules, rewards, and player inventory restoration intact.

## Scope and implementation order

### 0.7.0-A — Safety and diagnostics
- [x] `/ce diagnose` reports loaded/missing arenas, spawn points, kits, and optional integrations.
- [x] Event start preflight blocks starts with missing arena/kit/required starting-phase spawns or no online entrants.
- [x] Duel, Team Wars, and Tournament start checks validate online entrant counts and tournament Final spawns before transitioning event state.
- [x] Add opt-in lifecycle debug logging with `/ce debug on|off|status`; default remains disabled.

### 0.7.0-B — Admin Control Center
- [x] `/ce gui` opens a permission-checked dashboard.
- [x] Dashboard sections: Create Event, Arena Manager, Kit Manager, Rewards viewer, Player Manager, Statistics, Diagnostics, Settings, status and stop controls.
- [x] Reward command administration through `/ce reward list|add|remove|clear` for winner and team-winner reward groups.
- [x] Admin GUI click actions check admin permission server-side; settings edits are blocked while an event is active. Continue auditing every menu path.
- [x] Back navigation and status lore for current menus.
- [x] Add pagination for participant and statistics lists.
- [x] Add player detail view and admin removal action; removal requires the player online so their saved inventory/state can be restored safely.

### 0.7.0-C — Arena Setup Wizard
- [x] Arena phase spawn setup shortcuts for Wave 1, Wave 2, Final, and Audience.
- [x] Add guided lobby, spectator, and winner location setup directly in the Settings GUI.
- [x] Basic arena validation for schematic, required spawns, WorldEdit, and shared-world availability.
- [x] Expand diagnostics to validate registration duration, kit lead time, audience points, and all configured preset modes/durations.\n- [ ] Expand mode-aware arena validation and schematic/world metadata checks.
- [x] Require explicit confirmation before replacing existing spawn points; confirmation shows the current point count, while "Add Wave 1 Spawn" preserves existing points.

### 0.7.0-D — Presets and configuration
- [x] Reusable presets for all eight standard modes, configurable in `config.yml` and applied with `/ce preset list|apply`.
- [x] In-game cycling controls for registration duration, kit-delivery lead time, and audience prediction points.
- [x] Merge new default config keys into existing config.yml on startup without overwriting administrator values.\n- [x] Validate registration duration, kit lead time, prediction points, and preset values in diagnostics.\n- [ ] Add broader supported settings.
- [x] Current settings are range-cycled and persisted through the plugin YAML config.
- [x] Save plugin config changes through a temporary file and atomic replacement (with a safe non-atomic fallback).\n- [ ] Add GUI editing for additional settings.

### 0.7.0-E — Reliability
- [ ] Verify arena backup/reset workflow under live server testing before calling it release-ready.
- [ ] Recovery and reconnect tests before release.
- [x] CI verifies Maven build, plugin descriptor presence/main class, and packaged JAR contents.
- [ ] Add YAML syntax validation and automated behavior tests.

## Non-goals for the first 0.7.0 milestone
- No web panel or external database requirement.
- No automatic arena reset until schematic/backup restoration is proven safe.
- No rewrite of the existing event engine solely for architectural neatness.
- No removal or renaming of existing commands or modes.

## Release gates
- Java 25 Maven `clean verify` passes.
- Packaged JAR contains `plugin.yml` and the configured main class.
- [ ] All eight modes remain selectable and retain their approved behavior (live regression testing required).
- [ ] GUI permissions and click handling are tested in a live Paper server.
- [x] Basic arena preflight catches missing selected arena/kit/starting-phase spawns.
- [ ] Test negative and edge cases on a live server.
- Multiplayer tests cover start, elimination, winner, disconnect/rejoin, stop, and shutdown restoration.
- No stable release tag until live server testing passes.

## Compatibility promise
Existing 0.6.x configurations should continue to load. New settings must have safe defaults, and migration must preserve user data.
