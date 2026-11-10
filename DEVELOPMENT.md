# Clasher Events — development notes

## Confirmed architecture
- Shared world: `clasher_events`.
- Eight separate schematic footprints with unique palette themes.
- Eight YAML kits: NethPot, Axe & Shield, Build UHC, Sword, UHC, Crystal, SMP and Diamond Pot.

## Implemented in source package 0.3.0
- Paper plugin bootstrap and default resource copying.
- Eight embedded starter Sponge schematic files.
- Shared-world arena registry and WorldEdit paste command.
- YAML kit loader for armor, enchantments, inventory slots, offhand, potion items and effects.
- Registration countdown and kit delivery threshold.
- Battle Royale elimination flow, alive-player tracking, death/disconnect elimination and winner announcement.
- Wave 1 / Wave 2 / Final spawn transitions.
- Player state snapshot/restore for online participants.
- Audience respawn point, chat prediction and cheer commands.
- Random Kit preset selection and UHC natural regeneration restriction.

## Known gaps
- WorldGuard region creation/enforcement.
- GUI voting and persistent audience points/rewards.
- Full 1v1 bracket and Team Wars team assignment.
- WorldBorder shrinking: the vanilla border is world-wide, so it cannot independently shrink one arena in a shared world.
- Durable block rollback; reset currently re-pastes the schematic.
- Full movement freeze during pause and automated integration tests.

## Build
Use JDK 25 and Maven: `mvn clean package`. Test on a staging Paper server before deployment.
