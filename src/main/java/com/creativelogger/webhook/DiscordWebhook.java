package com.creativelogger.webhook;

import com.creativelogger.CreativeLogger;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class DiscordWebhook {
    private final CreativeLogger plugin;

    public DiscordWebhook(CreativeLogger plugin) {
        this.plugin = plugin;
    }

    public void send(String type, String message) {
        if (!plugin.getConfigManager().isDiscordEnabled()) return;
        if (!plugin.getConfigManager().hasDiscordNotification(type)) return;

        String url = type.equals("high_value_alert") || type.equals("blocked_alert")
                ? plugin.getConfigManager().getAlertsWebhookUrl()
                : plugin.getConfigManager().getWebhookUrl();

        if (url == null || url.isEmpty()) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String json = "{\"content\": \"" + escapeJson(message) + "\"}";
                URI uri = URI.create(url);
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = json.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
