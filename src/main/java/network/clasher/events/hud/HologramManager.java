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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native Paper 26.2 3D Floating Hologram manager using non-persistent TextDisplay entities — ZERO external plugin required!
 * Populates live Lobby Top Elo / Top Wins / Welcome / Parkour holograms, Winner Podium plaques, and Tournament Hall boards.
 *
 * Anti-Lag / Anti-Duplication Guarantees (v2.7.1):
 *  - All holograms use setPersistent(false) so chunk unloads / restarts can NEVER accumulate stacked TextDisplays.
 *  - Startup/full purge loads target hub chunk entity slices (chunk.getEntities()) BEFORE removing old persistent entities.
 *  - Live refreshes update existing TextDisplays in-place (skipping unchanged text) and NEVER spawn duplicates on unloaded chunks.
 *  - EntitiesLoadEvent immediately destroys any stale persistent holograms left over from v2.6/v2.7.0.
 */
public final class HologramManager implements Listener {
    private static final Color BG_DARK = Color.fromARGB(170, 14, 18, 30);

    private final JavaPlugin plugin;
    private final ArenaManager arenas;
    private final DataStore data;
    private final NamespacedKey holoKey;
    private final Map<String, UUID> activeHolograms = new HashMap<>();
    private final Map<String, String> lastHologramText = new HashMap<>();
    private String lastModeSummary;
    private String lastTourneyBanner;
    private String lastTourneySquads;
    private String lastTourneyBracket;
    private boolean legacyPurgedOnce;
    private boolean pendingReloadRefresh;

    public HologramManager(JavaPlugin plugin, ArenaManager arenas, DataStore data) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.data = data;
        this.holoKey = new NamespacedKey(plugin, "hologram_id");
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("holograms.enabled", true);
    }

    public void refreshAll(String activeModeSummary) {
        refreshAll(activeModeSummary, null, null, null);
    }

    /**
     * Deduplicates and refreshes all plugin-owned 3D floating holograms across Lobby, Winner Podium, and Tournament Hall.
     * On first call (or after reload), explicitly loads hub entity slices to purge any legacy persistent duplicates.
     */
    public void refreshAll(String activeModeSummary, String tourneyBannerMini, String tourneySquadsMini, String tourneyBracketMini) {
        this.lastModeSummary = activeModeSummary;
        this.lastTourneyBanner = tourneyBannerMini;
        this.lastTourneySquads = tourneySquadsMini;
        this.lastTourneyBracket = tourneyBracketMini;

        World world = Bukkit.getWorld(arenas.sharedWorldName());
        if (world == null) return;

        if (!legacyPurgedOnce) {
            legacyPurgedOnce = true;
            int purged = purgeAllInHubChunks(world);
            if (purged > 11) {
                plugin.getLogger().info("Cleaned up " + purged + " legacy/duplicate Hologram entities.");
            }
        }
        if (!isEnabled()) {
            purgeAllInHubChunks(world);
            return;
        }

        String ip = plugin.getConfig().getString("branding.server-ip", "play.clashernetwork.fun");
        int lx = ArenaManager.LOBBY_ORIGIN_X;
        int ly = ArenaManager.LOBBY_ORIGIN_Y;
        int lz = ArenaManager.LOBBY_ORIGIN_Z;

        // Only update/spawn Lobby holograms when Lobby origin chunk is loaded (or right after first purge)
        if (world.isChunkLoaded(lx >> 4, lz >> 4) && !world.getBlockAt(lx, ly + 1, lz).getType().isAir()) {
            String welcome = "<gold><bold>★ CLASHER EVENTS ★</bold></gold>\n"
                    + "<gray>Competitive PvP Tournaments</gray>\n\n"
                    + "<yellow>Status:</yellow> " + (activeModeSummary != null ? activeModeSummary : "<gray>Waiting for next event</gray>") + "\n"
                    + "<green>Use <bold>/event join</bold> when open!</green>\n"
                    + "<aqua>" + FormatUtil.escape(ip) + "</aqua>";
            upsertText(world, new Location(world, lx + 0.5, ly + 6.4, lz - 5.5), "lobby_welcome", welcome, 240);

            upsertText(world, new Location(world, lx - 9.5, ly + 5.6, lz - 3.5), "lobby_top_elo",
                    buildLeaderboardText("<gold><bold>🏆 TOP 5 ELO</bold></gold>", data.topElo(5), true, "Elo"), 220);

            upsertText(world, new Location(world, lx + 10.5, ly + 5.6, lz - 3.5), "lobby_top_wins",
                    buildLeaderboardText("<aqua><bold>⚔ TOP 5 WINS</bold></aqua>", data.topWins(5), false, "Wins"), 220);

            String pkStart = "<green><bold>🏃 SKY PARKOUR START</bold></green>\n"
                    + "<gray>Step on the gold pad to start timer!</gray>\n"
                    + "<yellow>Record:</yellow> <white>" + FormatUtil.escape(data.parkourServerRecordFormatted()) + "</white>";
            upsertText(world, new Location(world, lx + 22.5, ly + 4.8, lz - 18.0), "lobby_parkour_start", pkStart, 210);

            String pkEnd = "<gold><bold>🏆 SKY PARKOUR FINISH</bold></gold>\n"
                    + "<gray>Reach the golden crown to lock your time!</gray>";
            upsertText(world, new Location(world, lx + 10.5, ly + 23.0, lz - 36.5), "lobby_parkour_finish", pkEnd, 200);
        }

        // Winner Podium holograms
        int wx = ArenaManager.WINNER_ORIGIN_X;
        int wy = ArenaManager.WINNER_ORIGIN_Y;
        int wz = ArenaManager.WINNER_ORIGIN_Z;
        if (world.isChunkLoaded(wx >> 4, wz >> 4) && !world.getBlockAt(wx, wy + 4, wz - 6).getType().isAir()) {
            DataStore.PodiumRecord podium = data.lastPodium();
            String firstTxt = "<gold><bold>🥇 #1 CHAMPION</bold></gold>\n"
                    + "<yellow><bold>" + FormatUtil.escape(podium.first()) + "</bold></yellow>\n"
                    + "<gray>Mode:</gray> <white>" + FormatUtil.escape(podium.mode()) + "</white>\n"
                    + (podium.mvpKills() > 0
                    ? "<gray>MVP:</gray> <aqua>" + FormatUtil.escape(podium.mvp()) + " (" + podium.mvpKills() + " kills)</aqua>"
                    : "<gray>Clasher Network Champions</gray>");
            upsertText(world, new Location(world, wx + 0.5, wy + 8.3, wz - 5.5), "podium_first", firstTxt, 230);

            String secondTxt = "<white><bold>🥈 #2 RUNNER-UP</bold></white>\n"
                    + "<gray>" + FormatUtil.escape(podium.second()) + "</gray>";
            upsertText(world, new Location(world, wx - 7.5, wy + 6.4, wz - 5.5), "podium_second", secondTxt, 190);

            String thirdTxt = "<gold><bold>🥉 #3 THIRD PLACE</bold></gold>\n"
                    + "<gray>" + FormatUtil.escape(podium.third()) + "</gray>";
            upsertText(world, new Location(world, wx + 8.5, wy + 5.4, wz - 5.5), "podium_third", thirdTxt, 190);
        }

        // Grand Esports Tournament Hall holograms
        int tx = ArenaManager.TOURNAMENT_LOBBY_ORIGIN_X;
        int ty = ArenaManager.TOURNAMENT_LOBBY_ORIGIN_Y;
        int tz = ArenaManager.TOURNAMENT_LOBBY_ORIGIN_Z;
        if (world.isChunkLoaded(tx >> 4, tz >> 4) && !world.getBlockAt(tx, ty + 1, tz).getType().isAir()) {
            String banner = tourneyBannerMini != null ? tourneyBannerMini
                    : "<gold><bold>⚔ GRAND TOURNAMENT HALL ⚔</bold></gold>\n"
                    + "<gray>Official 1v1 • 2v2 • 3v3 • 4v4 Esports Arena</gray>\n\n"
                    + "<yellow>Status:</yellow> <gray>Awaiting next tournament</gray>\n"
                    + "<aqua>" + FormatUtil.escape(ip) + "</aqua>";
            upsertText(world, new Location(world, tx + 0.5, ty + 7.2, tz - 6.5), "tourney_banner", banner, 260);

            String squads = tourneySquadsMini != null ? tourneySquadsMini
                    : "<aqua><bold>👥 REGISTERED SQUADS</bold></aqua>\n"
                    + "<dark_gray><st>------------------</st></dark_gray>\n"
                    + "<gray>No tournament squads registered yet!</gray>\n"
                    + "<yellow>Join with <green>/event join</green></yellow>";
            upsertText(world, new Location(world, tx - 10.5, ty + 5.6, tz - 4.5), "tourney_squads", squads, 250);

            String bracket = tourneyBracketMini != null ? tourneyBracketMini
                    : "<gold><bold>🏆 SQUAD & BRACKET GUIDE</bold></gold>\n"
                    + "<dark_gray><st>------------------</st></dark_gray>\n"
                    + "<yellow>/event squad invite <player></yellow> <gray>— Invite mate</gray>\n"
                    + "<yellow>/event squad name <name></yellow> <gray>— Rename squad</gray>\n"
                    + "<yellow>/event bracket</yellow> <gray>— Live Teams & Bracket</gray>\n"
                    + "<gray>Right-click your hotbar items!</gray>";
            upsertText(world, new Location(world, tx + 11.5, ty + 5.6, tz - 4.5), "tourney_bracket", bracket, 250);
        }
    }

    /**
     * Marks that the next refreshAll() should force-load all 3 hub entity slices and purge any stale entities.
     */
    public void markNeedsPurge() {
        legacyPurgedOnce = false;
    }

    /**
     * Forces a full legacy entity sweep + fresh spawn across all 3 hubs (used by /ce reload & /ce setup).
     */
    public void forceFullReset(String activeModeSummary, String tourneyBannerMini, String tourneySquadsMini, String tourneyBracketMini) {
        legacyPurgedOnce = false;
        refreshAll(activeModeSummary, tourneyBannerMini, tourneySquadsMini, tourneyBracketMini);
    }

    public void removeAll() {
        World world = Bukkit.getWorld(arenas.sharedWorldName());
        if (world != null) {
            purgeAllInHubChunks(world);
        }
    }

    private int purgeAllInHubChunks(World world) {
        int removed = 0;
        for (int[] c : allHubHologramChunks()) {
            for (Entity entity : world.getChunkAt(c[0], c[1]).getEntities()) {
                if (entity instanceof TextDisplay && entity.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        for (Entity entity : new ArrayList<>(world.getEntitiesByClass(TextDisplay.class))) {
            if (entity.isValid() && entity.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) {
                entity.remove();
                removed++;
            }
        }
        activeHolograms.clear();
        lastHologramText.clear();
        return removed;
    }

    private void upsertText(World world, Location loc, String id, String miniText, int lineWidth) {
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) {
            return;
        }

        // Update existing live TextDisplay in-place without despawning/respawning!
        UUID existingId = activeHolograms.get(id);
        if (existingId != null) {
            Entity existing = Bukkit.getEntity(existingId);
            if (existing instanceof TextDisplay td && td.isValid() && td.getWorld().equals(world)) {
                if (!miniText.equals(lastHologramText.get(id))) {
                    td.text(FormatUtil.mm(miniText));
                    lastHologramText.put(id, miniText);
                }
                return;
            }
        }

        // Purge any stray/duplicate TextDisplay carrying this hologram ID in the target chunk BEFORE spawning!
        for (Entity e : world.getChunkAt(cx, cz).getEntities()) {
            if (e instanceof TextDisplay) {
                String existingKey = e.getPersistentDataContainer().get(holoKey, PersistentDataType.STRING);
                if (id.equals(existingKey)) {
                    e.remove();
                }
            }
        }

        TextDisplay spawned = world.spawn(loc, TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, id);
            td.text(FormatUtil.mm(miniText));
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(BG_DARK);
            td.setShadowed(true);
            td.setSeeThrough(false);
            td.setLineWidth(lineWidth);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setPersistent(false);
        });
        activeHolograms.put(id, spawned.getUniqueId());
        lastHologramText.put(id, miniText);
    }

    private List<int[]> allHubHologramChunks() {
        List<int[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int lx = ArenaManager.LOBBY_ORIGIN_X, lz = ArenaManager.LOBBY_ORIGIN_Z;
        int wx = ArenaManager.WINNER_ORIGIN_X, wz = ArenaManager.WINNER_ORIGIN_Z;
        int tx = ArenaManager.TOURNAMENT_LOBBY_ORIGIN_X, tz = ArenaManager.TOURNAMENT_LOBBY_ORIGIN_Z;

        int[][] points = {
                {lx, lz}, {lx, lz - 6}, {lx - 10, lz - 4}, {lx + 11, lz - 4}, {lx + 22, lz - 18}, {lx + 11, lz - 37},
                {wx, wz}, {wx, wz - 6}, {wx - 8, wz - 6}, {wx + 9, wz - 6},
                {tx, tz}, {tx, tz - 7}, {tx - 11, tz - 5}, {tx + 12, tz - 5}
        };
        for (int[] p : points) {
            int cx = p[0] >> 4;
            int cz = p[1] >> 4;
            long key = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
            if (seen.add(key)) {
                out.add(new int[]{cx, cz});
            }
        }
        return out;
    }

    private boolean isHubChunk(int cx, int cz) {
        for (int[] c : allHubHologramChunks()) {
            if (c[0] == cx && c[1] == cz) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World world = event.getWorld();
        if (!world.getName().equalsIgnoreCase(arenas.sharedWorldName())) return;

        Set<UUID> validIds = new HashSet<>(activeHolograms.values());
        int purged = 0;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof TextDisplay
                    && entity.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)
                    && !validIds.contains(entity.getUniqueId())) {
                entity.remove();
                purged++;
            }
        }
        if (purged > 0) {
            plugin.getLogger().info("Purged " + purged + " stale persistent Hologram entities on chunk load.");
        }

        // If a player enters Lobby / Podium / Tournament Hall after its chunk unloaded, cleanly spawn live non-persistent holograms!
        if (isEnabled() && isHubChunk(event.getChunk().getX(), event.getChunk().getZ()) && !pendingReloadRefresh) {
            pendingReloadRefresh = true;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pendingReloadRefresh = false;
                if (isEnabled()) {
                    refreshAll(lastModeSummary, lastTourneyBanner, lastTourneySquads, lastTourneyBracket);
                }
            }, 2L);
        }
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
