package network.clasher.events.hud;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import network.clasher.events.storage.DataStore;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages live Adventure BossBars, per-participant event sidebars, and crisp Phase/Countdown Titles.
 * Always restores each player's previous scoreboard cleanly when leaving or ending an event.
 */
public final class EventHudManager {
    private final JavaPlugin plugin;
    private final BossBar bossBar;
    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();

    public EventHudManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bossBar = BossBar.bossBar(
                FormatUtil.mm("<gold><bold>CLASHER EVENTS</bold></gold>"),
                1.0f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.NOTCHED_10
        );
    }

    public boolean isBossBarEnabled() {
        return plugin.getConfig().getBoolean("hud.bossbar", true);
    }

    public boolean isScoreboardEnabled() {
        return plugin.getConfig().getBoolean("hud.scoreboard", true);
    }

    public boolean isTitlesEnabled() {
        return plugin.getConfig().getBoolean("hud.titles", true);
    }

    public void updateBossBar(String miniText, float progress, BossBar.Color color, Set<UUID> viewers) {
        if (!isBossBarEnabled()) return;
        bossBar.name(FormatUtil.mm(miniText));
        bossBar.progress(Math.max(0.0f, Math.min(1.0f, progress)));
        bossBar.color(color);
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.showBossBar(bossBar);
        }
    }

    public void updateSidebar(
            Player player,
            String modeName,
            String arenaName,
            String phase,
            int aliveCount,
            int totalParticipants,
            int matchKills,
            DataStore.PlayerStats stats,
            Integer team,
            int countdownSec,
            boolean isOpen
    ) {
        if (!isScoreboardEnabled() || player == null || !player.isOnline()) return;
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;

        previousBoards.putIfAbsent(player.getUniqueId(), player.getScoreboard());
        Scoreboard board = mgr.getNewScoreboard();
        Objective obj = board.registerNewObjective(
                "ce_hud",
                Criteria.DUMMY,
                FormatUtil.mm("<gold><bold>CLASHER EVENTS</bold></gold>")
        );
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = new java.util.ArrayList<>();
        lines.add("§8§m------------------");
        lines.add("§7Mode: §e" + trim(modeName, 18));
        lines.add("§7Arena: §f" + trim(arenaName, 17));
        if (isOpen) {
            lines.add("§7Starts In: §a" + FormatUtil.formatDuration(countdownSec));
            lines.add("§7Joined: §e" + totalParticipants);
        } else {
            lines.add("§7Phase: §b" + phase.toUpperCase(Locale.ROOT));
            lines.add("§7Alive: §a" + aliveCount + "§8/§7" + totalParticipants);
            lines.add("§7Match Kills: §c" + matchKills);
            if (team != null) {
                lines.add("§7Team: " + (team == 1 ? "§c§lRED" : "§9§lBLUE"));
            }
        }
        lines.add("§1");
        lines.add("§7Your Elo: §6" + stats.elo());
        lines.add("§7Wins: §e" + stats.wins() + " §8| §7Streak: §a" + stats.winStreak());
        lines.add("§8§m------------------ ");

        int score = lines.size();
        for (String line : lines) {
            obj.getScore(line).setScore(score--);
        }
        player.setScoreboard(board);
    }

    public void showTitle(Player player, String titleMini, String subtitleMini, long fadeInMs, long stayMs, long fadeOutMs) {
        if (!isTitlesEnabled() || player == null || !player.isOnline()) return;
        Title title = Title.title(
                FormatUtil.mm(titleMini),
                FormatUtil.mm(subtitleMini),
                Title.Times.times(Duration.ofMillis(fadeInMs), Duration.ofMillis(stayMs), Duration.ofMillis(fadeOutMs))
        );
        player.showTitle(title);
    }

    public void broadcastTitle(Set<UUID> viewers, String titleMini, String subtitleMini, long fadeInMs, long stayMs, long fadeOutMs) {
        if (!isTitlesEnabled()) return;
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) showTitle(p, titleMini, subtitleMini, fadeInMs, stayMs, fadeOutMs);
        }
    }

    public void clearForPlayer(Player player) {
        if (player == null) return;
        player.hideBossBar(bossBar);
        Scoreboard prev = previousBoards.remove(player.getUniqueId());
        if (prev != null && player.isOnline()) {
            player.setScoreboard(prev);
        } else if (player.isOnline() && Bukkit.getScoreboardManager() != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    public void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(bossBar);
        }
        for (Map.Entry<UUID, Scoreboard> entry : new HashMap<>(previousBoards).entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null && p.isOnline()) {
                p.setScoreboard(entry.getValue());
            }
        }
        previousBoards.clear();
    }

    private static String trim(String raw, int max) {
        if (raw == null) return "";
        return raw.length() <= max ? raw : raw.substring(0, max - 1) + "…";
    }
}
