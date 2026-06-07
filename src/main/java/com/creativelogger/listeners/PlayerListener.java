package com.creativelogger.listeners;

import com.creativelogger.CreativeLogger;
import com.creativelogger.database.DatabaseManager;
import com.creativelogger.models.ContainerLog;
import com.creativelogger.models.Session;
import com.creativelogger.models.SessionItem;
import com.creativelogger.utils.ItemUtils;
import com.creativelogger.utils.LockdownManager;
import com.creativelogger.utils.RateLimiter;
import com.creativelogger.utils.SuspicionScore;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public class PlayerListener implements Listener {
    private final CreativeLogger plugin;
    private final Map<UUID, Integer> activeSessions = new HashMap<>();
    private final Map<UUID, Long> sessionJoinMap = new HashMap<>();
    private final Set<UUID> quitting = new HashSet<>();
    private final Map<UUID, Long> lastSessionEndTime = new HashMap<>();
    private static final long SESSION_REUSE_COOLDOWN = 3000;
    private final Map<UUID, Map<String, Long>> recentAudits = new HashMap<>();
    private static final long AUDIT_DEDUP_MS = 1000;

    private String auditKey(ItemStack item) {
        return item.getType().toString() + ":" + item.getAmount();
    }

    private boolean wasRecentlyAudited(Player p, ItemStack item) {
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        Map<String, Long> playerAudits = recentAudits.computeIfAbsent(uuid, k -> new HashMap<>());
        playerAudits.values().removeIf(t -> now - t > AUDIT_DEDUP_MS);
        String key = auditKey(item);
        if (playerAudits.containsKey(key)) return true;
        playerAudits.put(key, now);
        return false;
    }

    public PlayerListener(CreativeLogger plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        quitting.remove(p.getUniqueId());
        if (!p.hasPermission("stafflog.staff")) return;

        LockdownManager lm = plugin.getLockdownManager();
        if (lm.isPlayerLocked(p)) {
            p.setGameMode(GameMode.SURVIVAL);
            p.sendMessage("§c[CreativeLogger] LOCKDOWN ATIVO - Modo criativo bloqueado.");
            p.sendMessage("§cContate um administrador para desativar o lockdown.");
        }

        // Delay para dar tempo do nLogin terminar a autenticação antes de criar sessão
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
                startSession(p);
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("stafflog.staff")) return;
        quitting.add(p.getUniqueId());
        endSession(p, "quit");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("stafflog.staff")) return;

        if (event.getNewGameMode() == GameMode.CREATIVE || event.getNewGameMode() == GameMode.SPECTATOR) {
            LockdownManager lm = plugin.getLockdownManager();
            if (lm.isPlayerLocked(p)) {
                p.sendMessage("§c[CreativeLogger] LOCKDOWN ATIVO - Modo criativo bloqueado.");
                event.setCancelled(true);
                return;
            }
            startSession(p);
        } else {
            endSession(p, "gamemode");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreativeTake(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("stafflog.staff")) return;

        int slot = event.getSlot();
        ItemStack cursor = event.getCursor();

        plugin.getLogger().info("[CreativeTake-DEBUG] " + p.getName()
                + " slot=" + slot
                + " cursor=" + (cursor != null ? cursor.getType().toString() : "null")
                + " current=" + (event.getCurrentItem() != null ? event.getCurrentItem().getType().toString() : "null")
                + " click=" + event.getClick()
                + " action=" + event.getAction()
                + " inv=" + event.getInventory().getType());

        if (cursor != null && cursor.getType() != Material.AIR) {
            processCreativeTake(event, p, cursor);
            return;
        }

        UUID uuid = p.getUniqueId();
        if (!activeSessions.containsKey(uuid)) return;

        int sessionId = activeSessions.get(uuid);
        String actionSuffix = ItemUtils.getActionSuffix(p);
        ItemStack oldItem = event.getCurrentItem();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // Verifica o slot especifico se for valido
            if (slot >= 0 && slot <= 40) {
                ItemStack newItem = p.getInventory().getItem(slot);
                if (newItem != null && newItem.getType() != Material.AIR
                        && !plugin.getConfigManager().isIgnored(newItem.getType())
                        && !ItemUtils.hasCreativeTag(newItem)
                        && !newItem.isSimilar(oldItem)
                        && !wasRecentlyAudited(p, newItem)) {
                    ItemUtils.tagCreativeItem(newItem);
                    plugin.getLogger().info("[TRACE:AIR-SLOT] auditando " + newItem.getType() + " x" + newItem.getAmount() + " slot=" + slot);
                    addSessionItem(sessionId, newItem, "pickup_air_slot" + actionSuffix, false);
                    plugin.getDiscordWebhook().send("item_take",
                            p.getName() + " pegou " + newItem.getType().toString() + " x" + newItem.getAmount());
                    checkHighValue(p, newItem);
                }
            }

            // Verifica o cursor (captura scroll click que coloca item no cursor)
            ItemStack cursorItem = p.getItemOnCursor();
            if (cursorItem != null && cursorItem.getType() != Material.AIR
                    && !plugin.getConfigManager().isIgnored(cursorItem.getType())
                    && !ItemUtils.hasCreativeTag(cursorItem)
                    && !wasRecentlyAudited(p, cursorItem)) {
                ItemUtils.tagCreativeItem(cursorItem);
                plugin.getLogger().info("[TRACE:AIR-CURSOR] auditando " + cursorItem.getType() + " x" + cursorItem.getAmount());
                addSessionItem(sessionId, cursorItem, "pickup_air_cursor" + actionSuffix, false);
                plugin.getDiscordWebhook().send("item_take",
                        p.getName() + " pegou " + cursorItem.getType().toString() + " x" + cursorItem.getAmount());
                checkHighValue(p, cursorItem);
            }

            // Verifica todos os slots do hotbar (0-8) como rede de seguranca
            for (int i = 0; i < 9; i++) {
                ItemStack hotbarItem = p.getInventory().getItem(i);
                if (hotbarItem == null || hotbarItem.getType() == Material.AIR) continue;
                if (plugin.getConfigManager().isIgnored(hotbarItem.getType())) continue;
                if (ItemUtils.hasCreativeTag(hotbarItem)) continue;
                if (hotbarItem.isSimilar(oldItem)) continue;
                if (wasRecentlyAudited(p, hotbarItem)) continue;

                ItemUtils.tagCreativeItem(hotbarItem);
                plugin.getLogger().info("[TRACE:AIR-HOTBAR] auditando " + hotbarItem.getType() + " x" + hotbarItem.getAmount() + " slot=" + i);
                addSessionItem(sessionId, hotbarItem, "pickup_air_hotbar" + actionSuffix, false);
                plugin.getDiscordWebhook().send("item_take",
                        p.getName() + " pegou " + hotbarItem.getType().toString() + " x" + hotbarItem.getAmount());
                checkHighValue(p, hotbarItem);
            }
        });
    }

    private void processCreativeTake(InventoryCreativeEvent event, Player p, ItemStack item) {
        plugin.getLogger().info("[processCreativeTake] " + p.getName()
                + " item=" + item.getType() + " x" + item.getAmount()
                + " ignored=" + plugin.getConfigManager().isIgnored(item.getType()));

        if (plugin.getConfigManager().isIgnored(item.getType())) {
            plugin.getLogger().info("[processCreativeTake] IGNORADO: " + item.getType() + " esta na lista de ignorados");
            return;
        }
        if (wasRecentlyAudited(p, item)) {
            plugin.getLogger().info("[processCreativeTake] DEDUP: " + item.getType() + " ja auditado recentemente");
            return;
        }

        UUID uuid = p.getUniqueId();
        if (!activeSessions.containsKey(uuid)) {
            plugin.getLogger().info("[processCreativeTake] IGNORADO: " + p.getName() + " nao tem sessao ativa");
            return;
        }

        DatabaseManager db = plugin.getDatabaseManager();
        LockdownManager lm = plugin.getLockdownManager();
        RateLimiter rl = plugin.getRateLimiter();
        int sessionId = activeSessions.get(uuid);

        boolean isBypass = p.hasPermission("stafflog.bypass");
        boolean isBlockedPlayer = db.isPlayerBlocked(uuid);
        boolean isBlockedItem = plugin.getConfigManager().isBlocked(item.getType());
        boolean isWhitelisted = !isBlockedItem || db.isWhitelisted(uuid, item.getType().toString());

        String actionSuffix = ItemUtils.getActionSuffix(p);

        if (lm.isPlayerLocked(p) || isBlockedPlayer || (isBlockedItem && !isWhitelisted && !isBypass)) {
            event.setCancelled(true);
            String reason = isBlockedPlayer ? "jogador bloqueado" : "item bloqueado: " + item.getType();
            p.sendMessage("§c[CreativeLogger] Bloqueado: " + reason);
            p.closeInventory();

            addSessionItem(sessionId, item, "blocked_attempt" + actionSuffix, true);
            plugin.getDiscordWebhook().send("blocked_alert",
                    "**Bloqueado** - " + p.getName() + " tentou pegar " + item.getType() + " (" + reason + ")");
            plugin.getLogger().info("[processCreativeTake] BLOQUEADO: " + reason + " para " + p.getName());
            return;
        }

        if (!isBypass && !rl.tryConsume(uuid)) {
            event.setCancelled(true);
            p.sendMessage("§c[CreativeLogger] Rate limit excedido (max: " + plugin.getConfigManager().getRateLimit() + "/min)");
            p.closeInventory();
            plugin.getLogger().info("[processCreativeTake] RATE LIMIT: " + p.getName() + " excedeu rate limit");
            return;
        }

        ItemUtils.tagCreativeItem(item);
        plugin.getLogger().info("[processCreativeTake] SUCESSO: " + p.getName()
                + " pegou " + item.getType() + " x" + item.getAmount()
                + " sessao #" + sessionId);

        plugin.getLogger().info("[TRACE:PCT] auditando " + item.getType() + " x" + item.getAmount() + " para " + p.getName());

        String action = "pickup" + actionSuffix;
        addSessionItem(sessionId, item, action, false);
        plugin.getDiscordWebhook().send("item_take",
                p.getName() + " pegou " + item.getType().toString() + " x" + item.getAmount());
        checkHighValue(p, item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickItem(PlayerPickItemEvent event) {
        if (event.isCancelled()) return;
        Player p = event.getPlayer();
        if (!p.hasPermission("stafflog.staff")) return;

        UUID uuid = p.getUniqueId();
        if (!activeSessions.containsKey(uuid)) return;

        int sessionId = activeSessions.get(uuid);
        int targetSlot = event.getTargetSlot();
        String actionSuffix = ItemUtils.getActionSuffix(p);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (targetSlot >= 0 && targetSlot <= 40) {
                ItemStack item = p.getInventory().getItem(targetSlot);
                if (item != null && item.getType() != Material.AIR
                        && !plugin.getConfigManager().isIgnored(item.getType())
                        && !ItemUtils.hasCreativeTag(item)
                        && !wasRecentlyAudited(p, item)) {
                    plugin.getLogger().info("[TRACE:PICK-SLOT] auditando " + item.getType() + " x" + item.getAmount() + " targetSlot=" + targetSlot);
                    ItemUtils.tagCreativeItem(item);
                    addSessionItem(sessionId, item, "pickup_pick_slot" + actionSuffix, false);
                    plugin.getDiscordWebhook().send("item_take",
                            p.getName() + " pegou " + item.getType().toString() + " x" + item.getAmount());
                    checkHighValue(p, item);
                    return;
                }
            }

            // targetSlot -1 = item foi pro cursor (middle click copia pro cursor)
            ItemStack cursorItem = p.getItemOnCursor();
            if (cursorItem != null && cursorItem.getType() != Material.AIR
                    && !plugin.getConfigManager().isIgnored(cursorItem.getType())
                    && !ItemUtils.hasCreativeTag(cursorItem)
                    && !wasRecentlyAudited(p, cursorItem)) {
                plugin.getLogger().info("[TRACE:PICK-CURSOR] auditando " + cursorItem.getType() + " x" + cursorItem.getAmount());
                ItemUtils.tagCreativeItem(cursorItem);
                addSessionItem(sessionId, cursorItem, "pickup_pick_cursor" + actionSuffix, false);
                plugin.getDiscordWebhook().send("item_take",
                        p.getName() + " pegou " + cursorItem.getType().toString() + " x" + cursorItem.getAmount());
                checkHighValue(p, cursorItem);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("stafflog.staff")) return;
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) return;
        UUID uuid = p.getUniqueId();
        if (!activeSessions.containsKey(uuid)) return;

        if (!p.getOpenInventory().getType().name().contains("CREATIVE")) return;

        int slot = event.getSlot();
        if (slot < 0 || slot > 40) return;

        ItemStack newItem = event.getNewItemStack();
        if (newItem == null || newItem.getType() == Material.AIR) return;
        if (plugin.getConfigManager().isIgnored(newItem.getType())) return;
        if (ItemUtils.hasCreativeTag(newItem)) return;
        if (wasRecentlyAudited(p, newItem)) return;

        plugin.getLogger().info("[SlotChange-DEBUG] " + p.getName()
                + " slot=" + slot
                + " item=" + newItem.getType()
                + " x" + newItem.getAmount());

        int sessionId = activeSessions.get(uuid);
        String actionSuffix = ItemUtils.getActionSuffix(p);
        plugin.getLogger().info("[TRACE:SLOTCHANGE] auditando " + newItem.getType() + " x" + newItem.getAmount() + " slot=" + slot);
        ItemUtils.tagCreativeItem(newItem);
        addSessionItem(sessionId, newItem, "pickup_slotchange" + actionSuffix, false);
        plugin.getDiscordWebhook().send("item_take",
                p.getName() + " pegou " + newItem.getType().toString() + " x" + newItem.getAmount());
        checkHighValue(p, newItem);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        if (!p.hasPermission("stafflog.staff")) return;
        UUID uuid = p.getUniqueId();
        if (!activeSessions.containsKey(uuid)) return;

        String invType = event.getInventory().getType().name();
        if (!invType.contains("CREATIVE") && !invType.contains("CRAFTING")) return;

        int sessionId = activeSessions.get(uuid);
        String actionSuffix = ItemUtils.getActionSuffix(p);

        ItemStack cursor = p.getItemOnCursor();
        if (cursor != null && cursor.getType() != Material.AIR
                && !plugin.getConfigManager().isIgnored(cursor.getType())
                && !ItemUtils.hasCreativeTag(cursor)
                && !wasRecentlyAudited(p, cursor)) {
            plugin.getLogger().info("[TRACE:CLOSE-CURSOR] auditando " + cursor.getType() + " x" + cursor.getAmount());
            plugin.getLogger().info("[CloseCursor-DEBUG] " + p.getName()
                    + " cursor=" + cursor.getType() + " x" + cursor.getAmount());
            ItemUtils.tagCreativeItem(cursor);
            addSessionItem(sessionId, cursor, "pickup_closecursor" + actionSuffix, false);
            plugin.getDiscordWebhook().send("item_take",
                    p.getName() + " pegou " + cursor.getType().toString() + " x" + cursor.getAmount());
            checkHighValue(p, cursor);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        if (!p.hasPermission("stafflog.staff")) return;
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) return;
        UUID uuid = p.getUniqueId();
        if (!activeSessions.containsKey(uuid)) return;

        String invType = event.getInventory().getType().name();
        if (!invType.contains("CREATIVE") && !invType.contains("CRAFTING")) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> scanPlayerInventory(p));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCreativeClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("stafflog.staff")) return;
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) return;
        if (event instanceof InventoryCreativeEvent) return;
        if (!event.getClick().isCreativeAction()) return;

        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;

        UUID uuid = p.getUniqueId();
        if (!activeSessions.containsKey(uuid)) return;

        int sessionId = activeSessions.get(uuid);
        String actionSuffix = ItemUtils.getActionSuffix(p);

        ItemUtils.tagCreativeItem(cursor);
        String action = "pickup" + actionSuffix;
        addSessionItem(sessionId, cursor, action, false);
        checkHighValue(p, cursor);
    }

    private static final Set<String> ITEM_COMMANDS = Set.of(
            "/give", "/item", "/i", "/minecraft:give", "/minecraft:item",
            "/kit", "/essentials:give", "/essentials:item", "/essentials:i", "/essentials:kit",
            "/cmi give", "/cmi item", "/cmi i", "/cmi kit"
    );

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("stafflog.staff")) return;
        if (p.hasPermission("stafflog.bypass")) return;

        UUID uuid = p.getUniqueId();
        if (!activeSessions.containsKey(uuid)) return;

        String msg = event.getMessage().toLowerCase().trim();
        boolean isItemCmd = ITEM_COMMANDS.stream().anyMatch(cmd ->
                msg.equals(cmd) || msg.startsWith(cmd + " "));
        if (!isItemCmd) return;

        int sessionId = activeSessions.get(uuid);
        String actionSuffix = ItemUtils.getActionSuffix(p);
        String commandAction = "command:" + event.getMessage() + actionSuffix;

        plugin.getDatabaseManager().addSessionItem(new SessionItem(
                sessionId, msg.split(" ")[0].replace("/", "").toUpperCase(),
                1, "cmd-" + System.currentTimeMillis(), commandAction, System.currentTimeMillis(), false));
        plugin.getLogger().info("[CommandLog] " + p.getName() + " executou: " + event.getMessage()
                + " (session #" + sessionId + ")");
    }

    private void addSessionItem(int sessionId, ItemStack item, String action, boolean blocked) {
        plugin.getDatabaseManager().addSessionItem(new SessionItem(
                sessionId, item.getType().toString(), item.getAmount(),
                ItemUtils.getSHA256(item), action, System.currentTimeMillis(), blocked));
        plugin.getLogger().info("Logged: " + item.getType() + " x" + item.getAmount() +
                " (" + action + ") in session #" + sessionId);
    }

    private void checkHighValue(Player p, ItemStack taken) {
        if (!plugin.getConfigManager().isHighValue(taken.getType())) return;
        Material mat = taken.getType();
        int threshold = plugin.getConfigManager().getHighValueThreshold(mat);
        if (taken.getAmount() < threshold) return;

        String alert = "**ALERTA** " + p.getName() + " pegou item de alto valor: "
                + mat + " x" + taken.getAmount();
        p.sendMessage("§e[CreativeLogger] Item de alto valor registrado: " + mat);
        plugin.getDiscordWebhook().send("high_value_alert", alert);

        for (Player admin : plugin.getServer().getOnlinePlayers()) {
            if (admin.hasPermission("stafflog.admin") && !admin.equals(p)) {
                admin.sendMessage("§c[CreativeLogger] §e" + p.getName() + " pegou item de alto valor: " + mat + " x" + taken.getAmount());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        ItemStack item = event.getItemDrop().getItemStack();
        if (ItemUtils.hasCreativeTag(item) && !p.hasPermission("stafflog.bypass")) {
            event.setCancelled(true);
            p.sendMessage("§c[CreativeLogger] Você não pode dropar itens de origem criativa.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        ItemStack item = p.getInventory().getItem(event.getHand());
        if (item != null && ItemUtils.hasCreativeTag(item) && !p.hasPermission("stafflog.bypass")) {
            String entityType = event.getRightClicked().getType().toString();
            if (entityType.contains("ARMOR_STAND") || entityType.contains("ITEM_FRAME")
                    || entityType.contains("PLAYER")) {
                event.setCancelled(true);
                p.sendMessage("§c[CreativeLogger] Você não pode usar itens de origem criativa nessa entidade.");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        ItemStack current = event.getCurrentItem();
        if (current != null && ItemUtils.hasCreativeTag(current) && !p.hasPermission("stafflog.bypass")) {
            InventoryType topType = event.getView().getTopInventory().getType();
            if (isContainer(topType) && isMovingToContainer(event)) {
                event.setCancelled(true);
                p.sendMessage("§c[CreativeLogger] Você não pode colocar itens de origem criativa em contêineres.");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (p.hasPermission("stafflog.bypass")) return;

        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            if (ItemUtils.hasCreativeTag(entry.getValue())) {
                int slot = entry.getKey();
                if (slot < event.getView().getTopInventory().getSize()) {
                    InventoryType topType = event.getView().getTopInventory().getType();
                    if (isContainer(topType)) {
                        event.setCancelled(true);
                        p.sendMessage("§c[CreativeLogger] Você não pode colocar itens de origem criativa em contêineres.");
                        return;
                    }
                }
            }
        }
    }

    private boolean isContainer(InventoryType type) {
        return type == InventoryType.CHEST || type == InventoryType.BARREL
                || type == InventoryType.FURNACE || type == InventoryType.BLAST_FURNACE
                || type == InventoryType.SMOKER || type == InventoryType.HOPPER
                || type == InventoryType.DISPENSER || type == InventoryType.DROPPER
                || type == InventoryType.SHULKER_BOX || type == InventoryType.BREWING
                || type == InventoryType.ENDER_CHEST;
    }

    private boolean isMovingToContainer(InventoryClickEvent event) {
        InventoryAction action = event.getAction();
        return action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.PLACE_ALL
                || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME
                || action == InventoryAction.SWAP_WITH_CURSOR;
    }

    public void startSession(Player p) {
        UUID uuid = p.getUniqueId();
        if (activeSessions.containsKey(uuid)) return;
        if (quitting.contains(uuid)) return;

        DatabaseManager db = plugin.getDatabaseManager();

        // Se a sessão terminou há < 3s (ex: /mv tp), reutiliza a última sessão
        Long lastEnd = lastSessionEndTime.remove(uuid);
        if (lastEnd != null && System.currentTimeMillis() - lastEnd < SESSION_REUSE_COOLDOWN) {
            List<Session> recentSessions = db.getSessionsByPlayer(uuid);
            if (!recentSessions.isEmpty()) {
                Session lastSession = recentSessions.get(0);
                int sessionId = lastSession.getId();
                db.reactivateSession(sessionId);
                activeSessions.put(uuid, sessionId);
                sessionJoinMap.put(uuid, System.currentTimeMillis());
                plugin.getLogger().info("Reusing last session #" + sessionId + " for " + p.getName() + " (rapid restart)");
                p.sendMessage("§7[CreativeLogger] Sessão retomada: §f#" + sessionId);
                return;
            }
        }

        Session existing = db.getActiveSession(uuid);
        if (existing != null) {
            plugin.getLogger().info("Reusing DB session #" + existing.getId() + " for " + p.getName());
            activeSessions.put(uuid, existing.getId());
            sessionJoinMap.put(uuid, System.currentTimeMillis());
            p.sendMessage("§7[CreativeLogger] Sessão retomada: §f#" + existing.getId());
            return;
        }

        if (plugin.getGeyserChecker() != null) {
            plugin.getGeyserChecker().checkBedrock(p);
        }

        Session session = new Session(uuid, p.getName(), System.currentTimeMillis());
        int sessionId = db.createSession(session);
        if (sessionId < 0) return;

        activeSessions.put(uuid, sessionId);
        sessionJoinMap.put(uuid, System.currentTimeMillis());

        plugin.getLogger().info("Nova sessão #" + sessionId + " criada para " + p.getName());
        p.sendMessage("§7[CreativeLogger] Sessão iniciada: §f#" + sessionId);
        plugin.getDiscordWebhook().send("creative_enter", p.getName() + " entrou no criativo (sessão #" + sessionId + ")");
    }

    public void endSession(Player p, String reason) {
        UUID uuid = p.getUniqueId();
        if (!activeSessions.containsKey(uuid)) return;

        scanPlayerInventory(p);

        DatabaseManager db = plugin.getDatabaseManager();
        int sessionId = activeSessions.get(uuid);
        long endTime = System.currentTimeMillis();
        long startTime = sessionJoinMap.getOrDefault(uuid, endTime);
        long duration = endTime - startTime;

        List<SessionItem> items = db.getSessionItems(sessionId);
        int itemCount = items.size();

        List<SuspicionScore.SessionItemSummary> summaries = items.stream()
                .map(i -> new SuspicionScore.SessionItemSummary(i.getMaterial(), i.getAction()))
                .collect(Collectors.toList());

        Session session = db.getSessionById(sessionId);
        int totalSessions = db.getPlayerSessionCount(uuid);
        int score = SuspicionScore.calculate(session, summaries, totalSessions);

        db.endSession(sessionId, endTime, itemCount, score);

        activeSessions.remove(uuid);
        sessionJoinMap.remove(uuid);
        lastSessionEndTime.put(uuid, System.currentTimeMillis());

        String durationStr = String.format("%02d:%02d:%02d",
                duration / 3600000, (duration % 3600000) / 60000, (duration % 60000) / 1000);
        p.sendMessage("§7[CreativeLogger] Sessão #" + sessionId + " encerrada. " +
                "Itens: " + itemCount + ", Score: " + score + ", Duração: " + durationStr);
    }

    public void endSessionImmediately(UUID uuid) {
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null) endSession(p, "immediate");
    }

    public void scanPlayerInventory(Player p) {
        UUID uuid = p.getUniqueId();
        Integer sessionIdObj = activeSessions.get(uuid);
        if (sessionIdObj == null) return;
        int sessionId = sessionIdObj;
        String actionSuffix = ItemUtils.getActionSuffix(p);

        ItemStack cursor = p.getItemOnCursor();
        if (cursor != null && cursor.getType() != Material.AIR
                && !plugin.getConfigManager().isIgnored(cursor.getType())
                && !ItemUtils.hasCreativeTag(cursor)
                && !wasRecentlyAudited(p, cursor)) {
            plugin.getLogger().info("[TRACE:SCAN-CURSOR] auditando " + cursor.getType() + " x" + cursor.getAmount());
            plugin.getLogger().info("[Scanner-DEBUG] " + p.getName()
                    + " cursor=" + cursor.getType() + " x" + cursor.getAmount());
            ItemUtils.tagCreativeItem(cursor);
            addSessionItem(sessionId, cursor, "pickup" + actionSuffix, false);
            plugin.getDiscordWebhook().send("item_take",
                    p.getName() + " pegou " + cursor.getType().toString() + " x" + cursor.getAmount());
            checkHighValue(p, cursor);
        }

        for (int i = 0; i <= 40; i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;
            if (plugin.getConfigManager().isIgnored(item.getType())) continue;
            if (ItemUtils.hasCreativeTag(item)) continue;
            if (wasRecentlyAudited(p, item)) continue;

            plugin.getLogger().info("[Scanner-DEBUG] " + p.getName()
                    + " slot=" + i + " item=" + item.getType() + " x" + item.getAmount());
            ItemUtils.tagCreativeItem(item);
            addSessionItem(sessionId, item, "pickup" + actionSuffix, false);
            plugin.getDiscordWebhook().send("item_take",
                    p.getName() + " pegou " + item.getType().toString() + " x" + item.getAmount());
            checkHighValue(p, item);
        }
    }

    public void startInventoryScanner() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (!p.hasPermission("stafflog.staff")) continue;
                if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) continue;
                scanPlayerInventory(p);
            }
        }, 40L, 20L);
    }

    public boolean hasActiveSession(UUID uuid) {
        return activeSessions.containsKey(uuid);
    }

    public int getActiveSessionId(UUID uuid) {
        return activeSessions.getOrDefault(uuid, -1);
    }
}
