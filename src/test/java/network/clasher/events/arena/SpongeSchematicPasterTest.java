package network.clasher.events.arena;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SpongeSchematicPasterTest {

    @Test
    void parsesAllEightBundledSchematicsAndVerifiesEverySpawnClearance() throws Exception {
        File resourcesDir = new File("src/main/resources");
        File arenasFile = new File(resourcesDir, "arenas.yml");
        assertTrue(arenasFile.isFile(), "arenas.yml must exist");

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(arenasFile);
        ConfigurationSection arenas = yml.getConfigurationSection("arenas");
        assertNotNull(arenas);
        assertEquals(8, arenas.getKeys(false).size(), "All 8 arenas must be configured");

        for (String arenaId : arenas.getKeys(false)) {
            ConfigurationSection sec = arenas.getConfigurationSection(arenaId);
            assertNotNull(sec);
            String schemName = sec.getString("schematic");
            File schemFile = new File(new File(resourcesDir, "schematics"), schemName);
            assertTrue(schemFile.isFile(), "Missing bundled schematic: " + schemName);

            SpongeSchematicPaster.ParsedSchematic parsed = SpongeSchematicPaster.parse(schemFile);
            assertTrue(parsed.width() >= 96, arenaId + " width should be >= 96");
            assertTrue(parsed.height() >= 24, arenaId + " height should be >= 24");
            assertTrue(parsed.length() >= 96, arenaId + " length should be >= 96");
            assertTrue(parsed.nonAirBlockCount() > 5000, arenaId + " must have substantial architecture");

            int pasteX = sec.getInt("paste.x");
            int pasteY = sec.getInt("paste.y");
            int pasteZ = sec.getInt("paste.z");

            // Gameplay guarantees for Crystal & Diamond Pot
            if (arenaId.equals("crystal")) {
                assertEquals("minecraft:bedrock", parsed.blockAt(-parsed.offsetX(), 0, -parsed.offsetZ()),
                        "Crystal arena must have blast-proof bedrock subfloor");
            }
            if (arenaId.equals("diamond-pot")) {
                String centerFloor = parsed.blockAt(-parsed.offsetX() + 10, 1, -parsed.offsetZ());
                assertFalse(centerFloor.contains("ice"), "Diamond Pot combat floor must NOT be slippery ice: " + centerFloor);
            }

            // Mathematically verify EVERY single spawn has solid non-hazard floor below and pure air at feet & head!
            for (String phase : List.of("wave1", "wave2", "final", "audience")) {
                List<Map<?, ?>> spawns = sec.getMapList("spawns." + phase);
                assertFalse(spawns.isEmpty(), arenaId + " missing spawns for " + phase);
                for (Map<?, ?> sp : spawns) {
                    int sx = (int) Math.floor(((Number) sp.get("x")).doubleValue()) - pasteX - parsed.offsetX();
                    int sy = (int) Math.floor(((Number) sp.get("y")).doubleValue()) - pasteY - parsed.offsetY();
                    int sz = (int) Math.floor(((Number) sp.get("z")).doubleValue()) - pasteZ - parsed.offsetZ();

                    String floor = parsed.blockAt(sx, sy - 1, sz);
                    String feet = parsed.blockAt(sx, sy, sz);
                    String head = parsed.blockAt(sx, sy + 1, sz);

                    assertNotEquals("minecraft:air", floor, arenaId + "." + phase + " spawn has air below!");
                    assertNotEquals("minecraft:lava", floor, arenaId + "." + phase + " spawn has lava below!");
                    assertNotEquals("minecraft:water", floor, arenaId + "." + phase + " spawn has water below!");
                    assertEquals("minecraft:air", feet, arenaId + "." + phase + " spawn feet blocked by " + feet);
                    assertEquals("minecraft:air", head, arenaId + "." + phase + " spawn head blocked by " + head);
                }
            }
        }
    }
}
