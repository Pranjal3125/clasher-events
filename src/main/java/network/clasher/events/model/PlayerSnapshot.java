package network.clasher.events.model;

import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a player's pre-event inventory, stats, potion effects, and location
 * used for clean post-event restoration and crash-safe recovery on reconnect.
 */
public final class PlayerSnapshot {
    private final ItemStack[] storage;
    private final ItemStack[] armor;
    private final ItemStack offhand;
    private final GameMode mode;
    private final double health;
    private final int food;
    private final int level;
    private final float exp;
    private final float saturation;
    private final int fireTicks;
    private final Location location;
    private final Collection<PotionEffect> effects;

    public PlayerSnapshot(Player player) {
        this.storage = cloneItems(player.getInventory().getStorageContents());
        this.armor = cloneItems(player.getInventory().getArmorContents());
        ItemStack off = player.getInventory().getItemInOffHand();
        this.offhand = off.getType().isAir() ? null : off.clone();
        this.mode = player.getGameMode();
        this.health = Math.max(1.0, player.getHealth());
        this.food = player.getFoodLevel();
        this.level = player.getLevel();
        this.exp = player.getExp();
        this.saturation = player.getSaturation();
        this.fireTicks = player.getFireTicks();
        this.location = player.getLocation().clone();
        this.effects = new ArrayList<>(player.getActivePotionEffects());
    }

    private PlayerSnapshot(
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack offhand,
            GameMode mode,
            double health,
            int food,
            int level,
            float exp,
            float saturation,
            int fireTicks,
            Location location,
            Collection<PotionEffect> effects
    ) {
        this.storage = cloneItems(storage);
        this.armor = cloneItems(armor);
        this.offhand = offhand == null ? null : offhand.clone();
        this.mode = mode;
        this.health = health;
        this.food = food;
        this.level = level;
        this.exp = exp;
        this.saturation = saturation;
        this.fireTicks = fireTicks;
        this.location = location;
        this.effects = new ArrayList<>(effects);
    }

    public void writeTo(ConfigurationSection section) {
        section.set("storage", Arrays.asList(storage));
        section.set("armor", Arrays.asList(armor));
        section.set("offhand", offhand);
        section.set("gamemode", mode.name());
        section.set("health", health);
        section.set("food", food);
        section.set("level", level);
        section.set("exp", exp);
        section.set("saturation", saturation);
        section.set("fire", fireTicks);
        section.set("world", location.getWorld() == null ? "" : location.getWorld().getName());
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", location.getYaw());
        section.set("pitch", location.getPitch());

        List<Map<String, Object>> serializedEffects = new ArrayList<>();
        for (PotionEffect effect : effects) {
            Map<String, Object> row = new HashMap<>();
            row.put("type", effect.getType().getKey().getKey());
            row.put("duration", effect.getDuration());
            row.put("amplifier", effect.getAmplifier());
            row.put("ambient", effect.isAmbient());
            row.put("particles", effect.hasParticles());
            row.put("icon", effect.hasIcon());
            serializedEffects.add(row);
        }
        section.set("effects", serializedEffects);
    }

    public static PlayerSnapshot from(ConfigurationSection section) {
        ItemStack[] storage = readItems(section.getList("storage", Collections.emptyList()), 36);
        ItemStack[] armor = readItems(section.getList("armor", Collections.emptyList()), 4);
        ItemStack offhand = section.getItemStack("offhand");
        GameMode mode;
        try {
            mode = GameMode.valueOf(section.getString("gamemode", "SURVIVAL"));
        } catch (IllegalArgumentException ex) {
            mode = GameMode.SURVIVAL;
        }
        World world = Bukkit.getWorld(section.getString("world", ""));
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }
        Location location = new Location(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch")
        );
        List<PotionEffect> effects = new ArrayList<>();
        for (Map<?, ?> row : section.getMapList("effects")) {
            PotionEffectType type = FormatUtil.resolvePotionEffect(String.valueOf(row.get("type")));
            if (type == null) continue;
            int duration = row.get("duration") instanceof Number n ? n.intValue() : 20;
            int amplifier = row.get("amplifier") instanceof Number n ? n.intValue() : 0;
            effects.add(new PotionEffect(
                    type,
                    duration,
                    amplifier,
                    Boolean.TRUE.equals(row.get("ambient")),
                    !Boolean.FALSE.equals(row.get("particles")),
                    !Boolean.FALSE.equals(row.get("icon"))
            ));
        }
        return new PlayerSnapshot(
                storage,
                armor,
                offhand,
                mode,
                section.getDouble("health", 20.0),
                section.getInt("food", 20),
                section.getInt("level", 0),
                (float) section.getDouble("exp", 0.0),
                (float) section.getDouble("saturation", 5.0),
                section.getInt("fire", 0),
                location,
                effects
        );
    }

    public void restore(Player player, Location destination) {
        player.getInventory().clear();
        player.getInventory().setStorageContents(cloneItems(storage));
        player.getInventory().setArmorContents(cloneItems(armor));
        player.getInventory().setItemInOffHand(offhand == null ? null : offhand.clone());
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }
        player.setGameMode(mode);
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
        player.setHealth(Math.max(1.0, Math.min(health, maxHealth)));
        player.setFoodLevel(food);
        player.setLevel(level);
        player.setExp(exp);
        player.setSaturation(saturation);
        player.setFireTicks(fireTicks);
        Location target = destination != null ? destination : location;
        if (target != null && target.getWorld() != null) {
            player.teleport(target);
        }
    }

    private static ItemStack[] readItems(List<?> values, int size) {
        ItemStack[] result = new ItemStack[size];
        for (int i = 0; i < Math.min(size, values.size()); i++) {
            if (values.get(i) instanceof ItemStack item) {
                result[i] = item.clone();
            }
        }
        return result;
    }

    private static ItemStack[] cloneItems(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }
}
