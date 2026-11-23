package network.clasher.events.arena;

import network.clasher.events.model.Arena;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight, zero-lag block-change recorder and tick-batched rollback engine for
 * destructible PvP modes (Build UHC, Crystal PvP, TNT/liquid placement).
 * Preserves the FIRST original pre-match state per coordinate so repeated edits restore cleanly.
 */
public final class ArenaBlockTracker {
    public record BlockPos(int x, int y, int z) {}

    private final Map<BlockPos, BlockData> originalStates = new LinkedHashMap<>();
    private String activeWorldName;
    private Arena activeArena;
    private boolean tracking;

    public void startTracking(String worldName, Arena arena) {
        this.originalStates.clear();
        this.activeWorldName = worldName;
        this.activeArena = arena;
        this.tracking = arena != null && worldName != null;
    }

    public boolean isTracking() {
        return tracking;
    }

    public int trackedCount() {
        return originalStates.size();
    }

    public void recordBeforeChange(Block block) {
        if (!tracking || block == null || activeArena == null) return;
        if (!block.getWorld().getName().equalsIgnoreCase(activeWorldName)) return;
        if (!activeArena.contains(block.getX(), block.getY(), block.getZ())) return;
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        originalStates.putIfAbsent(pos, block.getBlockData().clone());
    }

    public void recordBeforeChange(BlockPos pos, BlockData originalData) {
        if (!tracking || pos == null || originalData == null) return;
        if (activeArena != null && !activeArena.contains(pos.x(), pos.y(), pos.z())) return;
        originalStates.putIfAbsent(pos, originalData);
    }

    public Map<BlockPos, BlockData> snapshotAndClear() {
        Map<BlockPos, BlockData> copy = new LinkedHashMap<>(originalStates);
        originalStates.clear();
        tracking = false;
        return copy;
    }

    /**
     * Restores all recorded block edits in smooth tick-batched chunks (20 TPS safe).
     */
    public void rollbackBatched(JavaPlugin plugin, int blocksPerTick, Runnable onComplete) {
        String worldName = this.activeWorldName;
        Map<BlockPos, BlockData> toRestore = snapshotAndClear();
        this.activeArena = null;

        if (toRestore.isEmpty() || worldName == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        List<Map.Entry<BlockPos, BlockData>> entries = new ArrayList<>(toRestore.entrySet());
        // Reverse order restores liquids/placed blocks cleanly
        java.util.Collections.reverse(entries);

        int batch = Math.max(100, blocksPerTick);
        int[] cursor = {0};
        BukkitTask[] holder = new BukkitTask[1];

        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.min(entries.size(), cursor[0] + batch);
            for (int i = cursor[0]; i < end; i++) {
                Map.Entry<BlockPos, BlockData> e = entries.get(i);
                BlockPos p = e.getKey();
                world.getBlockAt(p.x(), p.y(), p.z()).setBlockData(e.getValue(), false);
            }
            cursor[0] = end;
            if (cursor[0] >= entries.size()) {
                if (holder[0] != null) holder[0].cancel();
                if (onComplete != null) onComplete.run();
            }
        }, 1L, 1L);
    }

    /**
     * Immediate synchronous rollback (used during server shutdown when scheduler tasks cannot run).
     */
    public void rollbackImmediate() {
        String worldName = this.activeWorldName;
        Map<BlockPos, BlockData> toRestore = snapshotAndClear();
        this.activeArena = null;
        if (toRestore.isEmpty() || worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        for (Map.Entry<BlockPos, BlockData> e : toRestore.entrySet()) {
            BlockPos p = e.getKey();
            world.getBlockAt(p.x(), p.y(), p.z()).setBlockData(e.getValue(), false);
        }
    }
}
