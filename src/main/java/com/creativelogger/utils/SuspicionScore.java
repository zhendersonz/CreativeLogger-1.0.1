package com.creativelogger.utils;

import com.creativelogger.CreativeLogger;
import com.creativelogger.models.Session;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public class SuspicionScore {

    public static int calculate(Session session, List<SessionItemSummary> items, int totalSessions) {
        int score = 0;
        long duration = session.getDuration();
        long durationMinutes = duration / 60000;
        int itemCount = items.size();

        if (durationMinutes < 5 && itemCount > 10) score += 30;
        if (itemCount >= 20) score += 20;
        if (itemCount > 50) score += 40;

        CreativeLogger plugin = CreativeLogger.getInstance();
        Map<Material, Integer> highValue = plugin.getConfigManager().getHighValueItems();
        for (SessionItemSummary sis : items) {
            try {
                Material m = Material.valueOf(sis.material);
                if (highValue.containsKey(m)) score += 30;
            } catch (IllegalArgumentException ignored) {}
        }

        boolean hasVanishActions = items.stream().anyMatch(i -> i.action != null && i.action.contains("vanish"));
        if (hasVanishActions) score += 15;

        long hourOfDay = (session.getStartTime() / 3600000) % 24;
        if (hourOfDay >= 0 && hourOfDay < 6) score += 10;

        if (totalSessions > 5) score = Math.max(0, score - (totalSessions / 5) * 5);

        return Math.min(100, Math.max(0, score));
    }

    public static class SessionItemSummary {
        public String material;
        public String action;

        public SessionItemSummary(String material, String action) {
            this.material = material;
            this.action = action;
        }
    }
}
