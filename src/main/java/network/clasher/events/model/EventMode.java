package network.clasher.events.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Supported Clasher Events modes and their default arena mappings and GUI icons.
 */
public enum EventMode {
    NETHPOT("nethpot", "NethPot", "nethpot", Material.NETHERITE_SWORD),
    AXE_SHIELD("axe-shield", "Axe & Shield", "axe-shield", Material.IRON_AXE),
    BUILD_UHC("build-uhc", "Build UHC", "build-uhc", Material.GOLDEN_APPLE),
    SWORD("sword", "Sword", "sword", Material.DIAMOND_SWORD),
    UHC("uhc", "UHC", "uhc", Material.ENCHANTED_GOLDEN_APPLE),
    CRYSTAL("crystal", "Crystal PvP", "crystal", Material.END_CRYSTAL),
    SMP("smp", "SMP", "smp", Material.SHIELD),
    DIAMOND_POT("diamond-pot", "Diamond Pot", "diamond-pot", Material.DIAMOND_CHESTPLATE),
    TEAM_WARS("team-wars", "Team Wars", "smp", Material.RED_BANNER),
    DUEL("duel", "1v1 Duel", "sword", Material.IRON_SWORD),
    RANDOM_KIT("random-kit", "Random Kit", null, Material.CHEST),
    TOURNAMENT("tournament", "Tournament", "sword", Material.NETHER_STAR);

    private final String id;
    private final String displayName;
    private final String defaultArenaId;
    private final Material icon;

    EventMode(String id, String displayName, String defaultArenaId, Material icon) {
        this.id = id;
        this.displayName = displayName;
        this.defaultArenaId = defaultArenaId;
        this.icon = icon;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String defaultArenaId() { return defaultArenaId; }
    public Material icon() { return icon; }

    public static Optional<EventMode> fromInput(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "nethpot" -> Optional.of(NETHPOT);
            case "axe", "axe-shield" -> Optional.of(AXE_SHIELD);
            case "build-uhc" -> Optional.of(BUILD_UHC);
            case "sword" -> Optional.of(SWORD);
            case "uhc" -> Optional.of(UHC);
            case "crystal" -> Optional.of(CRYSTAL);
            case "smp" -> Optional.of(SMP);
            case "diamond-pot", "dia-pot" -> Optional.of(DIAMOND_POT);
            case "team-wars", "teams" -> Optional.of(TEAM_WARS);
            case "duel", "1v1" -> Optional.of(DUEL);
            case "random-kit", "random" -> Optional.of(RANDOM_KIT);
            case "tournament", "tourney" -> Optional.of(TOURNAMENT);
            default -> Optional.empty();
        };
    }

    public static List<String> allIds() {
        return java.util.Arrays.stream(values()).map(EventMode::id).toList();
    }

    public static List<EventMode> kitModes() {
        return List.of(NETHPOT, CRYSTAL, SWORD, AXE_SHIELD, BUILD_UHC, UHC, SMP, DIAMOND_POT);
    }

    public boolean supportsKitSelection() {
        return this == DUEL || this == TEAM_WARS || this == TOURNAMENT;
    }

    /**
     * Calculates the Wave 2 survivor threshold, or -1 if Wave 2 should be skipped.
     */
    public static int calculateWave2Threshold(int initialFighters) {
        if (initialFighters < 8) return -1;
        return Math.max(3, initialFighters / 3);
    }

    /**
     * Computes dynamic WorldBorder diameter scaled cleanly to the number of alive fighters.
     */
    public static double calculateDynamicBorderSize(int aliveCount, double maxArenaDiameter) {
        double raw;
        if (aliveCount <= 2) raw = 26.0;
        else if (aliveCount <= 4) raw = 38.0;
        else if (aliveCount <= 8) raw = 50.0;
        else if (aliveCount <= 14) raw = 64.0;
        else if (aliveCount <= 22) raw = 78.0;
        else raw = maxArenaDiameter;
        return Math.max(16.0, Math.min(maxArenaDiameter, raw));
    }
}
