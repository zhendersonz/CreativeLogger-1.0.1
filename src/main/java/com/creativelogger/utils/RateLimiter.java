package com.creativelogger.utils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimiter {
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private int maxItemsPerMinute;

    public RateLimiter(int maxItemsPerMinute) {
        this.maxItemsPerMinute = maxItemsPerMinute;
    }

    public boolean tryConsume(UUID playerUuid) {
        Window window = windows.computeIfAbsent(playerUuid, k -> new Window());
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.startTime > 60000) {
                window.startTime = now;
                window.count.set(0);
            }
            if (window.count.get() >= maxItemsPerMinute) return false;
            window.count.incrementAndGet();
            return true;
        }
    }

    public void reset(UUID playerUuid) {
        windows.remove(playerUuid);
    }

    public void resetRateLimit(int newMax) {
        try {
            java.lang.reflect.Field field = RateLimiter.class.getDeclaredField("maxItemsPerMinute");
            field.setAccessible(true);
            field.set(this, newMax);
        } catch (Exception ignored) {}
        windows.clear();
    }

    public int getRemaining(UUID playerUuid) {
        Window window = windows.get(playerUuid);
        if (window == null) return maxItemsPerMinute;
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.startTime > 60000) return maxItemsPerMinute;
            return Math.max(0, maxItemsPerMinute - window.count.get());
        }
    }

    private static class Window {
        long startTime = System.currentTimeMillis();
        AtomicInteger count = new AtomicInteger(0);
    }
}
