package com.creativelogger.commands;

import com.creativelogger.CreativeLogger;
import com.creativelogger.database.DatabaseManager;
import com.creativelogger.gui.GUI;
import com.creativelogger.gui.SessionViewGUI;
import com.creativelogger.models.Session;
import com.creativelogger.models.SessionItem;
import com.creativelogger.utils.ItemUtils;
import com.creativelogger.utils.LockdownManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class StaffLogCommand implements CommandExecutor, TabCompleter {
    private final CreativeLogger plugin;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
    private final Set<UUID> watchingPlayers = new HashSet<>();

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "gui", "sessao", "toggle", "rollback", "note",
            "watch", "freeze", "scan", "verify", "whitelist",
            "report", "export", "cleanup", "reload", "lockdown"
    );

    public StaffLogCommand(CreativeLogger plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "gui" -> handleGUI(sender, args);
            case "sessao" -> handleSessao(sender, args);
            case "toggle" -> handleToggle(sender, args);
            case "rollback" -> handleRollback(sender, args);
            case "note" -> handleNote(sender, args);
            case "watch" -> handleWatch(sender, args);
            case "freeze" -> handleFreeze(sender, args);
            case "scan" -> handleScan(sender, args);
            case "verify" -> handleVerify(sender, args);
            case "whitelist" -> handleWhitelist(sender, args);
            case "report" -> handleReport(sender, args);
            case "export" -> handleExport(sender, args);
            case "cleanup" -> handleCleanup(sender, args);
            case "reload" -> handleReload(sender);
            case "lockdown" -> handleLockdown(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    // ─── GUI ───
    private void handleGUI(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cApenas jogadores podem usar este comando.");
            return;
        }
        if (!p.hasPermission("stafflog.admin")) {
            p.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length >= 2) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                p.sendMessage("§cJogador não encontrado.");
                return;
            }
            plugin.getGUI().openPlayerSessions(p, target.getUniqueId(), target.getName());
        } else {
            plugin.getGUI().openStaffList(p);
        }
    }

    // ─── SESSAO ───
    private void handleSessao(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUse: /stafflog sessao <id>");
            return;
        }
        try {
            int id = Integer.parseInt(args[1]);
            Session session = plugin.getDatabaseManager().getSessionById(id);
            if (session == null) {
                sender.sendMessage("§cSessão #" + id + " não encontrada.");
                return;
            }
            sender.sendMessage("§6=== Sessão #" + id + " ===");
            sender.sendMessage("§7Jogador: §f" + session.getPlayerName());
            sender.sendMessage("§7Início: §f" + dateFormat.format(new Date(session.getStartTime())));
            if (session.getEndTime() > 0) {
                sender.sendMessage("§7Fim: §f" + dateFormat.format(new Date(session.getEndTime())));
            }
            sender.sendMessage("§7Duração: §f" + session.getFormattedDuration());
            sender.sendMessage("§7Itens: §f" + session.getItemCount());
            sender.sendMessage("§7Score: §f" + session.getSuspicionScore());
            sender.sendMessage("§7Status: " + (session.isActive() ? "§aAtiva" : "§7Encerrada"));
            if (session.getNotes() != null && !session.getNotes().isEmpty()) {
                sender.sendMessage("§6Notas:");
                for (String note : session.getNotes()) {
                    sender.sendMessage("§7- " + note);
                }
            }

            if (sender instanceof Player p) {
                plugin.getSessionViewGUI().open(p, id);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cID inválido.");
        }
    }

    // ─── TOGGLE ───
    private void handleToggle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUse: /stafflog toggle <jogador>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cJogador não encontrado.");
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        boolean currentlyBlocked = db.isPlayerBlocked(target.getUniqueId());
        db.setPlayerBlocked(target.getUniqueId(), target.getName(), !currentlyBlocked);
        sender.sendMessage("§a" + target.getName() + " agora está " + (!currentlyBlocked ? "§cBLOQUEADO" : "§aLIBERADO") + " §apara pegar itens do criativo.");
        target.sendMessage("§e[CreativeLogger] Você foi " + (!currentlyBlocked ? "§cBLOQUEADO" : "§aLIBERADO") + " §epara pegar itens do criativo.");
    }

    // ─── ROLLBACK ───
    private void handleRollback(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUse: /stafflog rollback <jogador> <id>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cJogador não encontrado.");
            return;
        }
        try {
            int sessionId = Integer.parseInt(args[2]);
            Session session = plugin.getDatabaseManager().getSessionById(sessionId);
            if (session == null) {
                sender.sendMessage("§cSessão não encontrada.");
                return;
            }
            List<SessionItem> items = plugin.getDatabaseManager().getSessionItems(sessionId);
            int removed = 0;
            for (SessionItem si : items) {
                try {
                    Material mat = Material.valueOf(si.getMaterial());
                    ItemStack toRemove = new ItemStack(mat, si.getAmount());
                    target.getInventory().removeItem(toRemove);
                    removed++;
                } catch (IllegalArgumentException ignored) {}
            }
            sender.sendMessage("§aRollback concluído. " + removed + " tipos de itens removidos de " + target.getName() + ".");
            target.sendMessage("§c[CreativeLogger] Rollback aplicado na sessão #" + sessionId + " por " + sender.getName());
        } catch (NumberFormatException e) {
            sender.sendMessage("§cID inválido.");
        }
    }

    // ─── NOTE ───
    private void handleNote(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUse: /stafflog note <id> <texto>");
            return;
        }
        try {
            int id = Integer.parseInt(args[1]);
            String noteText = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            plugin.getDatabaseManager().addNote(id, noteText, sender.getName());
            sender.sendMessage("§aNota adicionada à sessão #" + id + ".");
        } catch (NumberFormatException e) {
            sender.sendMessage("§cID inválido.");
        }
    }

    // ─── WATCH ───
    private void handleWatch(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cApenas jogadores podem usar este comando.");
            return;
        }
        if (!p.hasPermission("stafflog.admin")) {
            p.sendMessage("§cSem permissão.");
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("off")) {
            watchingPlayers.remove(p.getUniqueId());
            p.sendMessage("§cMonitoramento em tempo real desativado.");
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("on")) {
            watchingPlayers.add(p.getUniqueId());
            p.sendMessage("§aMonitoramento em tempo real ativado.");
        } else {
            if (watchingPlayers.contains(p.getUniqueId())) {
                watchingPlayers.remove(p.getUniqueId());
                p.sendMessage("§cMonitoramento desativado.");
            } else {
                watchingPlayers.add(p.getUniqueId());
                p.sendMessage("§aMonitoramento ativado.");
            }
        }
    }

    public void notifyWatchers(String message) {
        for (UUID uuid : watchingPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage("§7[Watch] " + message);
            }
        }
    }

    // ─── FREEZE ───
    private void handleFreeze(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUse: /stafflog freeze <jogador>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cJogador não encontrado.");
            return;
        }
        if (plugin.getFrozenPlayers().contains(target.getUniqueId())) {
            plugin.getFrozenPlayers().remove(target.getUniqueId());
            target.setWalkSpeed(0.2f);
            target.setFlySpeed(0.1f);
            target.setAllowFlight(false);
            sender.sendMessage("§a" + target.getName() + " descongelado.");
            target.sendMessage("§e[CreativeLogger] Você foi descongelado.");
        } else {
            plugin.getFrozenPlayers().add(target.getUniqueId());
            target.setWalkSpeed(0.0f);
            target.setFlySpeed(0.0f);
            target.setAllowFlight(true);
            target.setFlying(true);
            sender.sendMessage("§c" + target.getName() + " congelado.");
            target.sendMessage("§c[CreativeLogger] VOCÊ FOI CONGELADO!");
        }
    }

    // ─── SCAN ───
    private void handleScan(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUse: /stafflog scan <jogador>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cJogador não encontrado.");
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        List<Session> sessions = db.getSessionsByPlayer(target.getUniqueId());

        Set<String> registeredHashes = sessions.stream()
                .flatMap(s -> db.getSessionItems(s.getId()).stream())
                .map(SessionItem::getHash)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<String> unregistered = new ArrayList<>();
        for (ItemStack item : target.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                String hash = ItemUtils.getSHA256(item);
                if (!registeredHashes.contains(hash)) {
                    unregistered.add(item.getType().toString() + " x" + item.getAmount());
                }
            }
        }

        sender.sendMessage("§6=== Scan - " + target.getName() + " ===");
        sender.sendMessage("§7Sessões: §f" + sessions.size());
        sender.sendMessage("§7Itens no inventário não registrados: " + unregistered.size());
        for (String item : unregistered) {
            sender.sendMessage("  §c- " + item);
        }
        if (unregistered.isEmpty()) {
            sender.sendMessage("§aTodos os itens estão registrados.");
        }
    }

    // ─── VERIFY ───
    private void handleVerify(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cApenas jogadores podem usar este comando.");
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            p.sendMessage("§cSegure um item na mão.");
            return;
        }
        String hash = ItemUtils.getSHA256(item);
        boolean tagged = ItemUtils.hasCreativeTag(item);
        p.sendMessage("§6=== Verificação de Item ===");
        p.sendMessage("§7Material: §f" + item.getType());
        p.sendMessage("§7Quantidade: §f" + item.getAmount());
        p.sendMessage("§7Hash SHA-256: §f" + hash);
        p.sendMessage("§7Origem criativa: " + (tagged ? "§aSim" : "§cNão"));
    }

    // ─── WHITELIST ───
    private void handleWhitelist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUse: /stafflog whitelist add|remove|list <jogador> [material|all]");
            return;
        }
        String action = args[1].toLowerCase();
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage("§cJogador não encontrado.");
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();

        switch (action) {
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUse: /stafflog whitelist add <jogador> <material|all>");
                    return;
                }
                String material = args[3].toUpperCase();
                db.addWhitelist(target.getUniqueId(), material);
                sender.sendMessage("§a" + material + " adicionado à whitelist de " + target.getName() + ".");
            }
            case "remove" -> {
                String material = args.length >= 4 ? args[3].toUpperCase() : "ALL";
                db.removeWhitelist(target.getUniqueId(), material);
                sender.sendMessage("§aWhitelist atualizada para " + target.getName() + ".");
            }
            case "list" -> {
                Map<String, List<String>> all = db.getFullWhitelist();
                List<String> playerEntries = all.get(target.getUniqueId().toString());
                sender.sendMessage("§6=== Whitelist - " + target.getName() + " ===");
                if (playerEntries == null || playerEntries.isEmpty()) {
                    sender.sendMessage("§7Nenhum item na whitelist.");
                } else {
                    for (String entry : playerEntries) {
                        sender.sendMessage("§a- " + entry);
                    }
                }
            }
            default -> sender.sendMessage("§cAção inválida. Use: add, remove ou list.");
        }
    }

    // ─── REPORT ───
    private void handleReport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUse: /stafflog report <jogador> [dias]");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cJogador não encontrado.");
            return;
        }
        int days = args.length >= 3 ? Integer.parseInt(args[2]) : 7;
        long cutoff = System.currentTimeMillis() - (days * 86400000L);

        DatabaseManager db = plugin.getDatabaseManager();
        List<Session> sessions = db.getSessionsByPlayer(target.getUniqueId()).stream()
                .filter(s -> s.getStartTime() >= cutoff)
                .collect(Collectors.toList());

        File reportDir = new File(plugin.getDataFolder(), "reports");
        if (!reportDir.exists()) reportDir.mkdirs();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File reportFile = new File(reportDir, "report_" + target.getName() + "_" + timestamp + ".json");

        try (PrintWriter pw = new PrintWriter(new FileWriter(reportFile))) {
            pw.println("{");
            pw.println("  \"player\": \"" + target.getName() + "\",");
            pw.println("  \"period_days\": " + days + ",");
            pw.println("  \"total_sessions\": " + sessions.size() + ",");
            pw.println("  \"sessions\": [");
            for (int i = 0; i < sessions.size(); i++) {
                Session s = sessions.get(i);
                pw.println("    {");
                pw.println("      \"id\": " + s.getId() + ",");
                pw.println("      \"start\": \"" + new Date(s.getStartTime()) + "\",");
                pw.println("      \"duration\": \"" + s.getFormattedDuration() + "\",");
                pw.println("      \"items\": " + s.getItemCount() + ",");
                pw.println("      \"score\": " + s.getSuspicionScore());
                pw.println("    }" + (i < sessions.size() - 1 ? "," : ""));
            }
            pw.println("  ]");
            pw.println("}");
            sender.sendMessage("§aRelatório salvo em: " + reportFile.getName());
        } catch (Exception e) {
            sender.sendMessage("§cErro ao gerar relatório: " + e.getMessage());
        }
    }

    // ─── EXPORT ───
    private void handleExport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUse: /stafflog export <jogador> [dias]");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cJogador não encontrado.");
            return;
        }
        int days = args.length >= 3 ? Integer.parseInt(args[2]) : 7;
        long cutoff = System.currentTimeMillis() - (days * 86400000L);

        DatabaseManager db = plugin.getDatabaseManager();
        List<Session> sessions = db.getSessionsByPlayer(target.getUniqueId()).stream()
                .filter(s -> s.getStartTime() >= cutoff)
                .collect(Collectors.toList());

        File exportDir = new File(plugin.getDataFolder(), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File exportFile = new File(exportDir, "export_" + target.getName() + "_" + timestamp + ".csv");

        try (PrintWriter pw = new PrintWriter(new FileWriter(exportFile))) {
            pw.println("session_id,start_time,end_time,duration,item_count,score,material,amount,action,blocked");
            for (Session s : sessions) {
                List<SessionItem> items = db.getSessionItems(s.getId());
                if (items.isEmpty()) {
                    pw.printf("%d,%s,%s,%s,%d,%d,%s,%s,%s,%s%n",
                            s.getId(), new Date(s.getStartTime()), new Date(s.getEndTime()),
                            s.getFormattedDuration(), s.getItemCount(), s.getSuspicionScore(),
                            "NONE", 0, "NONE", "false");
                } else {
                    for (SessionItem si : items) {
                        pw.printf("%d,%s,%s,%s,%d,%d,%s,%d,%s,%s%n",
                                s.getId(), new Date(s.getStartTime()), new Date(s.getEndTime()),
                                s.getFormattedDuration(), s.getItemCount(), s.getSuspicionScore(),
                                si.getMaterial(), si.getAmount(), si.getAction(), si.isBlocked());
                    }
                }
            }
            sender.sendMessage("§aExportação salva em: " + exportFile.getName());
        } catch (Exception e) {
            sender.sendMessage("§cErro ao exportar: " + e.getMessage());
        }
    }

    // ─── CLEANUP ───
    private void handleCleanup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        int days = args.length >= 2 ? Integer.parseInt(args[1]) : 30;
        int count = plugin.getDatabaseManager().cleanupOldSessions(days);
        sender.sendMessage("§a" + count + " sessões antigas (> " + days + " dias) removidas.");
    }

    // ─── RELOAD ───
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        plugin.getConfigManager().reload();
        plugin.getRateLimiter().resetRateLimit(plugin.getConfigManager().getRateLimit());
        sender.sendMessage("§aConfiguração recarregada.");
    }

    // ─── LOCKDOWN ───
    private void handleLockdown(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafflog.admin")) {
            sender.sendMessage("§cSem permissão.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            plugin.getLockdownManager().clearLockdown();
            sender.sendMessage("§aLockdown desativado.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("stafflog.staff")) {
                    p.sendMessage("§e[CreativeLogger] Lockdown foi desativado por " + sender.getName());
                }
            }
        } else {
            sender.sendMessage("§cUse: /stafflog lockdown clear");
        }
    }

    // ─── HELP ───
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== CreativeLogger - Ajuda ===");
        sender.sendMessage("§7/stafflog gui [jogador] §8- GUI com lista de staffs/sessões/itens");
        sender.sendMessage("§7/stafflog sessao <id> §8- Chat com detalhes da sessão");
        sender.sendMessage("§7/stafflog toggle <jogador> §8- Bloqueia/libera pegar itens do criativo");
        sender.sendMessage("§7/stafflog rollback <jogador> <id> §8- Remove itens da sessão do inventário");
        sender.sendMessage("§7/stafflog note <id> <texto> §8- Anota sessão");
        sender.sendMessage("§7/stafflog watch [on/off] §8- Monitora ações em tempo real");
        sender.sendMessage("§7/stafflog freeze <jogador> §8- Congela/descongela jogador");
        sender.sendMessage("§7/stafflog scan <jogador> §8- Escaneia inventário vs registros");
        sender.sendMessage("§7/stafflog verify §8- Mostra hash SHA-256 do item na mão");
        sender.sendMessage("§7/stafflog whitelist add|remove|list <jogador> [material|all]");
        sender.sendMessage("§7/stafflog report <jogador> [dias] §8- Relatório JSON");
        sender.sendMessage("§7/stafflog export <jogador> [dias] §8- Exporta CSV");
        sender.sendMessage("§7/stafflog cleanup [dias] §8- Limpa sessões antigas");
        sender.sendMessage("§7/stafflog reload §8- Recarrega config.yml");
        sender.sendMessage("§7/stafflog lockdown clear §8- Desativa lockdown");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            return switch (sub) {
                case "gui", "toggle", "rollback", "freeze", "scan", "report", "export" ->
                        Bukkit.getOnlinePlayers().stream().map(Player::getName)
                                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                case "whitelist" -> Arrays.asList("add", "remove", "list");
                case "watch" -> Arrays.asList("on", "off");
                case "lockdown" -> Collections.singletonList("clear");
                default -> Collections.emptyList();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("whitelist")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("whitelist")
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            List<String> materials = Arrays.asList("ALL", "COMMAND_BLOCK", "BARRIER", "BEDROCK");
            return materials.stream()
                    .filter(m -> m.toLowerCase().startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
