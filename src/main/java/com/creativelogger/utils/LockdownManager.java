package com.creativelogger.utils;

import com.creativelogger.CreativeLogger;
import org.bukkit.entity.Player;

import java.io.*;
import java.util.Properties;

public class LockdownManager {
    private final CreativeLogger plugin;
    private boolean lockdownActive;
    private final File lockdownFile;

    public LockdownManager(CreativeLogger plugin) {
        this.plugin = plugin;
        this.lockdownFile = new File(plugin.getDataFolder(), "lockdown.properties");
    }

    public void checkLockdown() {
        if (!plugin.getConfigManager().isLockdownEnabled()) return;
        if (lockdownFile.exists()) {
            try (InputStream is = new FileInputStream(lockdownFile)) {
                Properties props = new Properties();
                props.load(is);
                if ("true".equals(props.getProperty("lockdown"))) {
                    lockdownActive = true;
                    plugin.getLogger().warning("LOCKDOWN ATIVO - Servidor recuperado de crash anterior!");
                    plugin.getLogger().warning("Use /stafflog lockdown clear para desativar.");
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        if (p.hasPermission("stafflog.staff")) {
                            p.sendMessage("§c[CreativeLogger] LOCKDOWN ATIVO - Plugin recuperou de crash!");
                            p.sendMessage("§cUse /stafflog lockdown clear para desativar.");
                        }
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not read lockdown file: " + e.getMessage());
            }
        }
    }

    public void saveLockdown() {
        if (!plugin.getConfigManager().isLockdownEnabled()) return;
        lockdownActive = true;
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            try (OutputStream os = new FileOutputStream(lockdownFile)) {
                Properties props = new Properties();
                props.setProperty("lockdown", "true");
                props.store(os, "CreativeLogger Lockdown - Do not delete manually");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save lockdown: " + e.getMessage());
        }
    }

    public void clearLockdown() {
        lockdownActive = false;
        if (lockdownFile.exists()) lockdownFile.delete();
        plugin.getLogger().info("Lockdown desativado.");
    }

    public boolean isLockdownActive() { return lockdownActive; }

    public boolean isPlayerLocked(Player player) {
        if (!lockdownActive) return false;
        return player.hasPermission("stafflog.staff") && !player.hasPermission("stafflog.bypass");
    }
}
