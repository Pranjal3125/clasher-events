package network.clasher.events.arena;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SpongeSchematicPasterTest {

    private static void assertAllPaletteMaterialsValid(String label, SpongeSchematicPaster.ParsedSchematic parsed) {
        assertTrue(parsed.palette().length >= 10, label + " should have a rich architectural palette");
        for (String entry : parsed.palette()) {
            if (entry == null) continue;
            int bracket = entry.indexOf('[');
            String baseKey = bracket >= 0 ? entry.substring(0, bracket) : entry;
            Material mat = Material.matchMaterial(baseKey);
            assertNotNull(mat, label + " contains unknown Paper 26.2 material in palette: " + entry);
        }
    }

    private static void assertSpawnClearance(
            String label,
            SpongeSchematicPaster.ParsedSchematic parsed,
            int pasteX, int pasteY, int pasteZ,
            double worldX, double worldY, double worldZ
    ) {
        int sx = (int) Math.floor(worldX) - pasteX - parsed.offsetX();
        int sy = (int) Math.floor(worldY) - pasteY - parsed.offsetY();
        int sz = (int) Math.floor(worldZ) - pasteZ - parsed.offsetZ();

        String floor = parsed.blockAt(sx, sy - 1, sz);
        String feet = parsed.blockAt(sx, sy, sz);
        String head = parsed.blockAt(sx, sy + 1, sz);

        assertNotEquals("minecraft:air", floor, label + " spawn has air below!");
        assertNotEquals("minecraft:lava", floor, label + " spawn has lava below!");
        assertNotEquals("minecraft:water", floor, label + " spawn has water below!");
        assertEquals("minecraft:air", feet, label + " spawn feet blocked by " + feet);
        assertEquals("minecraft:air", head, label + " spawn head blocked by " + head);
    }

    @Test
    void parsesAllEightBundledArenasValidatesPalettesAndEverySpawnClearance() throws Exception {
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
            assertTrue(parsed.width() >= 108, arenaId + " width should be >= 108");
            assertTrue(parsed.height() >= 42, arenaId + " height should be >= 42");
            assertTrue(parsed.length() >= 108, arenaId + " length should be >= 108");
            assertTrue(parsed.nonAirBlockCount() > 50_000, arenaId + " must have rich architectural detail");
            assertAllPaletteMaterialsValid(arenaId, parsed);

            int pasteX = sec.getInt("paste.x");
            int pasteY = sec.getInt("paste.y");
            int pasteZ = sec.getInt("paste.z");

            // Gameplay guarantees for Crystal & Diamond Pot
            if (arenaId.equals("crystal")) {
                assertEquals("minecraft:bedrock", parsed.blockAt(-parsed.offsetX(), -parsed.offsetY(), -parsed.offsetZ()),
                        "Crystal arena must have blast-proof bedrock subfloor");
            }
            if (arenaId.equals("diamond-pot")) {
                String centerFloor = parsed.blockAt(-parsed.offsetX() + 10, 1 - parsed.offsetY(), -parsed.offsetZ());
                assertFalse(centerFloor.contains("ice"), "Diamond Pot combat floor must NOT be slippery ice: " + centerFloor);
            }

            // Mathematically verify EVERY single spawn has solid non-hazard floor below and pure air at feet & head!
            for (String phase : List.of("wave1", "wave2", "final", "audience")) {
                List<Map<?, ?>> spawns = sec.getMapList("spawns." + phase);
                assertFalse(spawns.isEmpty(), arenaId + " missing spawns for " + phase);
                for (Map<?, ?> sp : spawns) {
                    assertSpawnClearance(
                            arenaId + "." + phase,
                            parsed,
                            pasteX, pasteY, pasteZ,
                            ((Number) sp.get("x")).doubleValue(),
                            ((Number) sp.get("y")).doubleValue(),
                            ((Number) sp.get("z")).doubleValue()
                    );
                }
            }
        }
    }

    @Test
    void parsesLobbyAndWinnerPodiumSchematicsAndVerifiesConfigSpawnClearance() throws Exception {
        File resourcesDir = new File("src/main/resources");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(resourcesDir, "config.yml"));

        File lobbyFile = new File(new File(resourcesDir, "schematics"), "lobby.schem");
        assertTrue(lobbyFile.isFile(), "lobby.schem must exist");
        SpongeSchematicPaster.ParsedSchematic lobby = SpongeSchematicPaster.parse(lobbyFile);
        assertTrue(lobby.width() >= 96 && lobby.height() >= 44 && lobby.length() >= 96, "Lobby dimensions");
        assertTrue(lobby.offsetY() < 0, "Sky Citadel Lobby must include floating island rock underside");
        assertTrue(lobby.nonAirBlockCount() > 20_000, "Lobby must have rich floating island architecture");
        assertAllPaletteMaterialsValid("lobby", lobby);

        assertSpawnClearance(
                "config.locations.lobby",
                lobby,
                ArenaManager.LOBBY_ORIGIN_X, ArenaManager.LOBBY_ORIGIN_Y, ArenaManager.LOBBY_ORIGIN_Z,
                cfg.getDouble("locations.lobby.x"),
                cfg.getDouble("locations.lobby.y"),
                cfg.getDouble("locations.lobby.z")
        );

        File winnerFile = new File(new File(resourcesDir, "schematics"), "winner-podium.schem");
        assertTrue(winnerFile.isFile(), "winner-podium.schem must exist");
        SpongeSchematicPaster.ParsedSchematic winner = SpongeSchematicPaster.parse(winnerFile);
        assertTrue(winner.width() >= 76 && winner.height() >= 34 && winner.length() >= 76, "Winner Podium dimensions");
        assertTrue(winner.offsetY() < 0, "Winner Podium must include floating island underside");
        assertTrue(winner.nonAirBlockCount() > 14_000, "Winner Podium must have rich stadium architecture");
        assertAllPaletteMaterialsValid("winner-podium", winner);

        assertSpawnClearance(
                "config.locations.winner",
                winner,
                ArenaManager.WINNER_ORIGIN_X, ArenaManager.WINNER_ORIGIN_Y, ArenaManager.WINNER_ORIGIN_Z,
                cfg.getDouble("locations.winner.x"),
                cfg.getDouble("locations.winner.y"),
                cfg.getDouble("locations.winner.z")
        );
        assertSpawnClearance(
                "config.locations.winner-crowd",
                winner,
                ArenaManager.WINNER_ORIGIN_X, ArenaManager.WINNER_ORIGIN_Y, ArenaManager.WINNER_ORIGIN_Z,
                cfg.getDouble("locations.winner-crowd.x"),
                cfg.getDouble("locations.winner-crowd.y"),
                cfg.getDouble("locations.winner-crowd.z")
        );
    }
}
