package com.creativelogger.models;

public class ContainerLog {
    private int id;
    private int sessionId;
    private String playerName;
    private String material;
    private int amount;
    private String containerType;
    private String world;
    private int x;
    private int y;
    private int z;
    private long timestamp;

    public ContainerLog(int id, int sessionId, String playerName, String material, int amount,
                        String containerType, String world, int x, int y, int z, long timestamp) {
        this.id = id;
        this.sessionId = sessionId;
        this.playerName = playerName;
        this.material = material;
        this.amount = amount;
        this.containerType = containerType;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.timestamp = timestamp;
    }

    public int getId() { return id; }
    public int getSessionId() { return sessionId; }
    public String getPlayerName() { return playerName; }
    public String getMaterial() { return material; }
    public int getAmount() { return amount; }
    public String getContainerType() { return containerType; }
    public String getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public long getTimestamp() { return timestamp; }
}
