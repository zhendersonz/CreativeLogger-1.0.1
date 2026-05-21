package com.creativelogger.utils;

import org.bukkit.entity.Player;
import java.util.UUID;

public class GeyserChecker {
    private boolean geyserEnabled;

    public GeyserChecker() {
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            geyserEnabled = true;
        } catch (ClassNotFoundException e) {
            geyserEnabled = false;
        }
    }

    public void checkBedrock(Player player) {
        if (!geyserEnabled) return;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object playerData = apiClass.getMethod("getPlayer", UUID.class)
                    .invoke(api, player.getUniqueId());
            if (playerData != null) {
                String username = (String) playerData.getClass().getMethod("getUsername").invoke(playerData);
                if (username != null && username.startsWith(".")) {
                    player.setDisplayName(username.substring(1));
                    player.setPlayerListName(username.substring(1));
                }
            }
        } catch (Exception ignored) {}
    }

    public boolean isGeyserEnabled() { return geyserEnabled; }
}
