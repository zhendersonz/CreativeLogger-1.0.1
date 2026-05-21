package com.creativelogger.config;

import com.creativelogger.CreativeLogger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ConfigManager {
    private final CreativeLogger plugin;
    private FileConfiguration config;
    private Set<Material> blockedItems;
    private Map<Material, Integer> highValueItems;
    private int rateLimit;
    private boolean discordEnabled;
    private String webhookUrl;
    private String alertsWebhookUrl;
    private Set<String> discordNotifications;
    private boolean lockdownEnabled;
    private int backupIntervalHours;
    private Set<Material> ignoredItems;

    public ConfigManager(CreativeLogger plugin) {
        this.plugin = plugin;
        this.blockedItems = new HashSet<>();
        this.highValueItems = new HashMap<>();
        this.discordNotifications = new HashSet<>();
        this.ignoredItems = new HashSet<>();
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        blockedItems.clear();
        for (String s : config.getStringList("blocked_items")) {
            try {
                Material m = Material.valueOf(s.toUpperCase());
                blockedItems.add(m);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid blocked item: " + s);
            }
        }

        highValueItems.clear();
        ConfigurationSection hvi = config.getConfigurationSection("high_value_items");
        if (hvi != null) {
            for (String key : hvi.getKeys(false)) {
                try {
                    Material m = Material.valueOf(key.toUpperCase());
                    highValueItems.put(m, hvi.getInt(key));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid high value item: " + key);
                }
            }
        }

        rateLimit = config.getInt("rate_limit.items_per_minute", 64);
        discordEnabled = config.getBoolean("discord.enabled", false);
        webhookUrl = config.getString("discord.webhook_url", "");
        alertsWebhookUrl = config.getString("discord.alerts_webhook_url", "");

        ConfigurationSection notif = config.getConfigurationSection("discord.notifications");
        if (notif != null) {
            for (String key : notif.getKeys(false)) {
                if (notif.getBoolean(key)) discordNotifications.add(key);
            }
        }

        lockdownEnabled = config.getBoolean("lockdown.enabled", true);
        backupIntervalHours = config.getInt("database.backup_interval_hours", 24);

        ignoredItems.clear();
        for (String s : config.getStringList("ignored_items")) {
            try {
                Material m = Material.valueOf(s.toUpperCase());
                ignoredItems.add(m);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public boolean isBlocked(Material material) { return blockedItems.contains(material); }
    public Set<Material> getBlockedItems() { return blockedItems; }
    public boolean isHighValue(Material material) { return highValueItems.containsKey(material); }
    public int getHighValueThreshold(Material material) { return highValueItems.getOrDefault(material, 0); }
    public Map<Material, Integer> getHighValueItems() { return highValueItems; }
    public int getRateLimit() { return rateLimit; }
    public boolean isDiscordEnabled() { return discordEnabled; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getAlertsWebhookUrl() { return alertsWebhookUrl; }
    public boolean hasDiscordNotification(String key) { return discordNotifications.contains(key); }
    public boolean isLockdownEnabled() { return lockdownEnabled; }
    public int getBackupIntervalHours() { return backupIntervalHours; }
    public boolean isIgnored(Material material) { return ignoredItems.contains(material); }
}
