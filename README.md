# Clasher Events — development build 0.1.0

Standalone Paper tournament/event plugin for Clasher Network.

## Target
- Paper 26.1.2 (API build 74), Java 25
- WorldGuard 7.0.18 (provided dependency)
- WorldEdit 7.4.6 (provided dependency)

## First event: NethPot
The first event preset is `kit-library/nethpot.yml`. Its dedicated arena definition is `arenas/nethpot/arena.yml`; it does not reuse the Axe/Shield or Build UHC arenas. Configure the named WorldGuard regions in `nethpot_arena` and populate safe spawn coordinates before testing.

## Kit library
Starting presets: NethPot, Axe & Shield, Build UHC, Sword, UHC, Crystal, SMP and Diamond Pot. See `kit-library/README.md`.

## Build
Run `mvn clean package` with JDK 25 and internet access. GitHub Actions is configured to build and upload the JAR artifact.

## Status
This is an early development scaffold, **not a finished public-event plugin**. Kit and arena YAML files are currently editable presets/specifications. The runtime YAML kit loader, arena selector, GUI voting, persistent audience points and complete duel/team/random-kit modes remain to be implemented and tested. See `DEVELOPMENT.md`.

Test on a staging server before public deployment.
