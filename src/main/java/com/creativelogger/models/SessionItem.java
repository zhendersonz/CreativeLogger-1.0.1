package com.creativelogger.models;

public class SessionItem {
    private int id;
    private int sessionId;
    private String material;
    private int amount;
    private String hash;
    private String action;
    private long timestamp;
    private boolean blocked;

    public SessionItem(int id, int sessionId, String material, int amount, String hash,
                       String action, long timestamp, boolean blocked) {
        this.id = id;
        this.sessionId = sessionId;
        this.material = material;
        this.amount = amount;
        this.hash = hash;
        this.action = action;
        this.timestamp = timestamp;
        this.blocked = blocked;
    }

    public SessionItem(int sessionId, String material, int amount, String hash,
                       String action, long timestamp, boolean blocked) {
        this(0, sessionId, material, amount, hash, action, timestamp, blocked);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getSessionId() { return sessionId; }
    public String getMaterial() { return material; }
    public int getAmount() { return amount; }
    public String getHash() { return hash; }
    public String getAction() { return action; }
    public long getTimestamp() { return timestamp; }
    public boolean isBlocked() { return blocked; }
}
