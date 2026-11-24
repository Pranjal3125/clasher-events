package network.clasher.events.hud;

import network.clasher.events.arena.ArenaManager;
import network.clasher.events.stats.EloCalculator;
import network.clasher.events.storage.DataStore;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Native Paper 26.2 3D Floating Hologram manager using TextDisplay entities — ZERO external hologram plugin required!
 * Populates live Lobby Top Elo / Top Wins / Welcome / Parkour holograms and 1st/2nd/3rd Winner Podium plaques.
 */
public final class HologramManager {
    private static final Color BG_DARK = Color.fromARGB(170, 14, 18, 30);

    private final JavaPlugin plugin;
    private final ArenaManager arenas;
    private final DataStore data;
    private final NamespacedKey holoKey;

    public HologramManager(JavaPlugin plugin, ArenaManager arenas, DataStore data) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.data = data;
        this.holoKey = new NamespacedKey(plugin, "hologram_id");
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("holograms.enabled", true);
    }

    /**
     * Deduplicates and refreshes all plugin-owned 3D floating holograms in the shared arena world.
     */
    public void refreshAll(String activeModeSummary) {
        World world = Bukkit.getWorld(arenas.sharedWorldName());
        if (world == null) return;
        removeAllInWorld(world);
        if (!isEnabled()) return;

        String ip = plugin.getConfig().getString("branding.server-ip", "play.clashernetwork.fun");
        int lx = ArenaManager.LOBBY_ORIGIN_X;
        int ly = ArenaManager.LOBBY_ORIGIN_Y;
        int lz = ArenaManager.LOBBY_ORIGIN_Z;

        // Only spawn Lobby holograms if Lobby island exists
        if (!world.getBlockAt(lx, ly + 1, lz).getType().isAir()) {
            String welcome = "<gold><bold>★ CLASHER EVENTS ★</bold></gold>\n"
                    + "<gray>Competitive PvP Tournaments</gray>\n\n"
                    + "<yellow>Status:</yellow> " + (activeModeSummary != null ? activeModeSummary : "<gray>Waiting for next event</gray>") + "\n"
                    + "<green>Use <bold>/event join</bold> when open!</green>\n"
                    + "<aqua>" + FormatUtil.escape(ip) + "</aqua>";
            spawnText(world, new Location(world, lx + 0.5, ly + 6.4, lz - 5.5), "lobby_welcome", welcome, 240);

            spawnText(world, new Location(world, lx - 9.5, ly + 5.6, lz - 3.5), "lobby_top_elo",
                    buildLeaderboardText("<gold><bold>🏆 TOP 5 ELO</bold></gold>", data.topElo(5), true, "Elo"), 220);

            spawnText(world, new Location(world, lx + 10.5, ly + 5.6, lz - 3.5), "lobby_top_wins",
                    buildLeaderboardText("<aqua><bold>⚔ TOP 5 WINS</bold></aqua>", data.topWins(5), false, "Wins"), 220);

            String pkStart = "<green><bold>🏃 SKY PARKOUR START</bold></green>\n"
                    + "<gray>Step on the gold pad to start timer!</gray>\n"
                    + "<yellow>Record:</yellow> <white>" + FormatUtil.escape(data.parkourServerRecordFormatted()) + "</white>";
            spawnText(world, new Location(world, lx + 22.5, ly + 4.8, lz - 18.0), "lobby_parkour_start", pkStart, 210);

            String pkEnd = "<gold><bold>🏆 SKY PARKOUR FINISH</bold></gold>\n"
                    + "<gray>Reach the golden crown to lock your time!</gray>";
            spawnText(world, new Location(world, lx + 10.5, ly + 23.0, lz - 36.5), "lobby_parkour_finish", pkEnd, 200);
        }

        // Only spawn Winner Podium holograms if Winner Podium exists
        int wx = ArenaManager.WINNER_ORIGIN_X;
        int wy = ArenaManager.WINNER_ORIGIN_Y;
        int wz = ArenaManager.WINNER_ORIGIN_Z;
        if (!world.getBlockAt(wx, wy + 4, wz - 6).getType().isAir()) {
            DataStore.PodiumRecord podium = data.lastPodium();
            String firstTxt = "<gold><bold>🥇 #1 CHAMPION</bold></gold>\n"
                    + "<yellow><bold>" + FormatUtil.escape(podium.first()) + "</bold></yellow>\n"
                    + "<gray>Mode:</gray> <white>" + FormatUtil.escape(podium.mode()) + "</white>\n"
                    + (podium.mvpKills() > 0
                    ? "<gray>MVP:</gray> <aqua>" + FormatUtil.escape(podium.mvp()) + " (" + podium.mvpKills() + " kills)</aqua>"
                    : "<gray>Clasher Network Champions</gray>");
            spawnText(world, new Location(world, wx + 0.5, wy + 8.3, wz - 5.5), "podium_first", firstTxt, 230);

            String secondTxt = "<white><bold>🥈 #2 RUNNER-UP</bold></white>\n"
                    + "<gray>" + FormatUtil.escape(podium.second()) + "</gray>";
            spawnText(world, new Location(world, wx - 7.5, wy + 6.4, wz - 5.5), "podium_second", secondTxt, 190);

            String thirdTxt = "<gold><bold>🥉 #3 THIRD PLACE</bold></gold>\n"
                    + "<gray>" + FormatUtil.escape(podium.third()) + "</gray>";
            spawnText(world, new Location(world, wx + 8.5, wy + 5.4, wz - 5.5), "podium_third", thirdTxt, 190);
        }
    }

    public void removeAll() {
        World world = Bukkit.getWorld(arenas.sharedWorldName());
        if (world != null) removeAllInWorld(world);
    }

    private void removeAllInWorld(World world) {
        for (Entity entity : new ArrayList<>(world.getEntitiesByClass(TextDisplay.class))) {
            if (entity.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) {
                entity.remove();
            }
        }
    }

    private void spawnText(World world, Location loc, String id, String miniText, int lineWidth) {
        loc.getChunk().load();
        world.spawn(loc, TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, id);
            td.text(FormatUtil.mm(miniText));
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(BG_DARK);
            td.setShadowed(true);
            td.setSeeThrough(false);
            td.setLineWidth(lineWidth);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setPersistent(true);
        });
    }

    private static String buildLeaderboardText(String header, List<DataStore.LeaderboardEntry> entries, boolean showRank, String unit) {
        StringBuilder sb = new StringBuilder(header).append("\n<dark_gray><st>----------------</st></dark_gray>");
        if (entries.isEmpty()) {
            sb.append("\n<gray>No matches played yet!</gray>");
            return sb.toString();
        }
        String[] medals = {"<gold>#1</gold>", "<white>#2</white>", "<gold>#3</gold>", "<gray>#4</gray>", "<gray>#5</gray>"};
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            DataStore.LeaderboardEntry e = entries.get(i);
            sb.append("\n").append(medals[i]).append(" <white>").append(FormatUtil.escape(e.name())).append("</white> ");
            if (showRank) {
                sb.append(EloCalculator.rankBadgeMini(e.value())).append(" ");
            }
            sb.append("<yellow>").append(e.value()).append(" ").append(unit).append("</yellow>");
        }
        return sb.toString();
    }
}
