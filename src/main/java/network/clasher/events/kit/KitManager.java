package network.clasher.events.kit;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads YAML kit presets from plugins/ClasherEvents/kits/ and builds/applies gear,
 * enchantments, potions, and status effects using Paper 26.2 registries.
 */
public final class KitManager {
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
        YamlConfiguration kit = get(kitId);
        if (kit == null) {
            player.sendMessage(FormatUtil.mm("<red>Kit not found: " + FormatUtil.escape(kitId) + "</red>"));
            return false;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);

        ItemStack[] armorPieces = buildArmor(kit);
        player.getInventory().setHelmet(armorPieces[0]);
        player.getInventory().setChestplate(armorPieces[1]);
        player.getInventory().setLeggings(armorPieces[2]);
        player.getInventory().setBoots(armorPieces[3]);

        for (Map.Entry<Integer, ItemStack> entry : buildItems(kit).entrySet()) {
            int slot = entry.getKey();
            if (slot >= 0 && slot < 36) {
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

    public ItemStack[] buildArmor(YamlConfiguration kit) {
        ItemStack[] armor = new ItemStack[4];
        ConfigurationSection section = kit.getConfigurationSection("armor");
        if (section == null) return armor;
        armor[0] = matchItem(section.getString("helmet"));
        armor[1] = matchItem(section.getString("chestplate"));
        armor[2] = matchItem(section.getString("leggings"));
        armor[3] = matchItem(section.getString("boots"));

        ConfigurationSection armorEnchants = kit.getConfigurationSection("enchantments");
        if (armorEnchants != null) {
            for (ItemStack piece : armor) {
                if (piece == null) continue;
                for (String key : armorEnchants.getKeys(false)) {
                    Enchantment enchant = FormatUtil.resolveEnchantment(key);
                    if (enchant != null) {
                        piece.addUnsafeEnchantment(enchant, armorEnchants.getInt(key));
                    }
                }
            }
        }
        return armor;
    }

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
            if (row.get("potion") instanceof String potionName && stack.getItemMeta() instanceof PotionMeta potionMeta) {
                try {
                    potionMeta.setBasePotionType(PotionType.valueOf(potionName.toUpperCase(Locale.ROOT)));
                    stack.setItemMeta(potionMeta);
                } catch (IllegalArgumentException ignored) {
                    // Ignore unrecognized potion type names.
                }
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
        return result;
    }

    private static ItemStack matchItem(String name) {
        if (name == null || name.isBlank()) return null;
        Material material = Material.matchMaterial(name);
        return material == null ? null : new ItemStack(material);
    }
}
