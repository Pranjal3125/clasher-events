package network.clasher.events.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * Immutable definition of a configured arena in arenas.yml, including bounds,
 * combat containment radius/ceiling, and true horizontal center helpers.
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
        return b == null ? pasteY + 54 : b.getInt("max-y", pasteY + 54);
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

    /**
     * Playable horizontal combat radius inside the solid arena perimeter walls.
     */
    public double playableRadius() {
        ConfigurationSection b = section == null ? null : section.getConfigurationSection("bounds");
        double fallback = Math.max(16.0, Math.min(maxX() - minX(), maxZ() - minZ()) / 2.0 - 8.0);
        return b == null ? fallback : b.getDouble("playable-radius", fallback);
    }

    /**
     * Maximum Y height fighters may reach or build to (safely below the waiting skybox).
     */
    public int combatMaxY() {
        ConfigurationSection b = section == null ? null : section.getConfigurationSection("bounds");
        return b == null ? pasteY + 36 : b.getInt("combat-max-y", pasteY + 36);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX() && x <= maxX()
                && y >= minY() && y <= maxY()
                && z >= minZ() && z <= maxZ();
    }

    /**
     * Strict fighter containment check (prevents pearling over walls, towering to skybox, or escaping to void).
     */
    public boolean isInsideCombatBounds(double x, double y, double z) {
        double dx = x - centerX();
        double dz = z - centerZ();
        double r = playableRadius();
        return (dx * dx + dz * dz) <= r * r
                && y >= pasteY + 0.5
                && y <= combatMaxY();
    }

    /**
     * Strict block-editing bounds for destructible modes (protects subfloor, outer walls, and waiting skybox).
     */
    public boolean isInsideBuildBounds(int x, int y, int z) {
        double dx = (x + 0.5) - centerX();
        double dz = (z + 0.5) - centerZ();
        double r = Math.max(10.0, playableRadius() - 1.0);
        return (dx * dx + dz * dz) <= r * r
                && y >= pasteY + 1
                && y <= combatMaxY();
    }

    /**
     * Generous spectator viewing envelope around the active arena (prevents flying away to other arenas/void).
     */
    public boolean isInsideSpectatorBounds(double x, double y, double z) {
        double dx = x - centerX();
        double dz = z - centerZ();
        double r = playableRadius() + 26.0;
        return (dx * dx + dz * dz) <= r * r
                && y >= minY() - 4
                && y <= maxY() + 14;
    }
}
