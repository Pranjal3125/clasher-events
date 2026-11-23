package network.clasher.events.stats;

import java.util.Locale;

/**
 * Pure, testable Elo rating and PvP K/D calculator for Clasher Events leaderboards.
 */
public final class EloCalculator {
    public static final int DEFAULT_ELO = 1000;
    public static final int MIN_ELO = 100;
    public static final int K_FACTOR = 32;
    public static final int WIN_BONUS = 20;

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

    public static String formatKd(int kills, int deaths) {
        double ratio = deaths <= 0 ? kills : (double) kills / deaths;
        return String.format(Locale.ROOT, "%.2f", ratio);
    }
}
