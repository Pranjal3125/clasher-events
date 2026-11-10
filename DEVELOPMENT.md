# Clasher Events — development status

## Confirmed design
- One shared arena world: clasher_events.
- Eight separately configured schematic arenas at spaced coordinates.
- Unique themes: Nether Citadel, Ironkeep, Verdant Basin, Grand Colosseum, Wild Frontier, End Rift, Fallen Kingdom and Frostglass.
- Eight independent YAML kits: NethPot, Axe & Shield, Build UHC, Sword, UHC, Crystal, SMP and Diamond Pot.

## Current source milestone
- Paper plugin bootstrap and generated configuration files.
- Shared-world arena registry.
- WorldEdit schematic loading and paste command.
- YAML kit loader for armor, items, enchantments and potion effects.
- Basic registration, start, elimination and winner flow.

## Planned / incomplete
- Bundled polished schematic arena assets.
- WorldGuard region creation and enforcement.
- True arena snapshot/rollback reset.
- Wave 1 to Wave 2 to Final transitions and WorldBorder shrinking.
- Registration countdown and kit delivery at the 60-second mark.
- Pause/resume, GUI voting, chat prediction, persistent audience points and rewards.
- Complete duel brackets, team assignment and random-kit flow.
- Full player state restoration and production-grade edge-case handling.

## Build and test
Build with JDK 25 using mvn clean package. Test on a staging copy of the exact Paper build before public use.