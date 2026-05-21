package com.creativelogger.gui;

import com.creativelogger.CreativeLogger;
import com.creativelogger.models.Session;
import com.creativelogger.models.SessionItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SessionViewGUI {
    private final CreativeLogger plugin;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    public SessionViewGUI(CreativeLogger plugin) {
        this.plugin = plugin;
    }

    public void open(Player p, int sessionId) {
        Session session = plugin.getDatabaseManager().getSessionById(sessionId);
        if (session == null) {
            p.sendMessage("§cSessão #" + sessionId + " não encontrada.");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text("§8Sessão #" + sessionId + " - " + session.getPlayerName()));

        for (int i = 0; i < 9; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, sessionInfoItem(session));

        List<SessionItem> items = plugin.getDatabaseManager().getSessionItems(sessionId);
        int slot = 9;
        for (SessionItem si : items) {
            if (slot >= 54) break;
            try {
                Material mat = Material.valueOf(si.getMaterial());
                ItemStack display = new ItemStack(mat, Math.min(si.getAmount(), 64));
                ItemMeta meta = display.getItemMeta();
                meta.displayName(Component.text((si.isBlocked() ? "§c" : "§a") + si.getMaterial())
                        .decoration(TextDecoration.ITALIC, false));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("§7Qtd: §f" + si.getAmount()));
                lore.add(Component.text("§7Hash: §f" + (si.getHash() != null ? si.getHash().substring(0, Math.min(16, si.getHash().length())) + "..." : "N/A")));
                lore.add(Component.text("§7Ação: §f" + si.getAction()));
                lore.add(Component.text("§7Tempo: §f" + dateFormat.format(new Date(si.getTimestamp()))));
                if (si.isBlocked()) lore.add(Component.text("§cBLOQUEADO"));
                meta.lore(lore);
                display.setItemMeta(meta);
                inv.setItem(slot, display);
                slot++;
            } catch (IllegalArgumentException ignored) {}
        }

        p.openInventory(inv);
    }

    private ItemStack sessionInfoItem(Session session) {
        ItemStack item = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("§6Sessão #" + session.getId()));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7Jogador: §f" + session.getPlayerName()));
        lore.add(Component.text("§7Início: §f" + dateFormat.format(new Date(session.getStartTime()))));
        if (session.getEndTime() > 0) {
            lore.add(Component.text("§7Fim: §f" + dateFormat.format(new Date(session.getEndTime()))));
        }
        lore.add(Component.text("§7Duração: §f" + session.getFormattedDuration()));
        lore.add(Component.text("§7Itens: §f" + session.getItemCount()));
        lore.add(Component.text("§7Score: ").append(getScoreColor(session.getSuspicionScore())));
        if (session.getNotes() != null && !session.getNotes().isEmpty()) {
            lore.add(Component.text(""));
            lore.add(Component.text("§6Notas:"));
            for (String note : session.getNotes()) {
                lore.add(Component.text("§7- " + note));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack filler() {
        ItemStack f = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = f.getItemMeta();
        m.displayName(Component.text("").decoration(TextDecoration.ITALIC, false));
        f.setItemMeta(m);
        return f;
    }

    private Component getScoreColor(int score) {
        if (score >= 70) return Component.text("§c" + score);
        if (score >= 40) return Component.text("§e" + score);
        return Component.text("§a" + score);
    }
}
