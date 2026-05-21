package com.creativelogger.models;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Session {
    private int id;
    private UUID playerUuid;
    private String playerName;
    private long startTime;
    private long endTime;
    private int itemCount;
    private int suspicionScore;
    private List<String> notes;
    private boolean active;

    public Session(int id, UUID playerUuid, String playerName, long startTime, long endTime,
                   int itemCount, int suspicionScore, List<String> notes, boolean active) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.startTime = startTime;
        this.endTime = endTime;
        this.itemCount = itemCount;
        this.suspicionScore = suspicionScore;
        this.notes = notes != null ? notes : new ArrayList<>();
        this.active = active;
    }

    public Session(UUID playerUuid, String playerName, long startTime) {
        this(0, playerUuid, playerName, startTime, 0, 0, 0, new ArrayList<>(), true);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public int getSuspicionScore() { return suspicionScore; }
    public void setSuspicionScore(int suspicionScore) { this.suspicionScore = suspicionScore; }
    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public long getDuration() {
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    public String getFormattedDuration() {
        long dur = getDuration();
        long seconds = dur / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds %= 60;
        minutes %= 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
