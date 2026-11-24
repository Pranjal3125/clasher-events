package network.clasher.events.model;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * Optional toggleable Event Modifiers / Mutators that admins can stack onto any official event
 * via `/ce modifiers` or the `/ce gui` Modifiers panel!
 */
public enum EventModifier {
    DOUBLE_HP("double-hp", "Double Health (40 HP)", "All fighters start with 20 ❤ (40 HP)!", Material.GOLDEN_APPLE),
    VAMPIRE("vampire", "Vampire Lifesteal", "Heal 25% of every melee hit dealt!", Material.REDSTONE),
    LOW_GRAVITY("low-gravity", "Low Gravity", "Permanent Jump Boost III + Zero Fall Damage!", Material.FEATHER),
    SPEED_CHAOS("speed-chaos", "Speed Chaos", "Permanent Speed II turbo-paced combat!", Material.SUGAR),
    NO_PEARLS("no-pearls", "No Ender Pearls", "Disables Ender Pearl throws during combat!", Material.ENDER_PEARL),
    ONE_HIT_BOW("one-hit-bow", "One-Hit Bow", "Bow arrows deal instant lethal damage!", Material.BOW);

    private final String id;
    private final String displayName;
    private final String description;
    private final Material icon;

    EventModifier(String id, String displayName, String description, Material icon) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public Material icon() { return icon; }

    public static Optional<EventModifier> fromInput(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (EventModifier m : values()) {
            if (m.id.equals(key) || m.name().equalsIgnoreCase(raw.trim())) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
