package network.clasher.events.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import network.clasher.events.match.EventManager;
import network.clasher.events.storage.DataStore;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Optional PlaceholderAPI expansion (%clasherevents_*%).
 * Registered only when PlaceholderAPI is installed on the server.
 */
public final class ClasherEventsExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final EventManager events;
    private final DataStore data;

    public ClasherEventsExpansion(JavaPlugin plugin, EventManager events, DataStore data) {
        this.plugin = plugin;
        this.events = events;
        this.data = data;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "clasherevents";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Clasher Network";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        // Global live event placeholders
        switch (key) {
            case "active_mode" -> { return (events.isEventOpen() || events.isEventRunning()) ? events.eventMode().displayName() : "None"; }
            case "active_arena" -> { return (events.isEventOpen() || events.isEventRunning()) ? events.selectedArena() : "None"; }
            case "phase" -> { return events.phase(); }
            case "alive" -> { return String.valueOf(events.alive().size()); }
            case "participants" -> { return String.valueOf(events.participants().size()); }
            case "countdown" -> { return String.valueOf(events.countdownRemaining()); }
            default -> {}
        }

        // Leaderboard placeholders: top_wins_1 .. top_wins_10, top_elo_1, top_kills_1
        if (key.startsWith("top_wins_")) return topEntry(data.topWins(10), key.substring(9), " wins");
        if (key.startsWith("top_kills_")) return topEntry(data.topKills(10), key.substring(10), " kills");
        if (key.startsWith("top_elo_")) return topEntry(data.topElo(10), key.substring(8), " Elo");
        if (key.startsWith("top_points_")) return topEntry(data.topAudiencePoints(10), key.substring(11), " pts");

        if (player == null) return "0";
        DataStore.PlayerStats s = data.stats(player.getUniqueId());
        return switch (key) {
            case "wins" -> String.valueOf(s.wins());
            case "kills" -> String.valueOf(s.kills());
            case "deaths" -> String.valueOf(s.deaths());
            case "kd" -> s.kdFormatted();
            case "elo" -> String.valueOf(s.elo());
            case "streak" -> String.valueOf(s.winStreak());
            case "best_streak" -> String.valueOf(s.bestStreak());
            case "points" -> String.valueOf(data.audiencePoints(player.getUniqueId()));
            default -> null;
        };
    }

    private static String topEntry(List<DataStore.LeaderboardEntry> list, String rankRaw, String suffix) {
        try {
            int idx = Integer.parseInt(rankRaw) - 1;
            if (idx < 0 || idx >= list.size()) return "---";
            DataStore.LeaderboardEntry e = list.get(idx);
            return e.name() + " (" + e.value() + suffix + ")";
        } catch (NumberFormatException ex) {
            return "---";
        }
    }
}
