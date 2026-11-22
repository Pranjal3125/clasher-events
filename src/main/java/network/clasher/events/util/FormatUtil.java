package network.clasher.events.util;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/**
 * Shared formatting, MiniMessage parsing, and modern Paper 26.2 registry lookup utilities.
 */
public final class FormatUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();

    private FormatUtil() {}

    public static Component mm(String input) {
        return MM.deserialize(input);
    }

    /**
     * Parses a user-configured string supporting both MiniMessage tags and legacy '&' codes.
     */
    public static Component parseFlexible(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        if (input.contains("<") && input.contains(">")) {
            return MM.deserialize(input);
        }
        return LEGACY_AMP.deserialize(input);
    }

    public static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static String escape(String raw) {
        return raw == null ? "" : raw.replace("<", "\\<");
    }

    public static String formatDuration(long totalSeconds) {
        long clamped = Math.max(0L, totalSeconds);
        long hours = clamped / 3600L;
        long minutes = (clamped % 3600L) / 60L;
        long seconds = clamped % 60L;
        if (hours > 0) return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %02ds", minutes, seconds);
        return seconds + "s";
    }

    public static Enchantment resolveEnchantment(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        NamespacedKey key = parseMinecraftKey(rawKey);
        if (key == null) return null;
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
    }

    public static PotionEffectType resolvePotionEffect(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        String normalized = rawKey.trim().toLowerCase(Locale.ROOT);
        // Common legacy Bukkit PotionEffectType aliases mapped to Minecraft keys
        normalized = switch (normalized) {
            case "increase_damage" -> "strength";
            case "damage_resistance" -> "resistance";
            case "fast_digging" -> "haste";
            case "slow_digging" -> "mining_fatigue";
            case "heal" -> "instant_health";
            case "harm" -> "instant_damage";
            case "jump" -> "jump_boost";
            case "confusion" -> "nausea";
            case "slow" -> "slowness";
            default -> normalized;
        };
        NamespacedKey key = parseMinecraftKey(normalized);
        if (key == null) return null;
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(key);
    }

    public static Sound resolveSound(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        String trimmed = rawKey.trim();
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT);

        // Try direct key (e.g. "ui.button.click" or "minecraft:ui.button.click")
        NamespacedKey direct = parseMinecraftKey(trimmed);
        if (direct != null) {
            Sound found = registry.get(direct);
            if (found != null) return found;
        }

        // Convert legacy Bukkit enum names (e.g. "UI_BUTTON_CLICK" -> "ui.button.click", "BLOCK_NOTE_BLOCK_PLING" -> "block.note_block.pling")
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String dotted = lower.replace('_', '.');
        NamespacedKey dottedKey = parseMinecraftKey(dotted);
        if (dottedKey != null) {
            Sound found = registry.get(dottedKey);
            if (found != null) return found;
        }
        // Fallback for multi-word segments like block.note_block.pling
        String[] knownCompounds = {"note_block", "page_turn", "challenge_complete", "button_click"};
        String adjusted = dotted;
        for (String compound : knownCompounds) {
            adjusted = adjusted.replace(compound.replace('_', '.'), compound);
        }
        NamespacedKey adjustedKey = parseMinecraftKey(adjusted);
        return adjustedKey == null ? null : registry.get(adjustedKey);
    }

    private static NamespacedKey parseMinecraftKey(String raw) {
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.contains(":")) {
            return NamespacedKey.fromString(lower);
        }
        return NamespacedKey.fromString("minecraft:" + lower);
    }
}
