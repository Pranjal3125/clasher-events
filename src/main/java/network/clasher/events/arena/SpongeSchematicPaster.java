package network.clasher.events.arena;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Zero-dependency pure-Java Sponge Schematic v2 (.schem) reader and tick-batched paster.
 * Allows Clasher Events to paste all bundled arenas smoothly without TPS freezes and
 * without requiring WorldEdit to be installed.
 */
public final class SpongeSchematicPaster {

    public record ParsedSchematic(
            int width,
            int height,
            int length,
            int offsetX,
            int offsetY,
            int offsetZ,
            String[] palette,
            int[] blocks
    ) {
        public String blockAt(int x, int y, int z) {
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) {
                return "minecraft:air";
            }
            int idx = (y * length + z) * width + x;
            int palId = blocks[idx];
            return (palId >= 0 && palId < palette.length && palette[palId] != null)
                    ? palette[palId]
                    : "minecraft:air";
        }

        public int nonAirBlockCount() {
            int count = 0;
            for (int palId : blocks) {
                if (palId >= 0 && palId < palette.length && !"minecraft:air".equals(palette[palId])) {
                    count++;
                }
            }
            return count;
        }
    }

    private SpongeSchematicPaster() {}

    /**
     * Parses a GZIP-compressed Sponge Schematic v2 file completely off the Bukkit API.
     */
    public static ParsedSchematic parse(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            int rootType = in.readUnsignedByte();
            if (rootType != 10) {
                throw new IOException("Expected root NBT compound (10), got " + rootType);
            }
            in.readUTF(); // Root compound name ("Schematic" or "")
            Map<String, Object> root = readCompound(in);

            int width = asInt(root.get("Width"));
            int height = asInt(root.get("Height"));
            int length = asInt(root.get("Length"));
            if (width <= 0 || height <= 0 || length <= 0) {
                throw new IOException("Invalid schematic dimensions: " + width + "x" + height + "x" + length);
            }

            int offX = 0, offY = 0, offZ = 0;
            if (root.get("Metadata") instanceof Map<?, ?> meta) {
                offX = asInt(meta.get("WEOffsetX"));
                offY = asInt(meta.get("WEOffsetY"));
                offZ = asInt(meta.get("WEOffsetZ"));
            }
            if (offX == 0 && offY == 0 && offZ == 0 && root.get("Offset") instanceof int[] offArr && offArr.length >= 3) {
                offX = offArr[0];
                offY = offArr[1];
                offZ = offArr[2];
            }

            Map<?, ?> rawPal = root.get("Palette") instanceof Map<?, ?> m ? m : Map.of();
            int maxId = -1;
            for (Object val : rawPal.values()) {
                maxId = Math.max(maxId, asInt(val));
            }
            String[] palette = new String[Math.max(1, maxId + 1)];
            for (Map.Entry<?, ?> entry : rawPal.entrySet()) {
                int id = asInt(entry.getValue());
                if (id >= 0 && id < palette.length) {
                    palette[id] = String.valueOf(entry.getKey());
                }
            }

            byte[] blockData = root.get("BlockData") instanceof byte[] b ? b : new byte[0];
            int total = width * height * length;
            int[] blocks = new int[total];
            int pos = 0;
            int blockIdx = 0;
            while (pos < blockData.length && blockIdx < total) {
                int val = 0;
                int shift = 0;
                while (pos < blockData.length) {
                    int b = blockData[pos++] & 0xFF;
                    val |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                blocks[blockIdx++] = val;
            }
            return new ParsedSchematic(width, height, length, offX, offY, offZ, palette, blocks);
        }
    }

    /**
     * Pastes a parsed schematic into a Bukkit world in tick-batched slices so TPS stays smooth.
     */
    public static BukkitTask pasteBatched(
            JavaPlugin plugin,
            World world,
            ParsedSchematic schem,
            int originX,
            int originY,
            int originZ,
            int blocksPerTick,
            boolean skipAir,
            Runnable onComplete
    ) {
        BlockData[] resolvedPalette = new BlockData[schem.palette().length];
        for (int i = 0; i < schem.palette().length; i++) {
            String raw = schem.palette()[i];
            if (raw == null || raw.isBlank()) continue;
            try {
                resolvedPalette[i] = Bukkit.createBlockData(raw);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping unknown block state in schematic: " + raw);
            }
        }

        // Pre-collect placements in YZX order so terrain/floors place before lanterns/fences
        List<long[]> ops = new ArrayList<>(Math.min(schem.blocks().length, 65536));
        int baseX = originX + schem.offsetX();
        int baseY = originY + schem.offsetY();
        int baseZ = originZ + schem.offsetZ();
        int w = schem.width(), h = schem.height(), l = schem.length();
        int[] blocks = schem.blocks();

        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                int rowBase = (y * l + z) * w;
                for (int x = 0; x < w; x++) {
                    int palId = blocks[rowBase + x];
                    if (palId < 0 || palId >= resolvedPalette.length) continue;
                    String name = schem.palette()[palId];
                    if (skipAir && "minecraft:air".equals(name)) continue;
                    if (resolvedPalette[palId] == null) continue;
                    ops.add(new long[]{baseX + x, baseY + y, baseZ + z, palId});
                }
            }
        }

        final int batch = Math.max(500, blocksPerTick);
        final int maxScanPerTick = batch * 8;
        final int[] cursor = {0};
        final BukkitTask[] holder = new BukkitTask[1];

        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int placed = 0;
            int scanned = 0;
            while (cursor[0] < ops.size() && placed < batch && scanned < maxScanPerTick) {
                long[] op = ops.get(cursor[0]++);
                scanned++;
                BlockData bd = resolvedPalette[(int) op[3]];
                if (bd == null) continue;
                var block = world.getBlockAt((int) op[0], (int) op[1], (int) op[2]);
                // Turbo void optimization: never waste tick budget setting air over existing air!
                if (bd.getMaterial().isAir() && block.getType().isAir()) continue;
                block.setBlockData(bd, false);
                placed++;
            }
            if (cursor[0] >= ops.size()) {
                if (holder[0] != null) holder[0].cancel();
                if (onComplete != null) onComplete.run();
            }
        }, 1L, 1L);

        return holder[0];
    }

    // ------------------------------------------------------------------
    // Minimal Binary NBT Reader (Tags 0..12)
    // ------------------------------------------------------------------

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> map = new HashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) break;
            String name = in.readUTF();
            map.put(name, readPayload(in, type));
        }
        return map;
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int len = in.readInt();
                byte[] arr = new byte[len];
                in.readFully(arr);
                yield arr;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int elemType = in.readUnsignedByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, len));
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, elemType));
                }
                yield list;
            }
            case 10 -> readCompound(in);
            case 11 -> {
                int len = in.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                yield arr;
            }
            case 12 -> {
                int len = in.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                yield arr;
            }
            default -> throw new IOException("Unsupported NBT tag type: " + type);
        };
    }

    private static int asInt(Object obj) {
        return obj instanceof Number n ? n.intValue() : 0;
    }
}
