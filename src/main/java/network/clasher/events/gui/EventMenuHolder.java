package network.clasher.events.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Secure InventoryHolder marker for all Clasher Events GUIs.
 */
public final class EventMenuHolder implements InventoryHolder {
    public enum ViewType {
        ADMIN_MODES,
        COUNTDOWN,
        KIT_PREVIEW,
        VOTE_PREDICTION,
        SPECTATE_FIGHTER,
        MODE_VOTE,
        LEADERBOARD
    }

    private final ViewType viewType;
    private Inventory inventory;

    public EventMenuHolder(ViewType viewType) {
        this.viewType = viewType;
    }

    public ViewType viewType() {
        return viewType;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
