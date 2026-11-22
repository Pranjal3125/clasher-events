package network.clasher.events;

import network.clasher.events.arena.ArenaManager;
import network.clasher.events.command.EventCommands;
import network.clasher.events.gui.EventGui;
import network.clasher.events.kit.KitManager;
import network.clasher.events.match.EventManager;
import network.clasher.events.storage.DataStore;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Clasher Events entry point. Wires bundled assets, atomic persistence,
 * arena/kit managers, event state machine, GUIs, and commands.
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String resource : BUNDLED_ASSETS) {
            if (!new java.io.File(getDataFolder(), resource).exists()) {
                saveResource(resource, false);
            }
        }

        this.dataStore = new DataStore(getDataFolder(), getLogger());
        this.arenaManager = new ArenaManager(this);
        this.kitManager = new KitManager(getDataFolder());
        arenaManager.load();
        kitManager.load();
        arenaManager.ensureAllWorldGuardRegions();

        this.eventManager = new EventManager(this, arenaManager, kitManager, dataStore);
        EventGui eventGui = new EventGui(this, eventManager, kitManager);
        EventCommands commands = new EventCommands(this, arenaManager, kitManager, eventManager, eventGui);

        registerCommand("ce", commands);
        registerCommand("event", commands);
        registerCommand("latejoin", commands);

        Bukkit.getPluginManager().registerEvents(eventManager, this);
        Bukkit.getPluginManager().registerEvents(eventGui, this);

        getLogger().info("Clasher Events v" + getPluginMeta().getVersion()
                + " enabled (" + arenaManager.arenaIds().size() + " arenas, "
                + kitManager.kitIds().size() + " kits, shared world: " + arenaManager.sharedWorldName() + ")");
    }

    public void reloadAll() {
        reloadConfig();
        if (arenaManager != null) arenaManager.load();
        if (kitManager != null) kitManager.load();
    }

    @Override
    public void onDisable() {
        if (eventManager != null) {
            eventManager.shutdown();
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
