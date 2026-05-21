package com.creativelogger.gui;

import com.creativelogger.CreativeLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GUIListener implements Listener {
    private final CreativeLogger plugin;
    private final Map<UUID, FilterInput> awaitingFilter = new HashMap<>();

    public GUIListener(CreativeLogger plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getView().getTopInventory().getSize() != 54) return;
        String title = event.getView().getTitle();
        if (!title.startsWith("§8Staff Log") && !title.startsWith("§8Sessões") && !title.startsWith("§8Itens")) return;

        event.setCancelled(true);

        GUI gui = plugin.getGUI();
        if (gui.getState(p) == null) return;

        int slot = event.getRawSlot();
        if (slot < 0) return;

        boolean shift = event.getClick().isShiftClick();
        gui.handleClick(p, slot, event.getInventory(), shift);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        plugin.getGUI().removeState(p);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        FilterInput input = awaitingFilter.remove(p.getUniqueId());
        if (input != null) {
            event.setCancelled(true);
            plugin.getGUI().applyFilter(p, event.getMessage());
        }
    }

    public void awaitFilter(Player p) {
        awaitingFilter.put(p.getUniqueId(), new FilterInput());
        p.sendMessage("§eDigite o filtro no chat:");
    }

    private static class FilterInput {}
}
