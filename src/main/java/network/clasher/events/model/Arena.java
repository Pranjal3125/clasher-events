package network.clasher.events.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * Immutable definition of a configured arena in arenas.yml, including bounds
 * and true horizontal center helpers for accurate WorldBorder placement.
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
        return "ce_" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    public boolean hasBounds() {
        return section != null && section.getConfigurationSection("bounds") != null;
    }

    public int minX() {
        ConfigurationSection b = section == null ? null : section.getConfigurationSection("bounds");
        return b == null ? pasteX - 48 : b.getInt("min-x", pasteX - 48);
    }

    public int maxX() {
        ConfigurationSection b = section == null ? null : section.getConfigurationSection("bounds");
        return b == null ? pasteX + 48 : b.getInt("max-x", pasteX + 48);
    }

    public int minY() {
        ConfigurationSection b = section == null ? null : section.getConfigurationSection("bounds");
        return b == null ? pasteY - 1 : b.getInt("min-y", pasteY - 1);
    }

    public int maxY() {
        ConfigurationSection b = section == null ? null : section.getConfigurationSection("bounds");
        return b == null ? pasteY + 40 : b.getInt("max-y", pasteY + 40);
    }

    public int minZ() {
        ConfigurationSection b = section == null ? null : section.getConfigurationSection("bounds");
        return b == null ? pasteZ - 48 : b.getInt("min-z", pasteZ - 48);
    }

    public int maxZ() {
        ConfigurationSection b = section == null ? null : section.getConfigurationSection("bounds");
        return b == null ? pasteZ + 48 : b.getInt("max-z", pasteZ + 48);
    }

    public double centerX() {
        return hasBounds() ? (minX() + maxX()) / 2.0 + 0.5 : pasteX + 0.5;
    }

    public double centerZ() {
        return hasBounds() ? (minZ() + maxZ()) / 2.0 + 0.5 : pasteZ + 0.5;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX() && x <= maxX()
                && y >= minY() && y <= maxY()
                && z >= minZ() && z <= maxZ();
    }
}
