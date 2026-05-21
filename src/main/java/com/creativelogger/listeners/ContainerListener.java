package com.creativelogger.listeners;

import com.creativelogger.CreativeLogger;
import com.creativelogger.database.DatabaseManager;
import com.creativelogger.models.ContainerLog;
import com.creativelogger.utils.ItemUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class ContainerListener implements Listener {
    private final CreativeLogger plugin;

    public ContainerListener(CreativeLogger plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("stafflog.staff")) return;

        Inventory top = event.getView().getTopInventory();
        if (!isLoggableContainer(top.getType())) return;

        if (event.getClick().isCreativeAction()) return;

        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() == Material.AIR) return;
        if (!ItemUtils.hasCreativeTag(current)) return;

        int sessionId = plugin.getPlayerListener().getActiveSessionId(p.getUniqueId());
        if (sessionId < 0) return;

        Location loc = getContainerLocation(top);
        if (loc == null) return;

        DatabaseManager db = plugin.getDatabaseManager();
        ContainerLog log = new ContainerLog(0, sessionId, p.getName(),
                current.getType().toString(), current.getAmount(),
                top.getType().toString(), loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), System.currentTimeMillis());
        db.addContainerLog(log);

        plugin.getDiscordWebhook().send("container_store",
                p.getName() + " guardou " + current.getType() + " x" + current.getAmount()
                        + " em " + top.getType() + " @" + loc.getWorld().getName() + " "
                        + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
    }

    private boolean isLoggableContainer(InventoryType type) {
        return type == InventoryType.CHEST || type == InventoryType.BARREL
                || type == InventoryType.FURNACE || type == InventoryType.BLAST_FURNACE
                || type == InventoryType.SMOKER || type == InventoryType.HOPPER
                || type == InventoryType.DISPENSER || type == InventoryType.DROPPER
                || type == InventoryType.SHULKER_BOX || type == InventoryType.BREWING;
    }

    private Location getContainerLocation(Inventory inv) {
        if (inv.getLocation() != null) return inv.getLocation();
        if (inv.getHolder() instanceof org.bukkit.block.BlockState state) {
            return state.getLocation();
        }
        return null;
    }
}
