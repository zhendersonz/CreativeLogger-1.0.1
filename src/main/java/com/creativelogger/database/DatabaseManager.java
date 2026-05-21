package com.creativelogger.database;

import com.creativelogger.CreativeLogger;
import com.creativelogger.models.ContainerLog;
import com.creativelogger.models.Session;
import com.creativelogger.models.SessionItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DatabaseManager {
    private final CreativeLogger plugin;
    private Connection connection;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long lastBackupTime = 0;

    public DatabaseManager(CreativeLogger plugin) {
        this.plugin = plugin;
    }

    public void init() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();
        File dbFile = new File(dataFolder, "data.db");
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
            startBackupTask();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS sessions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "player_uuid TEXT NOT NULL, " +
                    "player_name TEXT NOT NULL, " +
                    "start_time INTEGER NOT NULL, " +
                    "end_time INTEGER DEFAULT 0, " +
                    "item_count INTEGER DEFAULT 0, " +
                    "suspicion_score INTEGER DEFAULT 0, " +
                    "active INTEGER DEFAULT 1)");

            stmt.execute("CREATE TABLE IF NOT EXISTS session_items (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "session_id INTEGER NOT NULL, " +
                    "material TEXT NOT NULL, " +
                    "amount INTEGER NOT NULL, " +
                    "hash TEXT, " +
                    "action TEXT DEFAULT 'pickup', " +
                    "timestamp INTEGER NOT NULL, " +
                    "blocked INTEGER DEFAULT 0, " +
                    "FOREIGN KEY (session_id) REFERENCES sessions(id))");

            stmt.execute("CREATE TABLE IF NOT EXISTS container_logs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "session_id INTEGER NOT NULL, " +
                    "player_name TEXT NOT NULL, " +
                    "material TEXT NOT NULL, " +
                    "amount INTEGER NOT NULL, " +
                    "container_type TEXT NOT NULL, " +
                    "world TEXT NOT NULL, " +
                    "x INTEGER NOT NULL, " +
                    "y INTEGER NOT NULL, " +
                    "z INTEGER NOT NULL, " +
                    "timestamp INTEGER NOT NULL, " +
                    "FOREIGN KEY (session_id) REFERENCES sessions(id))");

            stmt.execute("CREATE TABLE IF NOT EXISTS blocked_players (" +
                    "player_uuid TEXT PRIMARY KEY, " +
                    "player_name TEXT NOT NULL, " +
                    "blocked_since INTEGER NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS session_notes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "session_id INTEGER NOT NULL, " +
                    "note_text TEXT NOT NULL, " +
                    "author TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, " +
                    "FOREIGN KEY (session_id) REFERENCES sessions(id))");

            stmt.execute("CREATE TABLE IF NOT EXISTS player_item_whitelist (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "player_uuid TEXT NOT NULL, " +
                    "material TEXT NOT NULL, " +
                    "UNIQUE(player_uuid, material))");
        }
    }

    private void startBackupTask() {
        int intervalHours = plugin.getConfigManager().getBackupIntervalHours();
        long intervalMs = intervalHours * 3600000L;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            if (now - lastBackupTime >= intervalMs) {
                backupDatabase();
                lastBackupTime = now;
            }
        }, intervalMs, 60000L);
    }

    public void backupDatabase() {
        lock.writeLock().lock();
        try {
            File dbFile = new File(plugin.getDataFolder(), "data.db");
            if (!dbFile.exists()) return;
            File backupDir = new File(plugin.getDataFolder(), "backups");
            if (!backupDir.exists()) backupDir.mkdirs();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File backupFile = new File(backupDir, "data_" + timestamp + ".db");
            Files.copy(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Database backed up to: " + backupFile.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to backup database: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error closing database: " + e.getMessage());
        }
    }

    // ─── Session Operations ───

    public int createSession(Session session) {
        lock.writeLock().lock();
        try {
            try (PreparedStatement deactivate = connection.prepareStatement(
                    "UPDATE sessions SET active = 0, end_time = ? WHERE player_uuid = ? AND active = 1")) {
                deactivate.setLong(1, System.currentTimeMillis());
                deactivate.setString(2, session.getPlayerUuid().toString());
                deactivate.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error deactivating old sessions: " + e.getMessage());
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sessions (player_uuid, player_name, start_time, end_time, item_count, suspicion_score, active) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, session.getPlayerUuid().toString());
            ps.setString(2, session.getPlayerName());
            ps.setLong(3, session.getStartTime());
            ps.setLong(4, session.getEndTime());
            ps.setInt(5, session.getItemCount());
            ps.setInt(6, session.getSuspicionScore());
            ps.setInt(7, session.isActive() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    session.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error creating session: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
        return -1;
    }

    public void endSession(int sessionId, long endTime, int itemCount, int suspicionScore) {
        lock.writeLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET end_time = ?, item_count = ?, suspicion_score = ?, active = 0 WHERE id = ?")) {
            ps.setLong(1, endTime);
            ps.setInt(2, itemCount);
            ps.setInt(3, suspicionScore);
            ps.setInt(4, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error ending session: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Session getActiveSession(UUID playerUuid) {
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sessions WHERE player_uuid = ? AND active = 1 LIMIT 1")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSession(rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting active session: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return null;
    }

    public List<Session> getSessionsByPlayer(UUID playerUuid) {
        List<Session> sessions = new ArrayList<>();
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sessions WHERE player_uuid = ? ORDER BY start_time DESC")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sessions.add(mapSession(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting sessions: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return sessions;
    }

    public Session getSessionById(int id) {
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM sessions WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapSession(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting session by id: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return null;
    }

    public List<Session> getAllSessions(int limit, int offset) {
        List<Session> sessions = new ArrayList<>();
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sessions ORDER BY start_time DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sessions.add(mapSession(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting all sessions: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return sessions;
    }

    public void addNote(int sessionId, String noteText, String author) {
        lock.writeLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO session_notes (session_id, note_text, author, timestamp) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, sessionId);
            ps.setString(2, noteText);
            ps.setString(3, author);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error adding note: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> getNotes(int sessionId) {
        List<String> notes = new ArrayList<>();
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM session_notes WHERE session_id = ? ORDER BY timestamp")) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notes.add("[" + rs.getString("author") + "] " + rs.getString("note_text"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting notes: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return notes;
    }

    // ─── Session Items ───

    public void addSessionItem(SessionItem item) {
        lock.writeLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO session_items (session_id, material, amount, hash, action, timestamp, blocked) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, item.getSessionId());
            ps.setString(2, item.getMaterial());
            ps.setInt(3, item.getAmount());
            ps.setString(4, item.getHash());
            ps.setString(5, item.getAction());
            ps.setLong(6, item.getTimestamp());
            ps.setInt(7, item.isBlocked() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error adding session item: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<SessionItem> getSessionItems(int sessionId) {
        List<SessionItem> items = new ArrayList<>();
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM session_items WHERE session_id = ? ORDER BY timestamp")) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new SessionItem(
                            rs.getInt("id"),
                            rs.getInt("session_id"),
                            rs.getString("material"),
                            rs.getInt("amount"),
                            rs.getString("hash"),
                            rs.getString("action"),
                            rs.getLong("timestamp"),
                            rs.getInt("blocked") == 1
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting session items: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return items;
    }

    // ─── Container Logs ───

    public void addContainerLog(ContainerLog log) {
        lock.writeLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO container_logs (session_id, player_name, material, amount, container_type, world, x, y, z, timestamp) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, log.getSessionId());
            ps.setString(2, log.getPlayerName());
            ps.setString(3, log.getMaterial());
            ps.setInt(4, log.getAmount());
            ps.setString(5, log.getContainerType());
            ps.setString(6, log.getWorld());
            ps.setInt(7, log.getX());
            ps.setInt(8, log.getY());
            ps.setInt(9, log.getZ());
            ps.setLong(10, log.getTimestamp());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error adding container log: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ─── Blocked Players ───

    public boolean isPlayerBlocked(UUID playerUuid) {
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM blocked_players WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error checking blocked player: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return false;
    }

    public void setPlayerBlocked(UUID playerUuid, String playerName, boolean blocked) {
        lock.writeLock().lock();
        try {
            if (blocked) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO blocked_players (player_uuid, player_name, blocked_since) VALUES (?, ?, ?)")) {
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, playerName);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM blocked_players WHERE player_uuid = ?")) {
                    ps.setString(1, playerUuid.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error setting blocked player: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<UUID> getAllBlockedPlayers() {
        List<UUID> uuids = new ArrayList<>();
        lock.readLock().lock();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT player_uuid FROM blocked_players")) {
            while (rs.next()) uuids.add(UUID.fromString(rs.getString("player_uuid")));
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting blocked players: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return uuids;
    }

    // ─── Whitelist ───

    public boolean isWhitelisted(UUID playerUuid, String material) {
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM player_item_whitelist WHERE player_uuid = ? AND (material = ? OR material = 'ALL')")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, material);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error checking whitelist: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return false;
    }

    public void addWhitelist(UUID playerUuid, String material) {
        lock.writeLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO player_item_whitelist (player_uuid, material) VALUES (?, ?)")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, material.toUpperCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error adding whitelist: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeWhitelist(UUID playerUuid, String material) {
        lock.writeLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                material.equalsIgnoreCase("ALL")
                        ? "DELETE FROM player_item_whitelist WHERE player_uuid = ?"
                        : "DELETE FROM player_item_whitelist WHERE player_uuid = ? AND material = ?")) {
            ps.setString(1, playerUuid.toString());
            if (!material.equalsIgnoreCase("ALL")) ps.setString(2, material.toUpperCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error removing whitelist: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Map<String, List<String>> getFullWhitelist() {
        Map<String, List<String>> whitelist = new HashMap<>();
        lock.readLock().lock();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT player_uuid, material FROM player_item_whitelist")) {
            while (rs.next()) {
                whitelist.computeIfAbsent(rs.getString("player_uuid"), k -> new ArrayList<>())
                        .add(rs.getString("material"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting whitelist: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return whitelist;
    }

    // ─── Player Sessions Summary ───

    public Map<UUID, PlayerSummary> getAllPlayerSummaries() {
        Map<UUID, PlayerSummary> summaries = new HashMap<>();
        lock.readLock().lock();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT player_name, player_uuid, COUNT(*) as session_count, " +
                             "SUM(item_count) as total_items, AVG(suspicion_score) as avg_score, " +
                             "MAX(start_time) as last_seen FROM sessions GROUP BY player_uuid")) {
            while (rs.next()) {
                String uuidStr = rs.getString("player_uuid");
                if (uuidStr == null || uuidStr.isEmpty()) continue;
                try {
                    PlayerSummary s = new PlayerSummary();
                    s.playerName = rs.getString("player_name");
                    s.playerUuid = UUID.fromString(uuidStr);
                    s.sessionCount = rs.getInt("session_count");
                    s.totalItems = rs.getInt("total_items");
                    s.avgSuspicionScore = rs.getInt("avg_score");
                    s.lastSeen = rs.getLong("last_seen");
                    summaries.put(s.playerUuid, s);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping session with invalid UUID: " + uuidStr);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting player summaries: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return summaries;
    }

    public int getPlayerSessionCount(UUID playerUuid) {
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM sessions WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting session count: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return 0;
    }

    // ─── Cleanup ───

    public int cleanupOldSessions(int days) {
        long cutoff = System.currentTimeMillis() - (days * 86400000L);
        lock.writeLock().lock();
        int count = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM sessions WHERE start_time < ? AND active = 0")) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sessionId = rs.getInt("id");
                    deleteSessionData(sessionId);
                    count++;
                }
            }
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM sessions WHERE start_time < ? AND active = 0")) {
                del.setLong(1, cutoff);
                del.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error cleaning up sessions: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
        return count;
    }

    private void deleteSessionData(int sessionId) {
        try (PreparedStatement d1 = connection.prepareStatement("DELETE FROM session_items WHERE session_id = ?");
             PreparedStatement d2 = connection.prepareStatement("DELETE FROM container_logs WHERE session_id = ?");
             PreparedStatement d3 = connection.prepareStatement("DELETE FROM session_notes WHERE session_id = ?")) {
            d1.setInt(1, sessionId); d1.executeUpdate();
            d2.setInt(1, sessionId); d2.executeUpdate();
            d3.setInt(1, sessionId); d3.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error deleting session data: " + e.getMessage());
        }
    }

    // ─── Rollback ───

    public List<SessionItem> getSessionItemsByMaterial(int sessionId, String material) {
        List<SessionItem> items = new ArrayList<>();
        lock.readLock().lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM session_items WHERE session_id = ? AND material = ?")) {
            ps.setInt(1, sessionId);
            ps.setString(2, material);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new SessionItem(rs.getInt("id"), rs.getInt("session_id"),
                            rs.getString("material"), rs.getInt("amount"), rs.getString("hash"),
                            rs.getString("action"), rs.getLong("timestamp"), rs.getInt("blocked") == 1));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting session items by material: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return items;
    }

    // ─── Helper ───

    private Session mapSession(ResultSet rs) throws SQLException {
        return new Session(
                rs.getInt("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                rs.getLong("start_time"),
                rs.getLong("end_time"),
                rs.getInt("item_count"),
                rs.getInt("suspicion_score"),
                getNotes(rs.getInt("id")),
                rs.getInt("active") == 1
        );
    }

    public static class PlayerSummary {
        public String playerName;
        public UUID playerUuid;
        public int sessionCount;
        public int totalItems;
        public int avgSuspicionScore;
        public long lastSeen;
    }
}
