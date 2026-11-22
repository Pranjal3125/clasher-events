package network.clasher.events.storage;

import network.clasher.events.model.PlayerSnapshot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Centralized atomic YAML persistence for player stats, audience points,
 * crash-recovery snapshots, and match history.
 */
public final class DataStore {
    public record LeaderboardEntry(String name, int points) {}
    public record PlayerStats(int wins, int kills, int deaths) {}
    public record MatchSummary(int id, String mode, String arena, String winner, String mvp, int mvpKills, long durationSeconds) {}

    private final Logger logger;
    private final File audiencePointsFile;
    private final File recoveryFile;
    private final File playerStatsFile;
    private final File matchHistoryFile;

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
        loadAll();
    }

    public void loadAll() {
        this.audiencePoints = YamlConfiguration.loadConfiguration(audiencePointsFile);
        this.recoveryData = YamlConfiguration.loadConfiguration(recoveryFile);
        this.playerStats = YamlConfiguration.loadConfiguration(playerStatsFile);
        this.matchHistory = YamlConfiguration.loadConfiguration(matchHistoryFile);
    }

    // -------------------------------------------------------------------------
    // Audience points
    // -------------------------------------------------------------------------

    public int audiencePoints(UUID playerId) {
        return audiencePoints.getInt("players." + playerId + ".points", 0);
    }

    public void addAudiencePoints(UUID playerId, String playerName, int delta) {
        if (delta <= 0) return;
        String base = "players." + playerId;
        audiencePoints.set(base + ".points", audiencePoints.getInt(base + ".points", 0) + delta);
        audiencePoints.set(base + ".name", playerName == null ? playerId.toString() : playerName);
        saveAudiencePoints();
    }

    public List<LeaderboardEntry> topAudiencePoints(int limit) {
        ConfigurationSection players = audiencePoints.getConfigurationSection("players");
        if (players == null) return List.of();
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (String key : players.getKeys(false)) {
            int pts = players.getInt(key + ".points", 0);
            String name = players.getString(key + ".name", key);
            entries.add(new LeaderboardEntry(name, pts));
        }
        entries.sort(Comparator.comparingInt(LeaderboardEntry::points).reversed()
                .thenComparing(LeaderboardEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    private void saveAudiencePoints() {
        YamlAtomicStorage.saveAtomically(audiencePoints, audiencePointsFile, logger, "Could not safely save audience-points.yml");
    }

    // -------------------------------------------------------------------------
    // Crash-recovery snapshots
    // -------------------------------------------------------------------------

    public void persistSnapshot(UUID playerId, PlayerSnapshot snapshot) {
        ConfigurationSection section = recoveryData.createSection("players." + playerId);
        snapshot.writeTo(section);
        YamlAtomicStorage.saveAtomically(recoveryData, recoveryFile, logger, "Could not safely save player-recovery.yml");
    }

    public void removePersistedSnapshot(UUID playerId) {
        if (!recoveryData.contains("players." + playerId)) return;
        recoveryData.set("players." + playerId, null);
        YamlAtomicStorage.saveAtomically(recoveryData, recoveryFile, logger, "Could not safely update player-recovery.yml");
    }

    public PlayerSnapshot loadPersistedSnapshot(UUID playerId) {
        ConfigurationSection section = recoveryData.getConfigurationSection("players." + playerId);
        return section == null ? null : PlayerSnapshot.from(section);
    }

    // -------------------------------------------------------------------------
    // Player PvP stats
    // -------------------------------------------------------------------------

    public PlayerStats stats(UUID playerId) {
        String base = "players." + playerId;
        return new PlayerStats(
                playerStats.getInt(base + ".wins", 0),
                playerStats.getInt(base + ".kills", 0),
                playerStats.getInt(base + ".deaths", 0)
        );
    }

    public void recordWin(UUID winnerId, String winnerName) {
        String base = "players." + winnerId;
        playerStats.set(base + ".name", winnerName);
        playerStats.set(base + ".wins", playerStats.getInt(base + ".wins", 0) + 1);
        savePlayerStats();
    }

    public void recordKill(UUID killerId, String killerName, UUID victimId, String victimName) {
        String k = "players." + killerId;
        playerStats.set(k + ".name", killerName);
        playerStats.set(k + ".kills", playerStats.getInt(k + ".kills", 0) + 1);
        playerStats.set(k + ".last-kill", victimName);

        String v = "players." + victimId;
        playerStats.set(v + ".name", victimName);
        playerStats.set(v + ".deaths", playerStats.getInt(v + ".deaths", 0) + 1);
        savePlayerStats();
    }

    public void recordDeath(UUID victimId, String victimName) {
        String base = "players." + victimId;
        playerStats.set(base + ".name", victimName);
        playerStats.set(base + ".deaths", playerStats.getInt(base + ".deaths", 0) + 1);
        savePlayerStats();
    }

    private void savePlayerStats() {
        YamlAtomicStorage.saveAtomically(playerStats, playerStatsFile, logger, "Could not safely save player-stats.yml");
    }

    // -------------------------------------------------------------------------
    // Match history
    // -------------------------------------------------------------------------

    public void recordMatch(
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
        YamlAtomicStorage.saveAtomically(matchHistory, matchHistoryFile, logger, "Could not safely save match-history.yml");
    }

    public List<MatchSummary> recentMatches(int limit) {
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
