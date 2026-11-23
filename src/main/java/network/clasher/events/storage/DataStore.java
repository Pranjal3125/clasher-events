package network.clasher.events.storage;

import network.clasher.events.model.PlayerSnapshot;
import network.clasher.events.stats.EloCalculator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Thread-safe, async-persisted atomic YAML store for PvP stats, Elo ratings, win streaks,
 * audience points, crash-recovery snapshots, and match history.
 * Zero disk I/O runs on the Minecraft main server tick thread!
 */
public final class DataStore {
    public record LeaderboardEntry(String name, int value, int secondary) {
        public int points() {
            return value;
        }
    }

    public record PlayerStats(int wins, int kills, int deaths, int elo, int winStreak, int bestStreak) {
        public String kdFormatted() {
            return EloCalculator.formatKd(kills, deaths);
        }
    }

    public record MatchSummary(int id, String mode, String arena, String winner, String mvp, int mvpKills, long durationSeconds) {}

    private final Logger logger;
    private final File audiencePointsFile;
    private final File recoveryFile;
    private final File playerStatsFile;
    private final File matchHistoryFile;
    private final ExecutorService ioExecutor;

    private YamlConfiguration audiencePoints;
    private YamlConfiguration recoveryData;
    private YamlConfiguration playerStats;
    private YamlConfiguration matchHistory;

    public DataStore(File dataFolder, Logger logger) {
        this.logger = logger;
        if (!dataFolder.exists()) dataFolder.mkdirs();
        this.audiencePointsFile = new File(dataFolder, "audience-points.yml");
        this.recoveryFile = new File(dataFolder, "player-recovery.yml");
        this.playerStatsFile = new File(dataFolder, "player-stats.yml");
        this.matchHistoryFile = new File(dataFolder, "match-history.yml");
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "clasher-events-io");
            t.setDaemon(true);
            return t;
        });
        loadAll();
    }

    public synchronized void loadAll() {
        this.audiencePoints = YamlConfiguration.loadConfiguration(audiencePointsFile);
        this.recoveryData = YamlConfiguration.loadConfiguration(recoveryFile);
        this.playerStats = YamlConfiguration.loadConfiguration(playerStatsFile);
        this.matchHistory = YamlConfiguration.loadConfiguration(matchHistoryFile);
    }

    /**
     * Waits for all queued async writes to complete without shutting down the executor.
     */
    public void flush() {
        try {
            ioExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best-effort flush.
        }
    }

    public void flushAndShutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(8, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    private void enqueueSave(YamlConfiguration yaml, File target, String errMsg) {
        String serialized = yaml.saveToString();
        if (ioExecutor.isShutdown()) {
            writeRawAtomically(serialized, target, errMsg);
            return;
        }
        ioExecutor.execute(() -> writeRawAtomically(serialized, target, errMsg));
    }

    private void writeRawAtomically(String serialized, File target, String errMsg) {
        YamlConfiguration copy = new YamlConfiguration();
        try {
            copy.loadFromString(serialized);
            YamlAtomicStorage.saveAtomically(copy, target, logger, errMsg);
        } catch (Exception ex) {
            logger.severe(errMsg + ": " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Audience points
    // -------------------------------------------------------------------------

    public synchronized int audiencePoints(UUID playerId) {
        return audiencePoints.getInt("players." + playerId + ".points", 0);
    }

    public synchronized void addAudiencePoints(UUID playerId, String playerName, int delta) {
        if (delta <= 0) return;
        String base = "players." + playerId;
        audiencePoints.set(base + ".points", audiencePoints.getInt(base + ".points", 0) + delta);
        audiencePoints.set(base + ".name", playerName == null ? playerId.toString() : playerName);
        enqueueSave(audiencePoints, audiencePointsFile, "Could not safely save audience-points.yml");
    }

    public synchronized List<LeaderboardEntry> topAudiencePoints(int limit) {
        ConfigurationSection players = audiencePoints.getConfigurationSection("players");
        if (players == null) return List.of();
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (String key : players.getKeys(false)) {
            int pts = players.getInt(key + ".points", 0);
            String name = players.getString(key + ".name", key);
            entries.add(new LeaderboardEntry(name, pts, 0));
        }
        entries.sort(Comparator.comparingInt(LeaderboardEntry::value).reversed()
                .thenComparing(LeaderboardEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    // -------------------------------------------------------------------------
    // Crash-recovery snapshots
    // -------------------------------------------------------------------------

    public synchronized void persistSnapshot(UUID playerId, PlayerSnapshot snapshot) {
        ConfigurationSection section = recoveryData.createSection("players." + playerId);
        snapshot.writeTo(section);
        enqueueSave(recoveryData, recoveryFile, "Could not safely save player-recovery.yml");
    }

    public synchronized void removePersistedSnapshot(UUID playerId) {
        if (!recoveryData.contains("players." + playerId)) return;
        recoveryData.set("players." + playerId, null);
        enqueueSave(recoveryData, recoveryFile, "Could not safely update player-recovery.yml");
    }

    public synchronized PlayerSnapshot loadPersistedSnapshot(UUID playerId) {
        ConfigurationSection section = recoveryData.getConfigurationSection("players." + playerId);
        return section == null ? null : PlayerSnapshot.from(section);
    }

    // -------------------------------------------------------------------------
    // Player PvP stats, Elo ratings & Win Streaks
    // -------------------------------------------------------------------------

    public synchronized PlayerStats stats(UUID playerId) {
        String base = "players." + playerId;
        return new PlayerStats(
                playerStats.getInt(base + ".wins", 0),
                playerStats.getInt(base + ".kills", 0),
                playerStats.getInt(base + ".deaths", 0),
                playerStats.getInt(base + ".elo", EloCalculator.DEFAULT_ELO),
                playerStats.getInt(base + ".win-streak", 0),
                playerStats.getInt(base + ".best-streak", 0)
        );
    }

    public synchronized void recordWin(UUID winnerId, String winnerName) {
        String base = "players." + winnerId;
        int newWins = playerStats.getInt(base + ".wins", 0) + 1;
        int newStreak = playerStats.getInt(base + ".win-streak", 0) + 1;
        int bestStreak = Math.max(playerStats.getInt(base + ".best-streak", 0), newStreak);
        int curElo = playerStats.getInt(base + ".elo", EloCalculator.DEFAULT_ELO);
        playerStats.set(base + ".name", winnerName);
        playerStats.set(base + ".wins", newWins);
        playerStats.set(base + ".win-streak", newStreak);
        playerStats.set(base + ".best-streak", bestStreak);
        playerStats.set(base + ".elo", EloCalculator.applyWinBonus(curElo));
        enqueueSave(playerStats, playerStatsFile, "Could not safely save player-stats.yml");
    }

    public synchronized EloCalculator.EloDelta recordKill(UUID killerId, String killerName, UUID victimId, String victimName) {
        String k = "players." + killerId;
        String v = "players." + victimId;
        int killerElo = playerStats.getInt(k + ".elo", EloCalculator.DEFAULT_ELO);
        int victimElo = playerStats.getInt(v + ".elo", EloCalculator.DEFAULT_ELO);
        EloCalculator.EloDelta delta = EloCalculator.calculateKill(killerElo, victimElo);

        playerStats.set(k + ".name", killerName);
        playerStats.set(k + ".kills", playerStats.getInt(k + ".kills", 0) + 1);
        playerStats.set(k + ".elo", delta.newWinnerElo());
        playerStats.set(k + ".last-kill", victimName);

        playerStats.set(v + ".name", victimName);
        playerStats.set(v + ".deaths", playerStats.getInt(v + ".deaths", 0) + 1);
        playerStats.set(v + ".elo", delta.newLoserElo());
        playerStats.set(v + ".win-streak", 0);

        enqueueSave(playerStats, playerStatsFile, "Could not safely save player-stats.yml");
        return delta;
    }

    public synchronized void recordDeath(UUID victimId, String victimName) {
        String base = "players." + victimId;
        playerStats.set(base + ".name", victimName);
        playerStats.set(base + ".deaths", playerStats.getInt(base + ".deaths", 0) + 1);
        playerStats.set(base + ".win-streak", 0);
        enqueueSave(playerStats, playerStatsFile, "Could not safely save player-stats.yml");
    }

    public synchronized List<LeaderboardEntry> topWins(int limit) {
        return queryStatsLeaderboard("wins", "elo", EloCalculator.DEFAULT_ELO, limit);
    }

    public synchronized List<LeaderboardEntry> topKills(int limit) {
        return queryStatsLeaderboard("kills", "deaths", 0, limit);
    }

    public synchronized List<LeaderboardEntry> topElo(int limit) {
        return queryStatsLeaderboard("elo", "wins", 0, limit);
    }

    private List<LeaderboardEntry> queryStatsLeaderboard(String primaryKey, String secondaryKey, int secondaryDefault, int limit) {
        ConfigurationSection players = playerStats.getConfigurationSection("players");
        if (players == null) return List.of();
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (String key : players.getKeys(false)) {
            int val = players.getInt(key + "." + primaryKey, primaryKey.equals("elo") ? EloCalculator.DEFAULT_ELO : 0);
            int sec = players.getInt(key + "." + secondaryKey, secondaryDefault);
            String name = players.getString(key + ".name", key);
            entries.add(new LeaderboardEntry(name, val, sec));
        }
        entries.sort(Comparator.comparingInt(LeaderboardEntry::value).reversed()
                .thenComparing(Comparator.comparingInt(LeaderboardEntry::secondary).reversed())
                .thenComparing(LeaderboardEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    // -------------------------------------------------------------------------
    // Match history
    // -------------------------------------------------------------------------

    public synchronized void recordMatch(
            String arena,
            String mode,
            String winnerName,
            String winnerId,
            long startedAt,
            Set<UUID> participants,
            String mvpName,
            int mvpKills
    ) {
        int id = matchHistory.getInt("next-id", 1);
        String path = "matches." + id;
        long now = System.currentTimeMillis();
        matchHistory.set(path + ".arena", arena);
        matchHistory.set(path + ".mode", mode);
        matchHistory.set(path + ".winner", winnerName);
        matchHistory.set(path + ".winner-uuid", winnerId);
        matchHistory.set(path + ".started-at", startedAt);
        matchHistory.set(path + ".finished-at", now);
        matchHistory.set(path + ".duration-seconds", Math.max(0L, (now - startedAt) / 1000L));
        matchHistory.set(path + ".players", participants.stream().map(UUID::toString).toList());
        matchHistory.set(path + ".mvp", mvpName);
        matchHistory.set(path + ".mvp-kills", mvpKills);
        matchHistory.set("next-id", id + 1);
        enqueueSave(matchHistory, matchHistoryFile, "Could not safely save match-history.yml");
    }

    public synchronized List<MatchSummary> recentMatches(int limit) {
        ConfigurationSection root = matchHistory.getConfigurationSection("matches");
        if (root == null) return List.of();
        List<MatchSummary> out = new ArrayList<>();
        for (String key : root.getKeys(false)) {
            int id;
            try {
                id = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                continue;
            }
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            out.add(new MatchSummary(
                    id,
                    s.getString("mode", "unknown"),
                    s.getString("arena", "unknown"),
                    s.getString("winner", "Unknown"),
                    s.getString("mvp", "Unknown"),
                    s.getInt("mvp-kills", 0),
                    s.getLong("duration-seconds", 0L)
            ));
        }
        out.sort(Comparator.comparingInt(MatchSummary::id).reversed());
        return out.subList(0, Math.min(limit, out.size()));
    }
}
