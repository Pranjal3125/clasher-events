package network.clasher.events.kit;

import network.clasher.events.storage.YamlAtomicStorage;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Loads YAML kit presets from plugins/ClasherEvents/kits/, builds/applies armor, offhand,
 * per-piece enchantments, fill-potions, status effects, per-player custom hotbar layouts,
 * and supports in-game admin kit saving (/ce kit save <kit>).
 */
public final class KitManager {
    public static final int OFFHAND_SLOT = 40;

    private final File kitsDir;
    private final Map<String, YamlConfiguration> kits = new LinkedHashMap<>();

    public KitManager(File dataFolder) {
        this.kitsDir = new File(dataFolder, "kits");
    }

    public void load() {
        kits.clear();
        File[] files = kitsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            kits.put(id, YamlConfiguration.loadConfiguration(file));
        }
    }

    public boolean exists(String kitId) {
        return kitId != null && kits.containsKey(kitId.toLowerCase(Locale.ROOT));
    }

    public YamlConfiguration get(String kitId) {
        return kitId == null ? null : kits.get(kitId.toLowerCase(Locale.ROOT));
    }

    public Set<String> kitIds() {
        return Collections.unmodifiableSet(kits.keySet());
    }

    public boolean giveKit(Player player, String kitId) {
        return giveKit(player, kitId, null);
    }

    public boolean giveKit(Player player, String kitId, Map<Integer, Integer> customLayout) {
        YamlConfiguration kit = get(kitId);
        if (kit == null) {
            player.sendMessage(FormatUtil.mm("<red>Kit not found: " + FormatUtil.escape(kitId) + "</red>"));
            return false;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        for (PotionEffect active : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(active.getType());
        }

        ItemStack[] armorPieces = buildArmor(kit);
        player.getInventory().setHelmet(armorPieces[0]);
        player.getInventory().setChestplate(armorPieces[1]);
        player.getInventory().setLeggings(armorPieces[2]);
        player.getInventory().setBoots(armorPieces[3]);

        Map<Integer, ItemStack> items = applyCustomLayout(buildAllInventorySlots(kit), customLayout);
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            int slot = entry.getKey();
            if (slot == OFFHAND_SLOT) {
                player.getInventory().setItemInOffHand(entry.getValue());
            } else if (slot >= 0 && slot < 36) {
                player.getInventory().setItem(slot, entry.getValue());
            }
        }

        for (Map<?, ?> row : kit.getMapList("effects")) {
            PotionEffectType type = FormatUtil.resolvePotionEffect(String.valueOf(row.get("type")));
            if (type == null) continue;
            int duration = row.get("duration") instanceof Number d ? Math.max(20, d.intValue()) : 20 * 60 * 10;
            int amplifier = row.get("amplifier") instanceof Number a ? Math.max(0, a.intValue()) : 0;
            player.addPotionEffect(new PotionEffect(type, duration, amplifier));
        }
        player.updateInventory();
        return true;
    }

    /**
     * Builds armor array [helmet, chestplate, leggings, boots] supporting both shared
     * 'enchantments:' and per-piece 'armor-enchantments.<piece>:' overrides.
     */
    public ItemStack[] buildArmor(YamlConfiguration kit) {
        ItemStack[] armor = new ItemStack[4];
        ConfigurationSection section = kit.getConfigurationSection("armor");
        if (section == null) return armor;
        String[] pieceKeys = {"helmet", "chestplate", "leggings", "boots"};
        for (int i = 0; i < 4; i++) {
            armor[i] = matchItem(section.getString(pieceKeys[i]));
        }

        ConfigurationSection sharedEnchants = kit.getConfigurationSection("enchantments");
        ConfigurationSection perPiece = kit.getConfigurationSection("armor-enchantments");
        for (int i = 0; i < 4; i++) {
            ItemStack piece = armor[i];
            if (piece == null) continue;
            ConfigurationSection specific = perPiece == null ? null : perPiece.getConfigurationSection(pieceKeys[i]);
            ConfigurationSection source = specific != null ? specific : sharedEnchants;
            if (source != null) {
                for (String key : source.getKeys(false)) {
                    Enchantment enchant = FormatUtil.resolveEnchantment(key);
                    if (enchant != null) {
                        piece.addUnsafeEnchantment(enchant, source.getInt(key));
                    }
                }
            }
        }
        return armor;
    }

    public ItemStack buildOffhand(YamlConfiguration kit) {
        if (kit == null) return null;
        ItemStack offhand = matchItem(kit.getString("offhand"));
        if (offhand == null) return null;
        int amt = kit.getInt("offhand-amount", 1);
        offhand.setAmount(Math.max(1, Math.min(64, amt)));
        ConfigurationSection enchants = kit.getConfigurationSection("offhand-enchants");
        if (enchants != null) {
            for (String key : enchants.getKeys(false)) {
                Enchantment enchant = FormatUtil.resolveEnchantment(key);
                if (enchant != null) {
                    offhand.addUnsafeEnchantment(enchant, enchants.getInt(key));
                }
            }
        }
        return offhand;
    }

    /**
     * Builds inventory slots 0..35 including optional 'fill-potion' splash healing refills.
     */
    public Map<Integer, ItemStack> buildItems(YamlConfiguration kit) {
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        List<Map<?, ?>> rows = kit.getMapList("items");
        for (Map<?, ?> row : rows) {
            Object slotObj = row.get("slot");
            Object matObj = row.get("material");
            if (!(slotObj instanceof Number slotNum) || !(matObj instanceof String matName)) continue;
            ItemStack stack = matchItem(matName);
            if (stack == null) continue;
            if (row.get("amount") instanceof Number amt) {
                stack.setAmount(Math.max(1, Math.min(64, amt.intValue())));
            }
            if (row.get("potion") instanceof String potionName) {
                applyPotionType(stack, potionName);
            }
            if (row.get("enchants") instanceof Map<?, ?> enchants) {
                for (Map.Entry<?, ?> entry : enchants.entrySet()) {
                    Enchantment enchant = FormatUtil.resolveEnchantment(String.valueOf(entry.getKey()));
                    if (enchant != null && entry.getValue() instanceof Number lvl) {
                        stack.addUnsafeEnchantment(enchant, lvl.intValue());
                    }
                }
            }
            result.put(slotNum.intValue(), stack);
        }

        // Optional full-inventory splash potion filler (authentic NethPot & Diamond Pot!)
        String fillPotion = kit.getString("fill-potion", "");
        if (fillPotion != null && !fillPotion.isBlank()) {
            String fillMat = kit.getString("fill-material", "SPLASH_POTION");
            for (int slot = 0; slot < 36; slot++) {
                if (!result.containsKey(slot)) {
                    ItemStack pot = matchItem(fillMat);
                    if (pot != null) {
                        applyPotionType(pot, fillPotion);
                        result.put(slot, pot);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns slots 0..35 PLUS OFFHAND_SLOT (40) when configured.
     */
    public Map<Integer, ItemStack> buildAllInventorySlots(YamlConfiguration kit) {
        Map<Integer, ItemStack> all = new LinkedHashMap<>(buildItems(kit));
        ItemStack offhand = buildOffhand(kit);
        if (offhand != null) {
            all.put(OFFHAND_SLOT, offhand);
        }
        return all;
    }

    /**
     * Pure, safe slot permutation: maps default kit slots -> player-preferred slots without
     * ever duplicating, forging, or dropping an item. Any unmapped or colliding item cleanly
     * falls back to the first free slot.
     */
    public static <T> Map<Integer, T> applyCustomLayout(Map<Integer, T> defaults, Map<Integer, Integer> customSlotMap) {
        if (defaults == null || defaults.isEmpty() || customSlotMap == null || customSlotMap.isEmpty()) {
            return defaults == null ? Map.of() : new LinkedHashMap<>(defaults);
        }
        Map<Integer, T> remapped = new LinkedHashMap<>();
        List<Map.Entry<Integer, T>> overflow = new ArrayList<>();
        Set<Integer> usedTargets = new HashSet<>();

        for (Map.Entry<Integer, T> entry : defaults.entrySet()) {
            int origSlot = entry.getKey();
            Integer desired = customSlotMap.get(origSlot);
            int target = (desired != null && isValidTargetSlot(desired) && !usedTargets.contains(desired))
                    ? desired : origSlot;
            if (isValidTargetSlot(target) && usedTargets.add(target)) {
                remapped.put(target, entry.getValue());
            } else {
                overflow.add(entry);
            }
        }
        for (Map.Entry<Integer, T> entry : overflow) {
            for (int slot = 0; slot < 36; slot++) {
                if (usedTargets.add(slot)) {
                    remapped.put(slot, entry.getValue());
                    break;
                }
            }
        }
        return remapped;
    }

    private static boolean isValidTargetSlot(int slot) {
        return (slot >= 0 && slot < 36) || slot == OFFHAND_SLOT;
    }

    /**
     * Saves an admin's current inventory, armor, offhand, and potion effects directly to kits/<kitId>.yml.
     */
    public boolean saveKitFromPlayer(Player admin, String kitId, Logger logger) {
        String id = kitId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (id.isEmpty()) return false;
        if (!kitsDir.exists()) kitsDir.mkdirs();

        YamlConfiguration yml = new YamlConfiguration();
        yml.set("name", kitId);

        ItemStack[] armor = {
                admin.getInventory().getHelmet(),
                admin.getInventory().getChestplate(),
                admin.getInventory().getLeggings(),
                admin.getInventory().getBoots()
        };
        String[] pieceKeys = {"helmet", "chestplate", "leggings", "boots"};
        for (int i = 0; i < 4; i++) {
            if (armor[i] != null && !armor[i].getType().isAir()) {
                yml.set("armor." + pieceKeys[i], armor[i].getType().name());
                for (Map.Entry<Enchantment, Integer> e : armor[i].getEnchantments().entrySet()) {
                    yml.set("armor-enchantments." + pieceKeys[i] + "." + e.getKey().getKey().getKey(), e.getValue());
                }
            }
        }

        ItemStack offhand = admin.getInventory().getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            yml.set("offhand", offhand.getType().name());
            if (offhand.getAmount() > 1) yml.set("offhand-amount", offhand.getAmount());
            for (Map.Entry<Enchantment, Integer> e : offhand.getEnchantments().entrySet()) {
                yml.set("offhand-enchants." + e.getKey().getKey().getKey(), e.getValue());
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = admin.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot);
            row.put("material", item.getType().name());
            row.put("amount", item.getAmount());
            if (item.getItemMeta() instanceof PotionMeta pm && pm.getBasePotionType() != null) {
                row.put("potion", pm.getBasePotionType().name());
            }
            if (!item.getEnchantments().isEmpty()) {
                Map<String, Integer> enchMap = new LinkedHashMap<>();
                for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
                    enchMap.put(e.getKey().getKey().getKey(), e.getValue());
                }
                row.put("enchants", enchMap);
            }
            items.add(row);
        }
        yml.set("items", items);

        List<Map<String, Object>> effects = new ArrayList<>();
        for (PotionEffect effect : admin.getActivePotionEffects()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", effect.getType().getKey().getKey().toUpperCase(Locale.ROOT));
            row.put("amplifier", effect.getAmplifier());
            row.put("duration", Math.max(200, effect.getDuration()));
            effects.add(row);
        }
        if (!effects.isEmpty()) yml.set("effects", effects);

        File target = new File(kitsDir, id + ".yml");
        boolean saved = YamlAtomicStorage.saveAtomically(yml, target, logger, "Could not save kit " + id);
        if (saved) load();
        return saved;
    }

    private static void applyPotionType(ItemStack stack, String potionName) {
        if (stack.getItemMeta() instanceof PotionMeta potionMeta) {
            try {
                potionMeta.setBasePotionType(PotionType.valueOf(potionName.toUpperCase(Locale.ROOT)));
                stack.setItemMeta(potionMeta);
            } catch (IllegalArgumentException ignored) {
                // Ignore unrecognized potion type names.
            }
        }
    }

    private static ItemStack matchItem(String name) {
        if (name == null || name.isBlank()) return null;
        Material material = Material.matchMaterial(name);
        return material == null ? null : new ItemStack(material);
    }
}
