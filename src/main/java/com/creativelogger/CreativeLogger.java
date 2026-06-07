package com.creativelogger;

import com.creativelogger.commands.StaffLogCommand;
import com.creativelogger.config.ConfigManager;
import com.creativelogger.database.DatabaseManager;
import com.creativelogger.gui.GUI;
import com.creativelogger.gui.GUIListener;
import com.creativelogger.gui.SessionViewGUI;
import com.creativelogger.listeners.CommandListener;
import com.creativelogger.listeners.ContainerListener;
import com.creativelogger.listeners.FreezeListener;
import com.creativelogger.listeners.PlayerListener;
import com.creativelogger.utils.GeyserChecker;
import com.creativelogger.utils.ItemUtils;
import com.creativelogger.utils.LockdownManager;
import com.creativelogger.utils.RateLimiter;
import com.creativelogger.webhook.DiscordWebhook;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CreativeLogger extends JavaPlugin {
    private static CreativeLogger instance;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private DiscordWebhook discordWebhook;
    private PlayerListener playerListener;
    private ContainerListener containerListener;
    private CommandListener commandListener;
    private FreezeListener freezeListener;
    private StaffLogCommand staffLogCommand;
    private GUI gui;
    private GUIListener guiListener;
    private SessionViewGUI sessionViewGUI;
    private RateLimiter rateLimiter;
    private LockdownManager lockdownManager;
    private GeyserChecker geyserChecker;
    private final Set<UUID> frozenPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        instance = this;

        this.configManager = new ConfigManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.discordWebhook = new DiscordWebhook(this);
        this.rateLimiter = new RateLimiter(configManager.getRateLimit());
        this.lockdownManager = new LockdownManager(this);
        this.geyserChecker = new GeyserChecker();

        ItemUtils.init(this);

        this.databaseManager.init();

        this.playerListener = new PlayerListener(this);
        this.containerListener = new ContainerListener(this);
        this.commandListener = new CommandListener(this);
        this.freezeListener = new FreezeListener(this);
        this.gui = new GUI(this);
        this.guiListener = new GUIListener(this);
        this.sessionViewGUI = new SessionViewGUI(this);
        this.staffLogCommand = new StaffLogCommand(this);

        getServer().getPluginManager().registerEvents(playerListener, this);
        getServer().getPluginManager().registerEvents(containerListener, this);
        getServer().getPluginManager().registerEvents(commandListener, this);
        getServer().getPluginManager().registerEvents(freezeListener, this);
        getServer().getPluginManager().registerEvents(guiListener, this);

        getCommand("stafflog").setExecutor(staffLogCommand);
        getCommand("stafflog").setTabCompleter(staffLogCommand);

        lockdownManager.checkLockdown();

        getServer().getScheduler().runTaskTimer(this, () -> {
            gui.getOpenPlayers().forEach(uuid -> {
                Player p = getServer().getPlayer(uuid);
                if (p != null && p.isOnline() && p.getOpenInventory().getTopInventory() != null
                        && gui.getState(p) != null) {
                    gui.notifyDataChanged(p);
                }
            });
        }, 40L, 40L);

        playerListener.startInventoryScanner();

        getLogger().info("CreativeLogger ativado com sucesso!");
        getLogger().info("Versão: 1.0.0 | Paper 1.21.4");
    }

    @Override
    public void onDisable() {
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.hasPermission("stafflog.staff")) {
                playerListener.endSession(p, "shutdown");
            }
        }

        lockdownManager.saveLockdown();
        databaseManager.close();

        getLogger().info("CreativeLogger desativado.");
    }

    public static CreativeLogger getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public DiscordWebhook getDiscordWebhook() { return discordWebhook; }
    public PlayerListener getPlayerListener() { return playerListener; }
    public StaffLogCommand getStaffLogCommand() { return staffLogCommand; }
    public GUI getGUI() { return gui; }
    public SessionViewGUI getSessionViewGUI() { return sessionViewGUI; }
    public RateLimiter getRateLimiter() { return rateLimiter; }
    public LockdownManager getLockdownManager() { return lockdownManager; }
    public GeyserChecker getGeyserChecker() { return geyserChecker; }
    public Set<UUID> getFrozenPlayers() { return frozenPlayers; }
}
