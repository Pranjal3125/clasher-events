package network.clasher.events.kit;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class KitManagerTest {

    @Test
    void validatesAllEightAuthenticPvpKitsPotionsOffhandsAndZeroPreAppliedEffects() {
        File kitsDir = new File("src/main/resources/kits");
        assertTrue(kitsDir.isDirectory(), "kits resource directory must exist");

        KitManager km = new KitManager(new File("src/main/resources"));
        km.load();
        assertEquals(8, km.kitIds().size(), "All 8 PvP kits must load cleanly");

        for (String kitId : km.kitIds()) {
            YamlConfiguration kit = km.get(kitId);
            assertNotNull(kit);
            // Authentic MCTiers / Stray.gg / PvP Legacy rule: NO free pre-applied potion effects on spawn!
            assertTrue(kit.getMapList("effects").isEmpty(), kitId + " must NOT pre-apply potion effects on spawn");

            ConfigurationSection armor = kit.getConfigurationSection("armor");
            assertNotNull(armor, kitId + " must define armor");
            for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
                String matName = armor.getString(piece);
                assertNotNull(Material.matchMaterial(matName), kitId + " invalid armor material: " + matName);
            }
            String offhand = kit.getString("offhand");
            if (offhand != null && !offhand.isBlank()) {
                assertNotNull(Material.matchMaterial(offhand), kitId + " invalid offhand material: " + offhand);
            }
            String fillPot = kit.getString("fill-potion", "");
            if (!fillPot.isBlank()) {
                assertDoesNotThrow(() -> PotionType.valueOf(fillPot.toUpperCase(Locale.ROOT)),
                        kitId + " invalid fill-potion: " + fillPot);
            }
            List<Map<?, ?>> items = kit.getMapList("items");
            assertFalse(items.isEmpty(), kitId + " must define kit items");
            for (Map<?, ?> row : items) {
                String matName = String.valueOf(row.get("material"));
                assertNotNull(Material.matchMaterial(matName), kitId + " invalid item material: " + matName);
                if (row.get("potion") instanceof String pName) {
                    assertDoesNotThrow(() -> PotionType.valueOf(pName.toUpperCase(Locale.ROOT)),
                            kitId + " invalid potion type: " + pName);
                }
            }
        }

        // Verify authentic popular PvP server kit meta across all 8 modes!
        assertEquals("GOLDEN_APPLE", km.get("nethpot").getString("offhand"));
        assertEquals(64, km.get("nethpot").getInt("offhand-amount"));
        assertEquals("STRONG_HEALING", km.get("nethpot").getString("fill-potion"));
        assertEquals("GOLDEN_CARROT", km.get("diamond-pot").getString("offhand"));
        assertEquals("STRONG_HEALING", km.get("diamond-pot").getString("fill-potion"));
        assertEquals("SHIELD", km.get("axe-shield").getString("offhand"));
        assertEquals("SHIELD", km.get("uhc").getString("offhand"));
        assertEquals("TOTEM_OF_UNDYING", km.get("crystal").getString("offhand"));
        assertEquals(4, km.get("crystal").getInt("armor-enchantments.leggings.blast_protection"));
        assertEquals("TOTEM_OF_UNDYING", km.get("smp").getString("offhand"));
        assertTrue(km.get("build-uhc").getMapList("items").stream()
                .anyMatch(r -> "DIAMOND_PICKAXE".equals(r.get("material"))), "Build UHC must include Diamond Pickaxe");
    }

    @Test
    void applyCustomLayoutSwapsSlotsAndOffhandWithoutDuplicationOrLoss() {
        Map<Integer, String> defaults = new LinkedHashMap<>();
        defaults.put(0, "SWORD");
        defaults.put(1, "PEARL");
        defaults.put(2, "GAPPLE");
        defaults.put(KitManager.OFFHAND_SLOT, "SHIELD");

        Map<Integer, Integer> layout = Map.of(
                0, 8,
                2, KitManager.OFFHAND_SLOT,
                KitManager.OFFHAND_SLOT, 0,
                1, 2
        );

        Map<Integer, String> result = KitManager.applyCustomLayout(defaults, layout);
        assertEquals(4, result.size(), "Must preserve exact item count");
        assertEquals("SWORD", result.get(8));
        assertEquals("GAPPLE", result.get(KitManager.OFFHAND_SLOT));
        assertEquals("SHIELD", result.get(0));
        assertEquals("PEARL", result.get(2));
        assertEquals(new HashSet<>(defaults.values()), new HashSet<>(result.values()), "Zero items lost or duplicated");

        Map<Integer, String> safe = KitManager.applyCustomLayout(defaults, Map.of(0, 5, 1, 5));
        assertEquals(4, safe.size());
        assertEquals(new HashSet<>(defaults.values()), new HashSet<>(safe.values()));
    }
}
