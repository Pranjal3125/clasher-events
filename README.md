# Clasher Events — 0.5.0 development candidate

Standalone Paper PvP event plugin for Clasher Network. This is a source milestone; it is not a compiled or production-certified JAR.

## Architecture
- One shared arena world: `clasher_events`.
- Eight separate schematic arenas and eight YAML kit presets: NethPot, Axe & Shield, Build UHC, Sword, UHC, Crystal, SMP and Diamond Pot.
- Schematics are extracted from the plugin JAR to `plugins/ClasherEvents/schematics/` on first startup.
- One event runs at a time.

## Event systems
- Battle Royale registration, kit delivery, elimination, Wave 1 / Wave 2 / Final progression and winner detection.
- 1v1 Duel validation and sequential tournament brackets with byes and round advancement.
- Team Wars with balanced Red/Blue assignment, friendly-fire prevention, team elimination victory and team rewards.
- Random Kit mode, spectator respawn, vote GUI, chat predictions and cheering.
- Player-specific WorldBorder for arenas sharing one world.

## Admin tools and protection
- `/ce gui` opens the event-selection GUI.
- `/ce kit preview <kit> <player>` opens a read-only kit preview.
- `/ce arena paste <arena>` pastes the starter schematic and creates a one-time arena baseline backup.
- `/ce arena reset <arena>` restores that saved baseline and removes non-player entities inside the arena bounds.
- WorldGuard regions protect each configured arena; Build UHC temporarily allows only registered event members to build.
- Winner and team-winner console commands are configurable under `rewards` in `config.yml`. Supported placeholders: `{player}`, `{uuid}`, `{arena}`, `{mode}`.
- Player inventory, armor, offhand, effects, game mode, health, food, XP, fire and location are persisted to `player-recovery.yml` and restored after reconnect if the server crashes mid-event.

## Dependencies
- Paper API matching the POM target.
- Java 25 and Maven to build.
- WorldEdit 7.4.6 required for schematic paste and arena backups.
- WorldGuard 7.0.18 optional but recommended for region protection.

## Build
From the directory containing `pom.xml`:
```bash
mvn clean package
```
The JAR should appear in `target/` after a successful build.

## Important
This milestone has passed YAML parsing and Java syntax screening only. It has **not** yet passed a dependency-backed Maven compile or live multiplayer staging test. Do not deploy it to a public event server until those checks pass. The eight schematics are editable starter arenas, not commissioned final builds.


## 0.6.0 development commands

- `/ce gui` — event selection and admin control centre
- `/ce countdown` — choose 2, 5 or 10 minute registration countdown
- `/ce setlobby` — save the waiting lobby
- `/ce setspectator` — save the spectator destination
- `/ce setwinner` — save the winner destination
- `/latejoin <playername>` — admin force-add an online player to a running event
- `/event spectate` — open the fighter spectator selector
- `/event stats` — view kills, deaths and wins
- `/event top` — audience-points leaderboard

The 60-second pre-start kit delivery remains configurable using
`kit-delivery-seconds-before-start`. AFK elimination is configurable under `afk`.
Discord integration and scheduled event features are intentionally not included.

## Mode-specific arena spawn setup (0.6.0 update)

Stand at the exact position in the shared `clasher_events` world and set the spawn for the mode. The command stores the current position in that mode's mapped arena and phase. Supported phases are `wave1`, `wave2`, `final`, and `audience`.

Examples:

```text
/ce setspawn nethpot wave1
/ce setspawn axe-shield wave1
/ce setspawn build-uhc wave1
/ce setspawn sword wave1
/ce setspawn uhc wave1
/ce setspawn crystal wave1
/ce setspawn smp wave1
/ce setspawn diamond-pot wave1
```

Use `wave2`, `final`, or `audience` instead of `wave1` for those phase locations. Use `/ce addspawn <mode-or-arena> <phase>` to add another spawn point without deleting existing points. `duel` and `tournament` use the `sword` arena; `team-wars` uses the `smp` arena. `random-kit` can choose any arena, so configure the spawn points for all eight arenas separately.
