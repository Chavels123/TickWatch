package ticks.tickWatch;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ticks.tickWatch.command.CommandManager;
import ticks.tickWatch.data.DataManager;
import ticks.tickWatch.gui.GuiManager;
import ticks.tickWatch.manager.PerformanceManager;
import ticks.tickWatch.manager.WebhookManager;

public final class TickWatch extends JavaPlugin implements Listener {

    private DataManager dataManager;
    private PerformanceManager performanceManager;
    private WebhookManager webhookManager;
    private GuiManager guiManager;
    private CommandManager commandManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        // Initialize Data
        dataManager = new DataManager(this);

        // Initialize Managers
        performanceManager = new PerformanceManager(this);
        webhookManager = new WebhookManager(this);
        webhookManager.setPerformanceManager(performanceManager);

        // Initialize GUI
        guiManager = new GuiManager(this, performanceManager, webhookManager, dataManager);

        // Initialize Commands
        commandManager = new CommandManager(this, guiManager, webhookManager, dataManager);
        PluginCommand cmd = getCommand("tickwatch");
        if (cmd != null) {
            cmd.setExecutor(commandManager);
        }

        // Register Events
        getServer().getPluginManager().registerEvents(this, this);

        // Start Systems
        webhookManager.startWebhookSystem();
        
        // Schedule performance warnings check
        getServer().getScheduler().runTaskTimer(this, () -> performanceManager.checkAndSendWarnings(), 40L, 40L);

        getLogger().info("TickWatch has been enabled!");
    }

    @Override
    public void onDisable() {
        if (guiManager != null) {
            guiManager.cleanup();
        }
        if (dataManager != null) {
            dataManager.saveData();
        }
        getLogger().info("TickWatch has been disabled!");
    }

    // Command interception for Reload/Stop tracking
    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String cmd = event.getMessage().toLowerCase();
        
        if (cmd.startsWith("/reload") || cmd.equals("/rl")) {
            if (dataManager != null) {
                dataManager.setLastReloadTime(System.currentTimeMillis());
                dataManager.saveData();
            }
        } else if (cmd.startsWith("/stop")) {
            if (dataManager != null) {
                dataManager.setLastShutdownTime(System.currentTimeMillis());
                dataManager.saveData();
            }
        }
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        String cmd = event.getCommand().toLowerCase();
        
        if (cmd.startsWith("reload") || cmd.equals("rl")) {
            if (dataManager != null) {
                dataManager.setLastReloadTime(System.currentTimeMillis());
                dataManager.saveData();
            }
        } else if (cmd.startsWith("stop")) {
            if (dataManager != null) {
                dataManager.setLastShutdownTime(System.currentTimeMillis());
                dataManager.saveData();
            }
        }
    }
}
