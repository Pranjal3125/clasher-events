# Clasher Events — 0.3.0 development build

Standalone Paper PvP event plugin for Clasher Network.

## Confirmed setup
- One shared world: `clasher_events`.
- Eight schematic arenas at separate coordinates in that world.
- Eight unique themed starter schematic builds and eight editable YAML kit presets.
- Battle Royale-style registration countdown, kit delivery at 60 seconds, Wave 1, Wave 2 and Final transitions.
- Player state snapshots/restoration for online participants.
- Chat spectator predictions and cheers.
- Random Kit selects a random configured kit/arena.

## Requirements
- Paper 26.1.2 API target and Java 25.
- WorldEdit 7.4.6 for schematic pasting.
- WorldGuard 7.0.18 is declared as provided; region creation/enforcement is not implemented yet.

## Build
Run `mvn clean package` from the project root using JDK 25. The JAR will be in `target/`.

## Setup
1. Put the JAR in `plugins/` and install WorldEdit.
2. Start the server once. The plugin copies its eight schematics and kit presets into `plugins/ClasherEvents/`.
3. Paste each arena using `/ce arena paste <arena>`.
4. Configure a safe lobby platform at the coordinates in `plugins/ClasherEvents/config.yml`.

## Important limitations
This is a development build, not a production-certified event system. Full 1v1 brackets, Team Wars, GUI voting, persistent audience rewards, WorldGuard region enforcement and true block rollback are not complete. WorldBorder shrinking is intentionally omitted because Minecraft's standard border affects the entire shared world, not one arena. `/ce arena reset` re-pastes the arena schematic. Customize and test all starter maps on a staging server before public use.
