package network.clasher.events.match;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory expiring cache of post-duel player inventories & pro combat analytics
 * so players can click `[Inspect Winner Inv]` / `[Inspect Loser Inv]` right after any duel!
 */
public final class DuelSnapshotStore {
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_ENTRIES = 64;

    public record PostDuelSnapshot(
            String token,
            String playerName,
            String kitId,
            boolean winner,
            double remainingHp,
            double maxHp,
            int hitsLanded,
            int critHits,
            int longestCombo,
            double damageDealt,
            int pingMs,
            ItemStack[] armor,
            ItemStack offhand,
            ItemStack[] contents,
            long createdAtMs
    ) {
        public int critPercent() {
            return hitsLanded <= 0 ? 0 : (int) Math.round((critHits * 100.0) / hitsLanded);
        }
    }

    private final Map<String, PostDuelSnapshot> cache = new LinkedHashMap<>();

    public synchronized String capture(
            Player player,
            String kitId,
            boolean winner,
            int hitsLanded,
            int critHits,
            int longestCombo,
            double damageDealt
    ) {
        pruneExpired();
        String token = UUID.randomUUID().toString().substring(0, 8);
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = attr != null ? attr.getValue() : 20.0;
        double hp = player.isDead() ? 0.0 : Math.max(0.0, player.getHealth());

        ItemStack[] rawArmor = player.getInventory().getArmorContents();
        ItemStack[] armorCopy = new ItemStack[4];
        for (int i = 0; i < Math.min(4, rawArmor.length); i++) {
            armorCopy[i] = rawArmor[i] != null ? rawArmor[i].clone() : null;
        }
        ItemStack offhandRaw = player.getInventory().getItemInOffHand();
        ItemStack offhandCopy = (offhandRaw != null && !offhandRaw.getType().isAir()) ? offhandRaw.clone() : null;

        ItemStack[] rawContents = player.getInventory().getStorageContents();
        ItemStack[] contentsCopy = new ItemStack[36];
        for (int i = 0; i < Math.min(36, rawContents.length); i++) {
            contentsCopy[i] = rawContents[i] != null ? rawContents[i].clone() : null;
        }

        PostDuelSnapshot snap = new PostDuelSnapshot(
                token,
                player.getName(),
                kitId,
                winner,
                hp,
                maxHp,
                hitsLanded,
                critHits,
                longestCombo,
                damageDealt,
                Math.max(0, player.getPing()),
                armorCopy,
                offhandCopy,
                contentsCopy,
                System.currentTimeMillis()
        );
        cache.put(token, snap);
        while (cache.size() > MAX_ENTRIES) {
            Iterator<String> it = cache.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        return token;
    }

    public synchronized PostDuelSnapshot get(String token) {
        if (token == null || token.isBlank()) return null;
        pruneExpired();
        return cache.get(token.trim());
    }

    private void pruneExpired() {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        cache.values().removeIf(s -> s.createdAtMs() < cutoff);
    }
}
