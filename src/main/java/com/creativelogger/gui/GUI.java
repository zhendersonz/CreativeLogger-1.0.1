package com.creativelogger.gui;

import com.creativelogger.CreativeLogger;
import com.creativelogger.database.DatabaseManager;
import com.creativelogger.database.DatabaseManager.PlayerSummary;
import com.creativelogger.models.Session;
import com.creativelogger.models.SessionItem;
import com.creativelogger.utils.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class GUI {
    private final CreativeLogger plugin;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
    private final Map<UUID, GUIState> playerStates = new HashMap<>();
    private final Set<UUID> transitioning = new HashSet<>();

    private static final int ITEMS_PER_PAGE = 45;
    private static final int FILTER_SLOT = 48;
    private static final int SEARCH_SLOT = 49;
    private static final int PREV_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int BACK_SLOT = 47;

    public GUI(CreativeLogger plugin) {
        this.plugin = plugin;
    }

    public void openStaffList(Player p, String filter) {
        GUIState state = new GUIState();
        state.page = 0;
        state.viewType = ViewType.STAFF_LIST;
        state.filter = filter != null ? filter : "";
        playerStates.put(p.getUniqueId(), state);
        renderStaffList(p);
    }

    public void openStaffList(Player p) {
        openStaffList(p, null);
    }

    private void renderStaffList(Player p) {
        GUIState state = playerStates.get(p.getUniqueId());
        if (state == null) return;
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("§8Staff Log - Staffs"));

        DatabaseManager db = plugin.getDatabaseManager();
        Map<UUID, PlayerSummary> summaries = db.getAllPlayerSummaries();

        List<Map.Entry<UUID, PlayerSummary>> sorted = summaries.entrySet().stream()
                .filter(e -> state.filter.isEmpty() || e.getValue().playerName.toLowerCase().contains(state.filter))
                .sorted((a, b) -> Integer.compare(b.getValue().avgSuspicionScore, a.getValue().avgSuspicionScore))
                .collect(Collectors.toList());

        int start = state.page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, sorted.size());

        for (int i = start; i < end; i++) {
            var entry = sorted.get(i);
            PlayerSummary summary = entry.getValue();
            Player online = Bukkit.getPlayer(summary.playerUuid);
            boolean isOnline = online != null && online.isOnline();

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (online != null) {
                meta.setOwningPlayer(online);
            }
            meta.displayName(Component.text(summary.playerName)
                    .color(isOnline ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Sessões: §f" + summary.sessionCount));
            lore.add(Component.text("§7Itens: §f" + summary.totalItems));
            lore.add(Component.text("§7Score Médio: ").append(getScoreColor(summary.avgSuspicionScore)));
            lore.add(Component.text("§7Status: " + (isOnline ? "§aOnline" : "§cOffline")));
            lore.add(Component.text("§7Última vez: §f" + dateFormat.format(new Date(summary.lastSeen))));
            lore.add(Component.text(""));
            lore.add(Component.text("§eClique para ver sessões"));
            meta.lore(lore);
            head.setItemMeta(meta);

            inv.setItem(i - start, head);
        }

        addNavButtons(inv, state, sorted.size());
        transitioning.add(p.getUniqueId());
        p.openInventory(inv);
    }

    public void openPlayerSessions(Player p, UUID targetUuid, String targetName) {
        GUIState state = new GUIState();
        state.page = 0;
        state.viewType = ViewType.PLAYER_SESSIONS;
        state.targetUuid = targetUuid;
        state.targetName = targetName;
        state.filter = "";
        playerStates.put(p.getUniqueId(), state);
        renderPlayerSessions(p);
    }

    private void renderPlayerSessions(Player p) {
        GUIState state = playerStates.get(p.getUniqueId());
        if (state == null || state.targetUuid == null) return;
        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text("§8Sessões - " + state.targetName));

        DatabaseManager db = plugin.getDatabaseManager();
        List<Session> sessions = db.getSessionsByPlayer(state.targetUuid);

        List<Session> filtered = sessions.stream()
                .filter(s -> {
                    if (state.filter.isEmpty()) return true;
                    String f = state.filter.toLowerCase();
                    if (f.equals("blocked") || f.equals("bloqueado")) {
                        return db.getSessionItems(s.getId()).stream().anyMatch(SessionItem::isBlocked);
                    }
                    if (f.equals("highscore") || f.equals("score")) {
                        return s.getSuspicionScore() >= 50;
                    }
                    if (f.equals("notas") || f.equals("notes")) {
                        return s.getNotes() != null && !s.getNotes().isEmpty();
                    }
                    return String.valueOf(s.getId()).contains(f);
                })
                .collect(Collectors.toList());

        int start = state.page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, filtered.size());

        for (int i = start; i < end; i++) {
            Session session = filtered.get(i);
            ItemStack item = new ItemStack(Material.BOOK);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("§6Sessão #" + session.getId())
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Início: §f" + dateFormat.format(new Date(session.getStartTime()))));
            if (session.getEndTime() > 0) {
                lore.add(Component.text("§7Fim: §f" + dateFormat.format(new Date(session.getEndTime()))));
            }
            lore.add(Component.text("§7Duração: §f" + session.getFormattedDuration()));
            lore.add(Component.text("§7Itens: §f" + session.getItemCount()));
            lore.add(Component.text("§7Score: ").append(getScoreColor(session.getSuspicionScore())));
            lore.add(Component.text("§7Status: " + (session.isActive() ? "§aAtiva" : "§7Encerrada")));
            if (session.getNotes() != null && !session.getNotes().isEmpty()) {
                lore.add(Component.text(""));
                lore.add(Component.text("§6Notas:"));
                for (String note : session.getNotes()) {
                    lore.add(Component.text("§7- " + note));
                }
            }
            lore.add(Component.text(""));
            lore.add(Component.text("§eClique para ver itens"));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(i - start, item);
        }

        addNavButtons(inv, state, filtered.size());
        transitioning.add(p.getUniqueId());
        p.openInventory(inv);
    }

    public void openSessionItems(Player p, int sessionId, String playerName) {
        GUIState state = new GUIState();
        state.page = 0;
        state.viewType = ViewType.SESSION_ITEMS;
        state.sessionId = sessionId;
        state.targetName = playerName;
        state.filter = "";
        playerStates.put(p.getUniqueId(), state);
        renderSessionItems(p);
    }

    private void renderSessionItems(Player p) {
        GUIState state = playerStates.get(p.getUniqueId());
        if (state == null) return;
        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text("§8Itens - Sessão #" + state.sessionId));

        DatabaseManager db = plugin.getDatabaseManager();
        List<SessionItem> items = db.getSessionItems(state.sessionId);

        List<SessionItem> filtered = items.stream()
                .filter(item -> {
                    if (state.filter.isEmpty()) return true;
                    String f = state.filter.toLowerCase();
                    if (f.equals("blocked") || f.equals("bloqueado")) return item.isBlocked();
                    if (f.equals("highvalue") || f.equals("valor")) {
                        try {
                            return plugin.getConfigManager().isHighValue(Material.valueOf(item.getMaterial()));
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    }
                    if (f.equals("vanish")) return item.getAction() != null && item.getAction().contains("vanish");
                    return item.getMaterial().toLowerCase().contains(f);
                })
                .collect(Collectors.toList());

        int start = state.page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, filtered.size());

        for (int i = start; i < end; i++) {
            SessionItem si = filtered.get(i);
            Material mat;
            try {
                mat = Material.valueOf(si.getMaterial());
            } catch (IllegalArgumentException e) {
                mat = Material.STONE;
            }

            ItemStack display = new ItemStack(mat, Math.min(si.getAmount(), 64));
            ItemMeta meta = display.getItemMeta();
            meta.displayName(Component.text((si.isBlocked() ? "§c" : "§a") + si.getMaterial())
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Quantidade: §f" + si.getAmount()));
            lore.add(Component.text("§7Hash: §f" + (si.getHash() != null ? si.getHash().substring(0, Math.min(16, si.getHash().length())) + "..." : "N/A")));
            lore.add(Component.text("§7Ação: §f" + si.getAction()));
            lore.add(Component.text("§7Tempo: §f" + dateFormat.format(new Date(si.getTimestamp()))));
            if (si.isBlocked()) {
                lore.add(Component.text("§cBLOQUEADO"));
            }
            meta.lore(lore);
            display.setItemMeta(meta);
            inv.setItem(i - start, display);
        }

        addNavButtons(inv, state, filtered.size());
        transitioning.add(p.getUniqueId());
        p.openInventory(inv);
    }

    private void addNavButtons(Inventory inv, GUIState state, int totalItems) {
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE));

        if (state.page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.displayName(Component.text("§aPágina Anterior").decoration(TextDecoration.ITALIC, false));
            prev.setItemMeta(prevMeta);
            inv.setItem(PREV_SLOT, prev);
        }

        if (state.page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.displayName(Component.text("§aPróxima Página").decoration(TextDecoration.ITALIC, false));
            next.setItemMeta(nextMeta);
            inv.setItem(NEXT_SLOT, next);
        }

        if (state.viewType != ViewType.STAFF_LIST) {
            ItemStack back = new ItemStack(Material.BARRIER);
            ItemMeta backMeta = back.getItemMeta();
            backMeta.displayName(Component.text("§cVoltar").decoration(TextDecoration.ITALIC, false));
            back.setItemMeta(backMeta);
            inv.setItem(BACK_SLOT, back);
        }

        ItemStack pageInfo = new ItemStack(Material.PAPER);
        ItemMeta pageMeta = pageInfo.getItemMeta();
        pageMeta.displayName(Component.text("§7Página " + (state.page + 1) + "/" + totalPages)
                .decoration(TextDecoration.ITALIC, false));
        pageInfo.setItemMeta(pageMeta);
        inv.setItem(49, pageInfo);

        ItemStack filterBtn = new ItemStack(Material.HOPPER);
        ItemMeta filterMeta = filterBtn.getItemMeta();
        filterMeta.displayName(Component.text("§eFiltros").decoration(TextDecoration.ITALIC, false));
        List<Component> filterLore = new ArrayList<>();
        filterLore.add(Component.text("§7Filtro atual: §f" + (state.filter.isEmpty() ? "nenhum" : state.filter)));
        filterLore.add(Component.text(""));
        filterLore.add(Component.text("§aItens bloqueados: bloqueado"));
        filterLore.add(Component.text("§aAlto valor: highvalue"));
        filterLore.add(Component.text("§aScore alto: highscore"));
        filterLore.add(Component.text("§aCom notas: notas"));
        filterLore.add(Component.text("§aVanish: vanish"));
        filterLore.add(Component.text("§eShift+Click: limpar filtro"));
        filterMeta.lore(filterLore);
        filterBtn.setItemMeta(filterMeta);
        inv.setItem(FILTER_SLOT, filterBtn);
    }

    public void handleClick(Player p, int slot, Inventory inv, boolean shift) {
        GUIState state = playerStates.get(p.getUniqueId());
        if (state == null) return;

        if (slot == FILTER_SLOT && shift) {
            state.filter = "";
            reRender(p);
            return;
        }

        if (slot == PREV_SLOT && state.page > 0) {
            state.page--;
            reRender(p);
            return;
        }
        if (slot == NEXT_SLOT) {
            state.page++;
            reRender(p);
            return;
        }
        if (slot == BACK_SLOT) {
            if (state.viewType == ViewType.PLAYER_SESSIONS) {
                openStaffList(p, state.filter);
            } else if (state.viewType == ViewType.SESSION_ITEMS) {
                openPlayerSessions(p, state.targetUuid, state.targetName);
            }
            return;
        }

        if (slot >= 0 && slot < ITEMS_PER_PAGE) {
            int index = state.page * ITEMS_PER_PAGE + slot;
            DatabaseManager db = plugin.getDatabaseManager();

            switch (state.viewType) {
                case STAFF_LIST -> {
                    Map<UUID, PlayerSummary> summaries = db.getAllPlayerSummaries();
                    List<Map.Entry<UUID, PlayerSummary>> list = new ArrayList<>(summaries.entrySet());
                    list.removeIf(e -> e.getKey() == null || e.getValue() == null);
                    list.sort((a, b) -> Integer.compare(b.getValue().avgSuspicionScore, a.getValue().avgSuspicionScore));
                    if (index >= 0 && index < list.size()) {
                        var entry = list.get(index);
                        UUID targetUuid = entry.getKey();
                        if (targetUuid == null) return;
                        openPlayerSessions(p, targetUuid, entry.getValue().playerName);
                    }
                }
                case PLAYER_SESSIONS -> {
                    List<Session> sessions = db.getSessionsByPlayer(state.targetUuid);
                    List<Session> filtered = applyFilter(sessions, state.filter, db);
                    if (index >= 0 && index < filtered.size()) {
                        Session s = filtered.get(index);
                        openSessionItems(p, s.getId(), state.targetName);
                    }
                }
                case SESSION_ITEMS -> {
                    // Click on an item in session view - show detailed info/rollback
                    List<SessionItem> items = db.getSessionItems(state.sessionId);
                    if (index >= 0 && index < items.size()) {
                        SessionItem si = items.get(index);
                        p.sendMessage("§6[CreativeLogger] Item: §f" + si.getMaterial()
                                + " §7x" + si.getAmount()
                                + " §7| Hash: §f" + si.getHash()
                                + " §7| Ação: §f" + si.getAction()
                                + " §7| Bloqueado: " + (si.isBlocked() ? "§cSim" : "§aNão"));
                    }
                }
            }
        }
    }

    private List<Session> applyFilter(List<Session> sessions, String filter, DatabaseManager db) {
        if (filter.isEmpty()) return sessions;
        String f = filter.toLowerCase();
        return sessions.stream().filter(s -> {
            if (f.equals("blocked") || f.equals("bloqueado")) {
                return db.getSessionItems(s.getId()).stream().anyMatch(SessionItem::isBlocked);
            }
            if (f.equals("highscore") || f.equals("score")) return s.getSuspicionScore() >= 50;
            if (f.equals("notas") || f.equals("notes")) return s.getNotes() != null && !s.getNotes().isEmpty();
            if (f.equals("vanish")) {
                return db.getSessionItems(s.getId()).stream()
                        .anyMatch(i -> i.getAction() != null && i.getAction().contains("vanish"));
            }
            return String.valueOf(s.getId()).contains(f);
        }).collect(Collectors.toList());
    }

    public void applyFilter(Player p, String filter) {
        GUIState state = playerStates.get(p.getUniqueId());
        if (state == null) return;
        state.filter = filter.toLowerCase();
        state.page = 0;
        reRender(p);
    }

    private void reRender(Player p) {
        GUIState state = playerStates.get(p.getUniqueId());
        if (state == null) return;
        switch (state.viewType) {
            case STAFF_LIST -> renderStaffList(p);
            case PLAYER_SESSIONS -> renderPlayerSessions(p);
            case SESSION_ITEMS -> renderSessionItems(p);
        }
    }

    private Component getScoreColor(int score) {
        if (score >= 70) return Component.text("§c" + score);
        if (score >= 40) return Component.text("§e" + score);
        return Component.text("§a" + score);
    }

    public boolean isTransitioning(Player p) {
        return transitioning.contains(p.getUniqueId());
    }

    public void removeState(Player p) {
        if (!transitioning.contains(p.getUniqueId())) {
            playerStates.remove(p.getUniqueId());
        }
        transitioning.remove(p.getUniqueId());
    }

    public GUIState getState(Player p) {
        return playerStates.get(p.getUniqueId());
    }

    public List<UUID> getOpenPlayers() {
        return playerStates.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public void notifyDataChanged(Player p) {
        GUIState state = playerStates.get(p.getUniqueId());
        if (state == null) return;
        p.sendActionBar(Component.text("§a[CreativeLogger] Dados atualizados!"));
    }

    private static class GUIState {
        int page;
        ViewType viewType;
        UUID targetUuid;
        String targetName;
        int sessionId;
        String filter;
    }

    public enum ViewType {
        STAFF_LIST, PLAYER_SESSIONS, SESSION_ITEMS
    }
}
