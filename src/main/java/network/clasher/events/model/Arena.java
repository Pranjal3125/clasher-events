package network.clasher.events.model;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable definition of a configured arena in arenas.yml.
 */
public record Arena(
        String id,
        String displayName,
        String schematic,
        int pasteX,
        int pasteY,
        int pasteZ,
        ConfigurationSection section
) {
    public String worldGuardRegionId() {
        return "ce_" + id.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
