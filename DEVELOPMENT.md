# Clasher Events — Development Status

## Project boundary

Clasher Events is a standalone Paper plugin. It is developed and deployed independently of the Clasher Survival server and does not require that server's logs, world files, plugin configuration, or runtime. Install it on the separate Paper server intended to host PvP events.

## Current development target

- Version: `0.7.0-SNAPSHOT`
- Branch: `feature/v0.7.0-admin-control-center`
- Java: 25
- Paper API: `26.2.build.135-stable`
- Optional integrations: WorldEdit 7.4.6 and WorldGuard 7.0.18
- Build command: `mvn clean verify`

Keep `main` unchanged until the feature branch has passed the release gates.

## Implemented in the 0.7.0 development branch

### Event gameplay and lifecycle
- Eight configured modes and their arenas/kits: NethPot, Axe & Shield, Build UHC, Sword, UHC, Crystal, SMP, and Diamond Pot.
- Battle Royale registration, wave progression, eliminations, spectator handling, and winner detection.
- Duel, Team Wars, and sequential tournament flows.
- Registration countdown, kit delivery timing, late joining, pause/resume, and admin stop controls.
- Player combat attribution, kill/death statistics, match history, MVP tracking, spectator target selection, audience predictions, and audience points.
- Disconnect grace period and persisted player-state recovery.
- Configurable AFK warning/elimination behavior.

### Administration and setup
- `/ce gui` Admin Control Center.
- Arena Manager and guided spawn setup for Wave 1, Wave 2, Final, and Audience.
- Kit Manager with preview and reload.
- Player Manager with pagination, participant details, and safe online-player removal.
- Statistics and winner reward viewers.
- Reward command administration through `/ce reward`.
- Preset listing/application through `/ce preset`.
- Settings GUI for registration duration, kit-delivery lead time, audience prediction points, AFK enable/disable, AFK timeout, and named lobby/spectator/winner locations.
- `/ce diagnose` checks dependencies, kits, arenas, configuration values, presets, and named locations.
- `/ce debug on|off|status` controls opt-in lifecycle diagnostics.

### Reliability and validation
- Recovery snapshots, player statistics, match history, audience points, and plugin configuration use temporary-file replacement for safer persistence.
- Arena validation checks schematic path safety, file readability, supported format, schematic dimensions, bounds ordering, required spawn counts, and spawn coordinates against arena bounds.
- CI validates YAML resources and checks consistency across all eight arena, kit, schematic, preset, and required spawn definitions.
- CI builds with Java 25, verifies plugin metadata and main class inside the packaged JAR, and uploads the JAR as an Actions artifact.

## Release gates still open

The following cannot be certified by compilation alone and must be completed on a disposable, separate Paper test server:

1. Verify plugin startup, reload/restart behavior, and permission boundaries.
2. Test all eight modes with multiple clients, including edge cases and winner resolution.
3. Test reconnect grace period, disconnect elimination, server/plugin shutdown, and player inventory/state restoration.
4. Test WorldEdit paste, arena baseline backup, reset after block/entity changes, and failure recovery.
5. Test WorldGuard region behavior, including Build UHC member/non-member building.
6. Test rewards using harmless console commands, audience predictions, statistics, and tournament byes/disconnects.
7. Test GUI click/drag handling, pagination, admin-only actions, and settings lockout during an active event.
8. Verify performance and behavior under the expected concurrent player count.

Do not call the plugin production-ready or publish a stable release until these live integration tests pass.

## Known scope exclusions

- No web panel or external database requirement.
- No automatic arena reset until backup/reset behavior is proven safe on the separate test server.
- No Discord integration or scheduled event automation in this milestone.
- No dependency on the Clasher Survival server's runtime or data.
