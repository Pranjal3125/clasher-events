package network.clasher.events;

import network.clasher.events.arena.ArenaBlockTracker;
import network.clasher.events.arena.ArenaManager;
import network.clasher.events.command.EventCommands;
import network.clasher.events.gui.EventGui;
import network.clasher.events.hook.ClasherEventsExpansion;
import network.clasher.events.hud.EventHudManager;
import network.clasher.events.kit.KitManager;
import network.clasher.events.match.EventManager;
import network.clasher.events.notify.DiscordWebhookNotifier;
import network.clasher.events.schedule.EventScheduler;
import network.clasher.events.storage.DataStore;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * Clasher Events v2.0 entry point. Wires bundled assets, async atomic persistence,
 * void world & zero-dependency Sponge schematic paster, tick-batched rollback,
 * live HUD, auto-scheduler, Discord webhooks, optional PlaceholderAPI, GUIs, and commands.
 */
public final class ClasherEventsPlugin extends JavaPlugin {
    private static final List<String> BUNDLED_ASSETS = List.of(
            "arenas.yml",
            "schematics/nethpot.schem",
            "schematics/axe-shield.schem",
            "schematics/build-uhc.schem",
            "schematics/sword.schem",
            "schematics/uhc.schem",
            "schematics/crystal.schem",
            "schematics/smp.schem",
            "schematics/diamond-pot.schem",
            "kits/nethpot.yml",
            "kits/axe-shield.yml",
            "kits/build-uhc.yml",
            "kits/sword.yml",
            "kits/uhc.yml",
            "kits/crystal.yml",
            "kits/smp.yml",
            "kits/diamond-pot.yml"
    );

    private DataStore dataStore;
    private ArenaManager arenaManager;
    private KitManager kitManager;
    private EventManager eventManager;
    private EventScheduler scheduler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String resource : BUNDLED_ASSETS) {
            if (!new File(getDataFolder(), resource).exists()) {
                saveResource(resource, false);
            }
        }

        this.dataStore = new DataStore(getDataFolder(), getLogger());
        this.arenaManager = new ArenaManager(this);
        this.kitManager = new KitManager(getDataFolder());
        arenaManager.load();
        kitManager.load();
        arenaManager.ensureAllWorldGuardRegions();

        ArenaBlockTracker blockTracker = new ArenaBlockTracker();
        EventHudManager hud = new EventHudManager(this);
        DiscordWebhookNotifier discord = new DiscordWebhookNotifier(this);

        this.eventManager = new EventManager(this, arenaManager, kitManager, dataStore, blockTracker, hud, discord);
        EventGui eventGui = new EventGui(this, arenaManager, eventManager, kitManager);
        EventCommands commands = new EventCommands(this, arenaManager, kitManager, eventManager, eventGui);

        registerCommand("ce", commands);
        registerCommand("event", commands);
        registerCommand("latejoin", commands);

        Bukkit.getPluginManager().registerEvents(eventManager, this);
        Bukkit.getPluginManager().registerEvents(eventGui, this);

        this.scheduler = new EventScheduler(this, eventManager);
        scheduler.start();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new ClasherEventsExpansion(this, eventManager, dataStore).register();
                getLogger().info("Hooked into PlaceholderAPI (%clasherevents_*%).");
            } catch (Throwable ex) {
                getLogger().warning("PlaceholderAPI hook failed: " + ex.getMessage());
            }
        }

        getLogger().info("Clasher Events v" + getPluginMeta().getVersion()
                + " enabled (" + arenaManager.arenaIds().size() + " arenas, "
                + kitManager.kitIds().size() + " kits, world: " + arenaManager.sharedWorldName() + ")");
    }

    public void reloadAll() {
        reloadConfig();
        if (arenaManager != null) arenaManager.load();
        if (kitManager != null) kitManager.load();
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (eventManager != null) {
            eventManager.shutdown();
        }
        if (dataStore != null) {
            dataStore.flushAndShutdown();
        }
    }

    private void registerCommand(String name, EventCommands handler) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
    }
}
