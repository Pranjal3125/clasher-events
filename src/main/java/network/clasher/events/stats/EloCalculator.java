package network.clasher.events.stats;

import java.util.Locale;

/**
 * Pure, testable Elo rating, PvP Rank Tier, Bounty bonus, and K/D calculator for Clasher Events.
 */
public final class EloCalculator {
    public static final int DEFAULT_ELO = 1000;
    public static final int MIN_ELO = 100;
    public static final int K_FACTOR = 32;
    public static final int WIN_BONUS = 20;
    public static final int BOUNTY_BONUS = 10;

    public record EloDelta(int newWinnerElo, int newLoserElo, int gained, int lost) {}

    private EloCalculator() {}

    /**
     * Computes updated ratings after a PvP elimination.
     */
    public static EloDelta calculateKill(int killerElo, int victimElo) {
        int safeKiller = Math.max(MIN_ELO, killerElo);
        int safeVictim = Math.max(MIN_ELO, victimElo);
        double expectedKiller = 1.0 / (1.0 + Math.pow(10.0, (safeVictim - safeKiller) / 400.0));
        int delta = Math.max(4, (int) Math.round(K_FACTOR * (1.0 - expectedKiller)));
        int nextKiller = safeKiller + delta;
        int nextVictim = Math.max(MIN_ELO, safeVictim - delta);
        return new EloDelta(nextKiller, nextVictim, delta, safeVictim - nextVictim);
    }

    public static int applyWinBonus(int currentElo) {
        return Math.max(MIN_ELO, currentElo) + WIN_BONUS;
    }

    public static String rankName(int elo) {
        if (elo >= 1600) return "Clasher";
        if (elo >= 1400) return "Master";
        if (elo >= 1250) return "Diamond";
        if (elo >= 1100) return "Gold";
        if (elo >= 950) return "Silver";
        return "Bronze";
    }

    public static String rankBadgeMini(int elo) {
        if (elo >= 1600) return "<red><bold>[Clasher]</bold></red>";
        if (elo >= 1400) return "<light_purple><bold>[Master]</bold></light_purple>";
        if (elo >= 1250) return "<aqua>[Diamond]</aqua>";
        if (elo >= 1100) return "<yellow>[Gold]</yellow>";
        if (elo >= 950) return "<gray>[Silver]</gray>";
        return "<gold>[Bronze]</gold>";
    }

    public static String formatKd(int kills, int deaths) {
        double ratio = deaths <= 0 ? kills : (double) kills / deaths;
        return String.format(Locale.ROOT, "%.2f", ratio);
    }
}
