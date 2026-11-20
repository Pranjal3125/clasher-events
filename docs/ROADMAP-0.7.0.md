# Clasher Events 0.7.0 — Admin Control Center

**Status:** Development plan  
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
- `/ce diagnose` reports missing/invalid configuration, arenas, spawn points, kits, and optional integrations.
- Event start preflight validation blocks unsafe starts and lists exact issues.
- Debug logging can be enabled without flooding normal server logs.

### 0.7.0-B — Admin Control Center
- `/ce gui` opens a permission-checked dashboard.
- Dashboard sections: Create Event, Active Event, Arena Manager, Kit Manager, Rewards, Player Manager, Statistics, Diagnostics, Settings.
- Every menu action must validate state and permissions server-side; GUI visibility is not authorization.
- Back navigation, close handling, pagination, and clear status lore.

### 0.7.0-C — Arena Setup Wizard
- Guided setup for lobby, spectator, winner, and phase spawn locations.
- Validate world availability, spawn counts, and required arena metadata.
- Provide a summary before saving; never silently overwrite existing arena data.

### 0.7.0-D — Presets and configuration
- Reusable presets for the existing eight modes.
- Safe in-game editing for supported timing and event settings.
- Validate values and persist changes atomically; retain YAML as the source of truth.

### 0.7.0-E — Reliability
- Automatic arena backup/reset only after a verified restore workflow exists.
- Recovery and reconnect tests before release.
- CI checks for YAML validity, plugin descriptor consistency, and packaged JAR contents.

## Non-goals for the first 0.7.0 milestone
- No web panel or external database requirement.
- No automatic arena reset until schematic/backup restoration is proven safe.
- No rewrite of the existing event engine solely for architectural neatness.
- No removal or renaming of existing commands or modes.

## Release gates
- Java 25 Maven `clean verify` passes.
- Packaged JAR contains `plugin.yml` and the configured main class.
- All eight modes remain selectable and retain their approved behavior.
- GUI permissions and click handling are tested.
- Arena preflight catches missing setup without crashing.
- Multiplayer tests cover start, elimination, winner, disconnect/rejoin, stop, and shutdown restoration.
- No stable release tag until live server testing passes.

## Compatibility promise
Existing 0.6.x configurations should continue to load. New settings must have safe defaults, and migration must preserve user data.
