# Clasher Events — 0.2.0 development build

Standalone Paper PvP event plugin for Clasher Network.

## Confirmed arena architecture
- One shared arena world: clasher_events.
- Eight separate schematic arenas, placed at spaced coordinates.
- Unique visual theme for NethPot, Axe & Shield, Build UHC, Sword, UHC, Crystal, SMP and Diamond Pot.
- Individual YAML kit presets for all eight PvP styles.
- WorldEdit schematic paste integration.

## Requirements
- Paper server with Java 25
- WorldEdit 7.4.6 for schematic pasting
- WorldGuard 7.0.18 is planned for arena protection; integration is not complete in this milestone.

## Build
Run mvn clean package with JDK 25.

## Development status
This is an early implementation foundation, not a finished public tournament release. The current source milestone includes shared-world arena configuration, schematic paste, YAML kit loading and basic event registration/elimination. Wave progression, border shrinking, WorldGuard protection, audience GUI/voting, persistent rewards, pause/resume and full tournament modes still need implementation and staging tests.

See DEVELOPMENT.md for the current boundaries.