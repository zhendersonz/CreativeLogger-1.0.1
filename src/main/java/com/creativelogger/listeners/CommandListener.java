package com.creativelogger.listeners;

import com.creativelogger.CreativeLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CommandListener implements Listener {
    private final CreativeLogger plugin;
    private static final Set<String> BLOCKED_BASE_COMMANDS = new HashSet<>(Arrays.asList(
            "give", "i", "item", "kit",
            "stop", "reload", "plugman"
    ));
    private static final Set<String> BLOCKED_PREFIXES = new HashSet<>(Arrays.asList(
            "/give ", "/i ", "/item ", "/kit ",
            "/lp user", "/luckperms user",
            "/stop", "/reload",
            "/plugman reload"
    ));

    public CommandListener(CreativeLogger plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("stafflog.staff")) return;
        if (p.hasPermission("stafflog.bypass")) return;

        String msg = event.getMessage().toLowerCase().trim();

        String baseCommand = msg.split(" ")[0].replace("/", "");

        if (BLOCKED_BASE_COMMANDS.contains(baseCommand)) {
            cancelCommand(event, p, msg);
            return;
        }

        for (String prefix : BLOCKED_PREFIXES) {
            if (msg.startsWith(prefix)) {
                cancelCommand(event, p, msg);
                return;
            }
        }
    }

    private void cancelCommand(PlayerCommandPreprocessEvent event, Player p, String msg) {
        event.setCancelled(true);
        p.sendMessage("§c[CreativeLogger] Comando bloqueado para staffs monitorados.");

        String actionSuffix = com.creativelogger.utils.ItemUtils.getActionSuffix(p);
        plugin.getDiscordWebhook().send("command_use",
                p.getName() + " tentou usar comando bloqueado: " + msg);

        if (plugin.getStaffLogCommand() != null) {
            plugin.getStaffLogCommand().notifyWatchers(
                    p.getName() + " tentou: " + msg + actionSuffix);
        }
    }
}
