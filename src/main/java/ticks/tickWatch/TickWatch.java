package ticks.tickWatch;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.OutputStream;

import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.FileConfiguration;

public final class TickWatch extends JavaPlugin implements Listener {

    private long startTime;
    private long lastReloadTime;
    private long lastShutdownTime;
    private SimpleDateFormat dateFormat;
    private DecimalFormat decimalFormat;
    private File dataFolder;
    private File dataFile;
    private MemoryMXBean memoryBean;
    private OperatingSystemMXBean osBean;
    private long[] tpsHistory;
    private int tpsIndex;
    private long lastTpsCheck;
    private Map<Player, String> activeGuis;
    private BukkitTask updateTask;
    private BukkitTask webhookTask;
    private String webhookUrl;
    private boolean webhookEnabled;
    private int checkInterval;
    private int alertCooldown;
    private Map<String, Long> lastAlertTimes;
    private Map<UUID, String> awaitingChatInput;

    @Override
    public void onEnable() {
        startTime = System.currentTimeMillis();
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        decimalFormat = new DecimalFormat("#.##");
        
        memoryBean = ManagementFactory.getMemoryMXBean();
        osBean = ManagementFactory.getOperatingSystemMXBean();
        
        tpsHistory = new long[60];
        tpsIndex = 0;
        lastTpsCheck = System.currentTimeMillis();
        activeGuis = new HashMap<>();
        lastAlertTimes = new HashMap<>();
        awaitingChatInput = new HashMap<>();
        
        setupDataFolder();
        loadData();
        
        getServer().getPluginManager().registerEvents(this, this);
        
        startTpsTracking();
        startRealtimeUpdates();
        
        saveDefaultConfig();
        loadWebhookConfig();
        startWebhookSystem();
        
        getLogger().info("TickWatch has been enabled!");
    }

    @Override
    public void onDisable() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        if (webhookTask != null) {
            webhookTask.cancel();
        }
        activeGuis.clear();
        saveData();
        getLogger().info("TickWatch has been disabled!");
    }

    private void setupDataFolder() {
        dataFolder = new File(getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        dataFile = new File(dataFolder, "timedata.json");
    }

    private void loadData() {
        if (!dataFile.exists()) {
            lastReloadTime = startTime;
            lastShutdownTime = 0;
            saveData();
            return;
        }

        try {
            String content = new String(Files.readAllBytes(dataFile.toPath()));
            String[] lines = content.split("\n");
            
            for (String line : lines) {
                line = line.trim();
                if (line.contains("\"startTime\":")) {
                    String value = line.split(":")[1].trim().replace(",", "").replace("\"", "");
                    if (!value.equals("null")) {
                        startTime = Long.parseLong(value);
                    }
                } else if (line.contains("\"lastReloadTime\":")) {
                    String value = line.split(":")[1].trim().replace(",", "").replace("\"", "");
                    if (!value.equals("null")) {
                        lastReloadTime = Long.parseLong(value);
                    } else {
                        lastReloadTime = startTime;
                    }
                } else if (line.contains("\"lastShutdownTime\":")) {
                    String value = line.split(":")[1].trim().replace(",", "").replace("\"", "");
                    if (!value.equals("null")) {
                        lastShutdownTime = Long.parseLong(value);
                    } else {
                        lastShutdownTime = 0;
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to load time data: " + e.getMessage());
            lastReloadTime = startTime;
            lastShutdownTime = 0;
        }
    }

    private void saveData() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"startTime\": ").append(startTime).append(",\n");
            json.append("  \"lastReloadTime\": ").append(lastReloadTime).append(",\n");
            json.append("  \"lastShutdownTime\": ").append(lastShutdownTime).append(",\n");
            json.append("  \"lastSaved\": \"").append(dateFormat.format(new Date())).append("\"\n");
            json.append("}");

            Files.write(dataFile.toPath(), json.toString().getBytes());
        } catch (Exception e) {
            getLogger().severe("Failed to save time data: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("tickwatch")) {
            return false;
        }

        if (!sender.hasPermission("tickwatch.use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.YELLOW + "TickWatch can only be used by players in-game!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            playSound(player, "gui_open");
            openMainGUI(player);
            return true;
        }

        String subCmd = args[0].toLowerCase();
        
        switch (subCmd) {
            case "help":
            case "?":
                showHelp(player);
                break;
            case "dashboard":
            case "dash":
                playSound(player, "gui_open");
                openCompleteDashboardGUI(player);
                break;
            case "performance":
            case "perf":
                playSound(player, "gui_open");
                openPerformanceGUI(player);
                break;
            case "reload":
                if (player.hasPermission("tickwatch.admin")) {
                    playSound(player, "reload");
                    reloadConfig();
                    loadWebhookConfig();
                    
                    if (webhookTask != null) {
                        webhookTask.cancel();
                    }
                    startWebhookSystem();
                    
                    loadData();
                    player.sendMessage(ChatColor.GREEN + "TickWatch configuration and data reloaded!");
                } else {
                    playSound(player, "error");
                    player.sendMessage(ChatColor.RED + "You don't have permission to reload data!");
                }
                break;
            case "debug":
                if (player.hasPermission("tickwatch.admin")) {
                    boolean currentDebug = getConfig().getBoolean("advanced.debug", false);
                    getConfig().set("advanced.debug", !currentDebug);
                    saveConfig();
                    
                    player.sendMessage(ChatColor.GREEN + "Debug mode " + (!currentDebug ? "enabled" : "disabled") + "!");
                    player.sendMessage(ChatColor.GRAY + "Check server console for webhook debug information.");
                } else {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                }
                break;
            case "testwebhook":
                if (player.hasPermission("tickwatch.admin")) {
                    if (!webhookEnabled) {
                        player.sendMessage(ChatColor.RED + "Webhook is not enabled!");
                        break;
                    }
                    
                    if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                        player.sendMessage(ChatColor.RED + "Webhook URL is not configured!");
                        break;
                    }
                    
                    player.sendMessage(ChatColor.YELLOW + "Sending test webhook...");
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        sendAlert("warning", "Manual Test Alert", "This is a manual test webhook triggered by " + player.getName(), "#0099FF");
                        Bukkit.getScheduler().runTask(this, () -> {
                            player.sendMessage(ChatColor.GREEN + "Test webhook sent! Check Discord and console.");
                        });
                    });
                } else {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                }
                break;
            default:
                playSound(player, "gui_open");
                openMainGUI(player);
                break;
        }
        
        return true;
    }

    private void openMainGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GOLD + "§8[§6TW§8] §eServer Monitor");
        
        gui.setItem(11, createItem(getMaterialSafe("NETHER_STAR"), ChatColor.LIGHT_PURPLE + "Complete Dashboard",
            Arrays.asList(
                ChatColor.GRAY + "All-in-one monitoring interface",
                ChatColor.GRAY + "• Real-time TPS, Memory & CPU",
                ChatColor.GRAY + "• Performance graphs & analytics",
                ChatColor.GRAY + "• System health monitoring",
                "",
                ChatColor.GREEN + "Click to open dashboard"
            )));
        
        gui.setItem(13, createItem(getMaterialSafe("BEACON"), ChatColor.AQUA + "Performance Overview",
            Arrays.asList(
                ChatColor.GRAY + "Quick performance summary",
                ChatColor.GRAY + "• TPS status & trends",
                ChatColor.GRAY + "• Memory usage overview",
                ChatColor.GRAY + "• CPU load information",
                "",
                ChatColor.GREEN + "Click to view performance"
            )));
        
        gui.setItem(15, createItem(getMaterialSafe("BOOK"), ChatColor.YELLOW + "Help & Commands",
            Arrays.asList(
                ChatColor.GRAY + "View available commands",
                ChatColor.GRAY + "• Command usage guide",
                ChatColor.GRAY + "• Plugin information",
                "",
                ChatColor.GREEN + "Click for help"
            )));

        if (webhookEnabled && webhookUrl != null && !webhookUrl.trim().isEmpty()) {
            gui.setItem(22, createItem(getMaterialSafe("BELL"), ChatColor.GOLD + "Webhook Settings",
                Arrays.asList(
                    ChatColor.GRAY + "Configure webhook alerts",
                    ChatColor.GRAY + "• Enable/disable alerts",
                    ChatColor.GRAY + "• Adjust thresholds",
                    ChatColor.GRAY + "• Set intervals & cooldowns",
                    "",
                    ChatColor.GREEN + "Status: " + (webhookEnabled ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                    ChatColor.GREEN + "Click to configure"
                )));
        }
        
        player.openInventory(gui);
    }

    private void showHelp(Player player) {
        playSound(player, "notification");
        
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "==================== " + ChatColor.YELLOW + "TickWatch Help" + ChatColor.GOLD + " ====================");
        player.sendMessage("");
        player.sendMessage(ChatColor.AQUA + "Available Commands:");
        player.sendMessage(ChatColor.GRAY + "• " + ChatColor.GREEN + "/tw" + ChatColor.WHITE + " - Open main monitoring interface");
        player.sendMessage(ChatColor.GRAY + "• " + ChatColor.GREEN + "/tw dashboard" + ChatColor.WHITE + " - Open complete dashboard");
        player.sendMessage(ChatColor.GRAY + "• " + ChatColor.GREEN + "/tw performance" + ChatColor.WHITE + " - Open performance overview");
        player.sendMessage(ChatColor.GRAY + "• " + ChatColor.GREEN + "/tw help" + ChatColor.WHITE + " - Show this help message");
        if (player.hasPermission("tickwatch.admin")) {
            player.sendMessage(ChatColor.GRAY + "• " + ChatColor.GREEN + "/tw reload" + ChatColor.WHITE + " - Reload plugin configuration");
            player.sendMessage(ChatColor.GRAY + "• " + ChatColor.GREEN + "/tw debug" + ChatColor.WHITE + " - Toggle debug mode");
            player.sendMessage(ChatColor.GRAY + "• " + ChatColor.GREEN + "/tw testwebhook" + ChatColor.WHITE + " - Send test webhook");
        }
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "==================== " + ChatColor.YELLOW + "TickWatch Help" + ChatColor.GOLD + " ====================");
        player.sendMessage("");
    }

    private void openWebhookSettingsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 45, ChatColor.GOLD + "§8[§6TW§8] §eWebhook Settings");
        
        boolean enabled = getConfig().getBoolean("webhook.enabled", false);
        String url = getConfig().getString("webhook.url", "");
        int checkInterval = getConfig().getInt("webhook.notifications.check-interval", 5);
        int alertCooldown = getConfig().getInt("webhook.notifications.alert-cooldown", 15);
        
        gui.setItem(10, createItem(getMaterialSafe("REDSTONE_TORCH"), 
            (enabled ? ChatColor.GREEN + "Webhook: Enabled" : ChatColor.RED + "Webhook: Disabled"),
            Arrays.asList(
                ChatColor.GRAY + "Toggle webhook notifications",
                "",
                ChatColor.WHITE + "Status: " + (enabled ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                ChatColor.WHITE + "URL: " + (url.isEmpty() ? ChatColor.RED + "Not configured" : ChatColor.GREEN + "Configured"),
                "",
                ChatColor.YELLOW + "Click to " + (enabled ? "disable" : "enable")
            )));

        gui.setItem(12, createItem(getMaterialSafe("CLOCK"), ChatColor.AQUA + "Check Interval",
            Arrays.asList(
                ChatColor.GRAY + "How often to check for alerts",
                "",
                ChatColor.WHITE + "Current: " + ChatColor.YELLOW + checkInterval + " minutes",
                ChatColor.WHITE + "Range: " + ChatColor.GRAY + "1-60 minutes",
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(14, createItem(getMaterialSafe("HOPPER"), ChatColor.LIGHT_PURPLE + "Alert Cooldown",
            Arrays.asList(
                ChatColor.GRAY + "Delay between same alert types",
                "",
                ChatColor.WHITE + "Current: " + ChatColor.YELLOW + alertCooldown + " minutes",
                ChatColor.WHITE + "Range: " + ChatColor.GRAY + "1-120 minutes",
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(16, createItem(getMaterialSafe("PAPER"), ChatColor.GOLD + "Alert Types",
            Arrays.asList(
                ChatColor.GRAY + "Configure which alerts to send",
                "",
                ChatColor.WHITE + "TPS Alerts: " + getAlertStatus("tps-warning", "tps-critical"),
                ChatColor.WHITE + "Memory Alerts: " + getAlertStatus("memory-warning", "memory-critical"),
                ChatColor.WHITE + "CPU Alerts: " + getAlertStatus("cpu-warning", "cpu-critical"),
                "",
                ChatColor.GREEN + "Click to configure"
            )));

        gui.setItem(19, createItem(getMaterialSafe("COMPARATOR"), ChatColor.YELLOW + "TPS Thresholds",
            Arrays.asList(
                ChatColor.GRAY + "Configure TPS alert levels",
                "",
                ChatColor.WHITE + "Warning: " + ChatColor.GOLD + getConfig().getDouble("thresholds.tps.warning", 15.0),
                ChatColor.WHITE + "Critical: " + ChatColor.RED + getConfig().getDouble("thresholds.tps.critical", 10.0),
                "",
                ChatColor.GREEN + "Click to adjust"
            )));

        gui.setItem(21, createItem(getMaterialSafe("REDSTONE"), ChatColor.LIGHT_PURPLE + "Memory Thresholds",
            Arrays.asList(
                ChatColor.GRAY + "Configure memory alert levels",
                "",
                ChatColor.WHITE + "Warning: " + ChatColor.GOLD + getConfig().getDouble("thresholds.memory.warning", 80.0) + "%",
                ChatColor.WHITE + "Critical: " + ChatColor.RED + getConfig().getDouble("thresholds.memory.critical", 90.0) + "%",
                "",
                ChatColor.GREEN + "Click to adjust"
            )));

        gui.setItem(23, createItem(getMaterialSafe("PISTON"), ChatColor.AQUA + "CPU Thresholds",
            Arrays.asList(
                ChatColor.GRAY + "Configure CPU alert levels",
                "",
                ChatColor.WHITE + "Warning: " + ChatColor.GOLD + getConfig().getDouble("thresholds.cpu.warning", 85.0) + "%",
                ChatColor.WHITE + "Critical: " + ChatColor.RED + getConfig().getDouble("thresholds.cpu.critical", 95.0) + "%",
                "",
                ChatColor.GREEN + "Click to adjust"
            )));

        gui.setItem(31, createItem(getMaterialSafe("EMERALD"), ChatColor.GREEN + "Test Webhook",
            Arrays.asList(
                ChatColor.GRAY + "Send a test notification",
                "",
                ChatColor.WHITE + "This will send a test alert",
                ChatColor.WHITE + "to verify your webhook works",
                "",
                ChatColor.GREEN + "Click to test"
            )));

        gui.setItem(40, createItem(getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Main Menu",
            Arrays.asList(
                ChatColor.GRAY + "Return to main interface",
                "",
                ChatColor.GREEN + "Click to go back"
            )));

        player.openInventory(gui);
    }

    private String getAlertStatus(String warningKey, String criticalKey) {
        boolean warning = getConfig().getBoolean("webhook.notifications.alerts." + warningKey, true);
        boolean critical = getConfig().getBoolean("webhook.notifications.alerts." + criticalKey, true);
        
        if (warning && critical) return ChatColor.GREEN + "Both enabled";
        if (warning) return ChatColor.YELLOW + "Warning only";
        if (critical) return ChatColor.YELLOW + "Critical only";
        return ChatColor.RED + "Disabled";
    }

    private void handleWebhookSettingsClick(Player player, String itemName) {
        if (itemName.contains("Webhook: Enabled") || itemName.contains("Webhook: Disabled")) {
            boolean currentState = getConfig().getBoolean("webhook.enabled", false);
            getConfig().set("webhook.enabled", !currentState);
            saveConfig();
            loadWebhookConfig();
            
            if (!currentState && webhookTask == null) {
                startWebhookSystem();
            } else if (currentState && webhookTask != null) {
                webhookTask.cancel();
                webhookTask = null;
            }
            
            playSound(player, "gui_click");
            player.sendMessage(ChatColor.GREEN + "Webhook " + (!currentState ? "enabled" : "disabled") + "!");
            openWebhookSettingsGUI(player);
            
        } else if (itemName.contains("Check Interval")) {
            playSound(player, "notification");
            player.closeInventory();
            awaitingChatInput.put(player.getUniqueId(), "check-interval");
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "==================== " + ChatColor.YELLOW + "Set Check Interval" + ChatColor.GOLD + " ====================");
            player.sendMessage("");
            player.sendMessage(ChatColor.AQUA + "Enter the check interval in minutes (1-60):");
            player.sendMessage(ChatColor.GRAY + "Current value: " + ChatColor.YELLOW + getConfig().getInt("webhook.notifications.check-interval", 5) + " minutes");
            player.sendMessage(ChatColor.GRAY + "Type " + ChatColor.RED + "cancel" + ChatColor.GRAY + " to abort");
            player.sendMessage("");
            
        } else if (itemName.contains("Alert Cooldown")) {
            playSound(player, "notification");
            player.closeInventory();
            awaitingChatInput.put(player.getUniqueId(), "alert-cooldown");
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "==================== " + ChatColor.YELLOW + "Set Alert Cooldown" + ChatColor.GOLD + " ====================");
            player.sendMessage("");
            player.sendMessage(ChatColor.AQUA + "Enter the alert cooldown in minutes (1-120):");
            player.sendMessage(ChatColor.GRAY + "Current value: " + ChatColor.YELLOW + getConfig().getInt("webhook.notifications.alert-cooldown", 15) + " minutes");
            player.sendMessage(ChatColor.GRAY + "Type " + ChatColor.RED + "cancel" + ChatColor.GRAY + " to abort");
            player.sendMessage("");
            
        } else if (itemName.contains("Alert Types")) {
            playSound(player, "gui_click");
            openAlertTypesGUI(player);
            
        } else if (itemName.contains("TPS Thresholds")) {
            playSound(player, "gui_click");
            openThresholdGUI(player, "tps");
            
        } else if (itemName.contains("Memory Thresholds")) {
            playSound(player, "gui_click");
            openThresholdGUI(player, "memory");
            
        } else if (itemName.contains("CPU Thresholds")) {
            playSound(player, "gui_click");
            openThresholdGUI(player, "cpu");
            
        } else if (itemName.contains("Test Webhook")) {
            playSound(player, "notification");
            player.closeInventory();
            
            if (!webhookEnabled) {
                player.sendMessage(ChatColor.RED + "Webhook is disabled! Enable it first in the settings.");
                player.sendMessage(ChatColor.YELLOW + "Current status: " + ChatColor.RED + "Disabled");
                return;
            }
            
            if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                player.sendMessage(ChatColor.RED + "Webhook URL is not configured!");
                player.sendMessage(ChatColor.YELLOW + "Please set a valid Discord webhook URL in the config.");
                return;
            }
            
            if (!isValidWebhookUrl(webhookUrl)) {
                player.sendMessage(ChatColor.RED + "Webhook URL is invalid!");
                player.sendMessage(ChatColor.YELLOW + "Please check the URL format: " + webhookUrl);
                return;
            }
            
            player.sendMessage(ChatColor.GREEN + "Sending test webhook...");
            player.sendMessage(ChatColor.GRAY + "URL: " + webhookUrl.substring(0, Math.min(50, webhookUrl.length())) + "...");
            
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                boolean success = sendAlert("info", "Test Notification", "This is a test webhook from TickWatch to verify your configuration is working correctly. Server: " + Bukkit.getServer().getName(), "#0099FF");
                Bukkit.getScheduler().runTask(this, () -> {
                    if (success) {
                        player.sendMessage(ChatColor.GREEN + "✓ Test webhook sent successfully! Check your Discord channel.");
                        player.sendMessage(ChatColor.GRAY + "If you don't see the message, check your webhook URL and Discord channel.");
                    } else {
                        player.sendMessage(ChatColor.RED + "✗ Failed to send test webhook! Check server console for details.");
                        player.sendMessage(ChatColor.YELLOW + "Enable debug mode with '/tw debug' for more information.");
                    }
                });
            });
        }
    }

    private void openAlertTypesGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GOLD + "§8[§6TW§8] §eAlert Types");
        
        boolean tpsWarning = getConfig().getBoolean("webhook.notifications.alerts.tps-warning", true);
        boolean tpsCritical = getConfig().getBoolean("webhook.notifications.alerts.tps-critical", true);
        boolean memWarning = getConfig().getBoolean("webhook.notifications.alerts.memory-warning", true);
        boolean memCritical = getConfig().getBoolean("webhook.notifications.alerts.memory-critical", true);
        boolean cpuWarning = getConfig().getBoolean("webhook.notifications.alerts.cpu-warning", true);
        boolean cpuCritical = getConfig().getBoolean("webhook.notifications.alerts.cpu-critical", true);
        
        gui.setItem(9, createItem(getMaterialSafe("CLOCK"), 
            ChatColor.YELLOW + "TPS Warning Alerts",
            Arrays.asList(
                ChatColor.GRAY + "Alert when TPS drops below warning level",
                "",
                ChatColor.WHITE + "Status: " + (tpsWarning ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                "",
                ChatColor.GREEN + "Click to " + (tpsWarning ? "disable" : "enable")
            )));

        gui.setItem(10, createItem(getMaterialSafe("REDSTONE_TORCH"), 
            ChatColor.RED + "TPS Critical Alerts",
            Arrays.asList(
                ChatColor.GRAY + "Alert when TPS drops to critical level",
                "",
                ChatColor.WHITE + "Status: " + (tpsCritical ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                "",
                ChatColor.GREEN + "Click to " + (tpsCritical ? "disable" : "enable")
            )));

        gui.setItem(12, createItem(getMaterialSafe("REDSTONE"), 
            ChatColor.LIGHT_PURPLE + "Memory Warning Alerts",
            Arrays.asList(
                ChatColor.GRAY + "Alert when memory usage exceeds warning level",
                "",
                ChatColor.WHITE + "Status: " + (memWarning ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                "",
                ChatColor.GREEN + "Click to " + (memWarning ? "disable" : "enable")
            )));

        gui.setItem(13, createItem(getMaterialSafe("TNT"), 
            ChatColor.RED + "Memory Critical Alerts",
            Arrays.asList(
                ChatColor.GRAY + "Alert when memory usage reaches critical level",
                "",
                ChatColor.WHITE + "Status: " + (memCritical ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                "",
                ChatColor.GREEN + "Click to " + (memCritical ? "disable" : "enable")
            )));

        gui.setItem(15, createItem(getMaterialSafe("PISTON"), 
            ChatColor.AQUA + "CPU Warning Alerts",
            Arrays.asList(
                ChatColor.GRAY + "Alert when CPU usage exceeds warning level",
                "",
                ChatColor.WHITE + "Status: " + (cpuWarning ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                "",
                ChatColor.GREEN + "Click to " + (cpuWarning ? "disable" : "enable")
            )));

        gui.setItem(16, createItem(getMaterialSafe("COMPARATOR"), 
            ChatColor.RED + "CPU Critical Alerts",
            Arrays.asList(
                ChatColor.GRAY + "Alert when CPU usage reaches critical level",
                "",
                ChatColor.WHITE + "Status: " + (cpuCritical ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                "",
                ChatColor.GREEN + "Click to " + (cpuCritical ? "disable" : "enable")
            )));

        gui.setItem(22, createItem(getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Webhook Settings",
            Arrays.asList(
                ChatColor.GRAY + "Return to webhook configuration",
                "",
                ChatColor.GREEN + "Click to go back"
            )));

        player.openInventory(gui);
    }

    private void openThresholdGUI(Player player, String type) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GOLD + "§8[§6TW§8] §e" + type.toUpperCase() + " Thresholds");
        
        double warningValue = getConfig().getDouble("thresholds." + type + ".warning", type.equals("tps") ? 15.0 : 80.0);
        double criticalValue = getConfig().getDouble("thresholds." + type + ".critical", type.equals("tps") ? 10.0 : type.equals("memory") ? 90.0 : 95.0);
        String unit = type.equals("tps") ? "" : "%";
        
        gui.setItem(11, createItem(getMaterialSafe("GOLD_INGOT"), 
            ChatColor.GOLD + "Warning Threshold",
            Arrays.asList(
                ChatColor.GRAY + "Set the warning level for " + type.toUpperCase(),
                "",
                ChatColor.WHITE + "Current: " + ChatColor.YELLOW + warningValue + unit,
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(15, createItem(getMaterialSafe("REDSTONE_BLOCK"), 
            ChatColor.RED + "Critical Threshold",
            Arrays.asList(
                ChatColor.GRAY + "Set the critical level for " + type.toUpperCase(),
                "",
                ChatColor.WHITE + "Current: " + ChatColor.RED + criticalValue + unit,
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(22, createItem(getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Webhook Settings",
            Arrays.asList(
                ChatColor.GRAY + "Return to webhook configuration",
                "",
                ChatColor.GREEN + "Click to go back"
            )));

        player.openInventory(gui);
    }

    private void handleAlertTypesClick(Player player, String itemName) {
        if (itemName.contains("TPS Warning Alerts")) {
            boolean current = getConfig().getBoolean("webhook.notifications.alerts.tps-warning", true);
            getConfig().set("webhook.notifications.alerts.tps-warning", !current);
            saveConfig();
            playSound(player, "gui_click");
            player.sendMessage(ChatColor.GREEN + "TPS Warning alerts " + (!current ? "enabled" : "disabled") + "!");
            openAlertTypesGUI(player);
            
        } else if (itemName.contains("TPS Critical Alerts")) {
            boolean current = getConfig().getBoolean("webhook.notifications.alerts.tps-critical", true);
            getConfig().set("webhook.notifications.alerts.tps-critical", !current);
            saveConfig();
            playSound(player, "gui_click");
            player.sendMessage(ChatColor.GREEN + "TPS Critical alerts " + (!current ? "enabled" : "disabled") + "!");
            openAlertTypesGUI(player);
            
        } else if (itemName.contains("Memory Warning Alerts")) {
            boolean current = getConfig().getBoolean("webhook.notifications.alerts.memory-warning", true);
            getConfig().set("webhook.notifications.alerts.memory-warning", !current);
            saveConfig();
            playSound(player, "gui_click");
            player.sendMessage(ChatColor.GREEN + "Memory Warning alerts " + (!current ? "enabled" : "disabled") + "!");
            openAlertTypesGUI(player);
            
        } else if (itemName.contains("Memory Critical Alerts")) {
            boolean current = getConfig().getBoolean("webhook.notifications.alerts.memory-critical", true);
            getConfig().set("webhook.notifications.alerts.memory-critical", !current);
            saveConfig();
            playSound(player, "gui_click");
            player.sendMessage(ChatColor.GREEN + "Memory Critical alerts " + (!current ? "enabled" : "disabled") + "!");
            openAlertTypesGUI(player);
            
        } else if (itemName.contains("CPU Warning Alerts")) {
            boolean current = getConfig().getBoolean("webhook.notifications.alerts.cpu-warning", true);
            getConfig().set("webhook.notifications.alerts.cpu-warning", !current);
            saveConfig();
            playSound(player, "gui_click");
            player.sendMessage(ChatColor.GREEN + "CPU Warning alerts " + (!current ? "enabled" : "disabled") + "!");
            openAlertTypesGUI(player);
            
        } else if (itemName.contains("CPU Critical Alerts")) {
            boolean current = getConfig().getBoolean("webhook.notifications.alerts.cpu-critical", true);
            getConfig().set("webhook.notifications.alerts.cpu-critical", !current);
            saveConfig();
            playSound(player, "gui_click");
            player.sendMessage(ChatColor.GREEN + "CPU Critical alerts " + (!current ? "enabled" : "disabled") + "!");
            openAlertTypesGUI(player);
            
        } else if (itemName.contains("Back to Webhook Settings")) {
            playSound(player, "gui_click");
            openWebhookSettingsGUI(player);
        }
    }

    private void handleThresholdClick(Player player, String itemName, String title) {
        String type = "";
        if (title.contains("TPS")) type = "tps";
        else if (title.contains("MEMORY")) type = "memory";
        else if (title.contains("CPU")) type = "cpu";
        
        if (itemName.contains("Warning Threshold")) {
            playSound(player, "notification");
            player.closeInventory();
            awaitingChatInput.put(player.getUniqueId(), type + "-warning");
            
            double currentValue = getConfig().getDouble("thresholds." + type + ".warning", type.equals("tps") ? 15.0 : 80.0);
            String unit = type.equals("tps") ? "" : "%";
            
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "==================== " + ChatColor.YELLOW + "Set " + type.toUpperCase() + " Warning Threshold" + ChatColor.GOLD + " ====================");
            player.sendMessage("");
            player.sendMessage(ChatColor.AQUA + "Enter the warning threshold value:");
            player.sendMessage(ChatColor.GRAY + "Current value: " + ChatColor.YELLOW + currentValue + unit);
            if (type.equals("tps")) {
                player.sendMessage(ChatColor.GRAY + "Recommended range: " + ChatColor.WHITE + "10.0 - 19.0");
            } else {
                player.sendMessage(ChatColor.GRAY + "Recommended range: " + ChatColor.WHITE + "70% - 90%");
            }
            player.sendMessage(ChatColor.GRAY + "Type " + ChatColor.RED + "cancel" + ChatColor.GRAY + " to abort");
            player.sendMessage("");
            
        } else if (itemName.contains("Critical Threshold")) {
            playSound(player, "notification");
            player.closeInventory();
            awaitingChatInput.put(player.getUniqueId(), type + "-critical");
            
            double currentValue = getConfig().getDouble("thresholds." + type + ".critical", type.equals("tps") ? 10.0 : type.equals("memory") ? 90.0 : 95.0);
            String unit = type.equals("tps") ? "" : "%";
            
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "==================== " + ChatColor.RED + "Set " + type.toUpperCase() + " Critical Threshold" + ChatColor.GOLD + " ====================");
            player.sendMessage("");
            player.sendMessage(ChatColor.AQUA + "Enter the critical threshold value:");
            player.sendMessage(ChatColor.GRAY + "Current value: " + ChatColor.RED + currentValue + unit);
            if (type.equals("tps")) {
                player.sendMessage(ChatColor.GRAY + "Recommended range: " + ChatColor.WHITE + "5.0 - 15.0");
            } else {
                player.sendMessage(ChatColor.GRAY + "Recommended range: " + ChatColor.WHITE + "85% - 98%");
            }
            player.sendMessage(ChatColor.GRAY + "Type " + ChatColor.RED + "cancel" + ChatColor.GRAY + " to abort");
            player.sendMessage("");
            
        } else if (itemName.contains("Back to Webhook Settings")) {
            playSound(player, "gui_click");
            openWebhookSettingsGUI(player);
        }
    }

    private void handleCheckIntervalInput(Player player, String message) {
        try {
            int minutes = Integer.parseInt(message);
            if (minutes < 1 || minutes > 60) {
                player.sendMessage(ChatColor.RED + "Please enter a number between 1 and 60 minutes.");
                return;
            }
            
            awaitingChatInput.remove(player.getUniqueId());
            getConfig().set("webhook.notifications.check-interval", minutes);
            saveConfig();
            
            if (webhookTask != null) {
                webhookTask.cancel();
            }
            startWebhookSystem();
            
            player.sendMessage(ChatColor.GREEN + "Check interval set to " + minutes + " minutes!");
            Bukkit.getScheduler().runTask(this, () -> openWebhookSettingsGUI(player));
            
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid number! Please enter a valid number between 1 and 60.");
        }
    }

    private void handleAlertCooldownInput(Player player, String message) {
        try {
            int minutes = Integer.parseInt(message);
            if (minutes < 1 || minutes > 120) {
                player.sendMessage(ChatColor.RED + "Please enter a number between 1 and 120 minutes.");
                return;
            }
            
            awaitingChatInput.remove(player.getUniqueId());
            getConfig().set("webhook.notifications.alert-cooldown", minutes);
            saveConfig();
            
            player.sendMessage(ChatColor.GREEN + "Alert cooldown set to " + minutes + " minutes!");
            Bukkit.getScheduler().runTask(this, () -> openWebhookSettingsGUI(player));
            
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid number! Please enter a valid number between 1 and 120.");
        }
    }

    private void handleThresholdInput(Player player, String message, String inputType) {
        try {
            double value = Double.parseDouble(message);
            String[] parts = inputType.split("-");
            String type = parts[0];
            String level = parts[1];
            
            boolean isValid = false;
            String configPath = "thresholds." + type + "." + level;
            
            if (type.equals("tps")) {
                if (value >= 1.0 && value <= 20.0) {
                    isValid = true;
                }
            } else {
                if (value >= 1.0 && value <= 100.0) {
                    isValid = true;
                }
            }
            
            if (!isValid) {
                if (type.equals("tps")) {
                    player.sendMessage(ChatColor.RED + "TPS value must be between 1.0 and 20.0!");
                } else {
                    player.sendMessage(ChatColor.RED + "Percentage must be between 1% and 100%!");
                }
                return;
            }
            
            awaitingChatInput.remove(player.getUniqueId());
            getConfig().set(configPath, value);
            saveConfig();
            
            String unit = type.equals("tps") ? "" : "%";
            player.sendMessage(ChatColor.GREEN + type.toUpperCase() + " " + level + " threshold set to " + value + unit + "!");
            
            Bukkit.getScheduler().runTask(this, () -> {
                if (type.equals("tps")) openTPSThresholdsGUI(player);
                else if (type.equals("memory")) openMemoryThresholdsGUI(player);
                else if (type.equals("cpu")) openCPUThresholdsGUI(player);
            });
            
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid number! Please enter a valid decimal number.");
        }
    }

    private void openTPSThresholdsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, "§8[§6TW§8] §6TPS Thresholds");
        
        double warning = getConfig().getDouble("thresholds.tps.warning", 15.0);
        double critical = getConfig().getDouble("thresholds.tps.critical", 10.0);
        
        gui.setItem(11, createItem(getMaterialSafe("CLOCK"), 
            ChatColor.YELLOW + "Warning Threshold",
            Arrays.asList(
                ChatColor.GRAY + "Current value: " + ChatColor.YELLOW + warning,
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(15, createItem(getMaterialSafe("REDSTONE_TORCH"), 
            ChatColor.RED + "Critical Threshold",
            Arrays.asList(
                ChatColor.GRAY + "Current value: " + ChatColor.RED + critical,
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(22, createItem(getMaterialSafe("ARROW"), 
            ChatColor.WHITE + "Back to Webhook Settings",
            Arrays.asList(ChatColor.GRAY + "Return to webhook settings menu")));

        player.openInventory(gui);
    }

    private void openMemoryThresholdsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, "§8[§6TW§8] §dMEMORY Thresholds");
        
        double warning = getConfig().getDouble("thresholds.memory.warning", 80.0);
        double critical = getConfig().getDouble("thresholds.memory.critical", 90.0);
        
        gui.setItem(11, createItem(getMaterialSafe("REDSTONE"), 
            ChatColor.YELLOW + "Warning Threshold",
            Arrays.asList(
                ChatColor.GRAY + "Current value: " + ChatColor.YELLOW + warning + "%",
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(15, createItem(getMaterialSafe("TNT"), 
            ChatColor.RED + "Critical Threshold",
            Arrays.asList(
                ChatColor.GRAY + "Current value: " + ChatColor.RED + critical + "%",
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(22, createItem(getMaterialSafe("ARROW"), 
            ChatColor.WHITE + "Back to Webhook Settings",
            Arrays.asList(ChatColor.GRAY + "Return to webhook settings menu")));

        player.openInventory(gui);
    }

    private void openCPUThresholdsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, "§8[§6TW§8] §bCPU Thresholds");
        
        double warning = getConfig().getDouble("thresholds.cpu.warning", 85.0);
        double critical = getConfig().getDouble("thresholds.cpu.critical", 95.0);
        
        gui.setItem(11, createItem(getMaterialSafe("PISTON"), 
            ChatColor.YELLOW + "Warning Threshold",
            Arrays.asList(
                ChatColor.GRAY + "Current value: " + ChatColor.YELLOW + warning + "%",
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(15, createItem(getMaterialSafe("COMPARATOR"), 
            ChatColor.RED + "Critical Threshold",
            Arrays.asList(
                ChatColor.GRAY + "Current value: " + ChatColor.RED + critical + "%",
                "",
                ChatColor.GREEN + "Click to change"
            )));

        gui.setItem(22, createItem(getMaterialSafe("ARROW"), 
            ChatColor.WHITE + "Back to Webhook Settings",
            Arrays.asList(ChatColor.GRAY + "Return to webhook settings menu")));

        player.openInventory(gui);
    }

    private void openTimeGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GREEN + "§8[§6TW§8] §aTime Info");
        
        String currentTime = dateFormat.format(new Date());
        long timeSinceReload = System.currentTimeMillis() - lastReloadTime;
        String reloadTime = dateFormat.format(new Date(lastReloadTime));
        String reloadDuration = formatDuration(timeSinceReload);
        
        gui.setItem(11, createItem(Material.CLOCK, ChatColor.GREEN + "Current Time",
            Arrays.asList(ChatColor.WHITE + currentTime)));
        
        gui.setItem(13, createItem(getMaterialSafe("EMERALD"), ChatColor.AQUA + "Last Reload",
            Arrays.asList(
                ChatColor.WHITE + reloadTime,
                ChatColor.GRAY + "Duration: " + reloadDuration
            )));
        
        if (lastShutdownTime > 0) {
            long timeSinceShutdown = System.currentTimeMillis() - lastShutdownTime;
            String shutdownTime = dateFormat.format(new Date(lastShutdownTime));
            String shutdownDuration = formatDuration(timeSinceShutdown);
            
            gui.setItem(15, createItem(getMaterialSafe("REDSTONE_BLOCK"), ChatColor.RED + "Last Shutdown",
                Arrays.asList(
                    ChatColor.WHITE + shutdownTime,
                    ChatColor.GRAY + "Duration: " + shutdownDuration
                )));
        } else {
            gui.setItem(15, createItem(getMaterialSafe("BARRIER"), ChatColor.YELLOW + "No Shutdown Recorded",
                Arrays.asList(ChatColor.GRAY + "No shutdown since plugin start")));
        }
        
        gui.setItem(22, createItem(getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Main Menu", null));
        
        player.openInventory(gui);
    }

    private void openMemoryGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.LIGHT_PURPLE + "§8[§6TW§8] §dMemory");
        
        trackActiveGui(player, "memory");
        
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        
        double maxMemMB = bytesToMB(maxMem);
        double usedMemMB = bytesToMB(usedMem);
        double freeMemMB = bytesToMB(freeMem);
        double usagePercent = (usedMemMB / maxMemMB) * 100;
        
        String status;
        Material statusMaterial;
        if (usagePercent > 80) {
            status = ChatColor.RED + "HIGH USAGE";
            statusMaterial = getMaterialSafe("REDSTONE_BLOCK");
        } else if (usagePercent > 60) {
            status = ChatColor.YELLOW + "MODERATE USAGE";
            statusMaterial = getMaterialSafe("GOLD_BLOCK");
        } else {
            status = ChatColor.GREEN + "NORMAL USAGE";
            statusMaterial = getMaterialSafe("EMERALD_BLOCK");
        }
        
        gui.setItem(10, createItem(getMaterialSafe("DIAMOND"), ChatColor.GREEN + "Max Memory",
            Arrays.asList(ChatColor.WHITE + decimalFormat.format(maxMemMB) + " MB")));
        
        gui.setItem(12, createItem(getMaterialSafe("REDSTONE"), ChatColor.RED + "Used Memory",
            Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(usedMemMB) + " MB",
                ChatColor.GRAY + "(" + decimalFormat.format(usagePercent) + "%)"
            )));
        
        gui.setItem(14, createItem(getMaterialSafe("EMERALD"), ChatColor.AQUA + "Free Memory",
            Arrays.asList(ChatColor.WHITE + decimalFormat.format(freeMemMB) + " MB")));
        
        gui.setItem(16, createItem(statusMaterial, ChatColor.YELLOW + "Memory Status",
            Arrays.asList(status)));
        
        gui.setItem(22, createItem(getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Main Menu", null));
        
        player.openInventory(gui);
    }

    private void openCpuGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GOLD + "§8[§6TW§8] §6CPU Info");
        
        trackActiveGui(player, "cpu");
        
        int availableProcessors = osBean.getAvailableProcessors();
        double systemLoadAvg = osBean.getSystemLoadAverage();
        
        gui.setItem(10, createItem(getMaterialSafe("COMPARATOR"), ChatColor.YELLOW + "Processors",
            Arrays.asList(ChatColor.WHITE + String.valueOf(availableProcessors) + " cores")));
        
        if (systemLoadAvg >= 0) {
            double loadPercent = (systemLoadAvg / availableProcessors) * 100;
            String status;
            Material statusMaterial;
            
            if (loadPercent > 80) {
                status = ChatColor.RED + "VERY HIGH LOAD";
                statusMaterial = getMaterialSafe("REDSTONE_BLOCK");
            } else if (loadPercent > 60) {
                status = ChatColor.YELLOW + "HIGH LOAD";
                statusMaterial = getMaterialSafe("GOLD_BLOCK");
            } else if (loadPercent > 40) {
                status = ChatColor.YELLOW + "MODERATE LOAD";
                statusMaterial = getMaterialSafe("IRON_BLOCK");
            } else {
                status = ChatColor.GREEN + "NORMAL LOAD";
                statusMaterial = getMaterialSafe("EMERALD_BLOCK");
            }
            
            gui.setItem(12, createItem(getMaterialSafe("REDSTONE_TORCH"), ChatColor.RED + "System Load",
                Arrays.asList(
                    ChatColor.WHITE + decimalFormat.format(systemLoadAvg),
                    ChatColor.GRAY + "(" + decimalFormat.format(loadPercent) + "%)"
                )));
            
            gui.setItem(14, createItem(getMaterialSafe("TORCH"), ChatColor.AQUA + "Available Capacity",
                Arrays.asList(ChatColor.WHITE + decimalFormat.format(100 - loadPercent) + "%")));
            
            gui.setItem(16, createItem(statusMaterial, ChatColor.YELLOW + "CPU Status",
                Arrays.asList(status)));
        } else {
            gui.setItem(13, createItem(getMaterialSafe("BARRIER"), ChatColor.GRAY + "Load Info Unavailable",
                Arrays.asList(ChatColor.GRAY + "Not supported on this platform")));
        }
        
        gui.setItem(22, createItem(getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Main Menu", null));
        
        player.openInventory(gui);
    }

    private void openSystemGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_PURPLE + "§8[§6TW§8] §5System");
        
        trackActiveGui(player, "system");
        
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long usedMem = runtime.totalMemory() - runtime.freeMemory();
        double maxMemMB = bytesToMB(maxMem);
        double usedMemMB = bytesToMB(usedMem);
        double memUsagePercent = (usedMemMB / maxMemMB) * 100;
        
        int availableProcessors = osBean.getAvailableProcessors();
        double systemLoadAvg = osBean.getSystemLoadAverage();
        double cpuLoadPercent = -1;

        try {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            cpuLoadPercent = sunOsBean.getProcessCpuLoad() * 100;
        } catch (Exception e) {
            // Fallback for non-Sun JVMs
            if (systemLoadAvg >= 0) {
                cpuLoadPercent = (systemLoadAvg / availableProcessors) * 100;
            }
        }
        
        gui.setItem(10, createItem(getMaterialSafe("REDSTONE"), ChatColor.LIGHT_PURPLE + "Memory Usage",
            Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(usedMemMB) + " / " + decimalFormat.format(maxMemMB) + " MB",
                ChatColor.GRAY + "(" + decimalFormat.format(memUsagePercent) + "%)"
            )));
        
        gui.setItem(12, createItem(getMaterialSafe("COMPARATOR"), ChatColor.GOLD + "CPU Usage",
            cpuLoadPercent >= 0 ? Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(cpuLoadPercent) + "%",
                ChatColor.GRAY + "Load: " + (systemLoadAvg >= 0 ? decimalFormat.format(systemLoadAvg) : "N/A")
            ) : Arrays.asList(ChatColor.GRAY + "Not available")));
        
        gui.setItem(14, createItem(getMaterialSafe("COMMAND_BLOCK"), ChatColor.YELLOW + "Processors",
            Arrays.asList(ChatColor.WHITE + String.valueOf(availableProcessors) + " cores")));
        
        gui.setItem(16, createItem(getMaterialSafe("BOOK"), ChatColor.AQUA + "OS Information",
            Arrays.asList(
                ChatColor.WHITE + osBean.getName(),
                ChatColor.GRAY + "Version: " + osBean.getVersion(),
                ChatColor.GRAY + "Arch: " + osBean.getArch()
            )));
        
        gui.setItem(28, createItem(getMaterialSafe("ENCHANTED_BOOK"), ChatColor.GREEN + "Java Version",
            Arrays.asList(ChatColor.WHITE + System.getProperty("java.version"))));
        
        gui.setItem(30, createItem(Material.CLOCK, ChatColor.GREEN + "Current Time",
            Arrays.asList(ChatColor.WHITE + dateFormat.format(new Date()))));
        
        gui.setItem(32, createItem(getMaterialSafe("PAPER"), ChatColor.GRAY + "Data File",
            Arrays.asList(ChatColor.WHITE + dataFile.getPath())));
        
        gui.setItem(49, createItem(getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Main Menu", null));
        
        player.openInventory(gui);
    }

    private void openAdminGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 36, ChatColor.DARK_RED + "§8[§6TW§8] §4Admin");
        
        gui.setItem(10, createItem(getMaterialSafe("COMMAND_BLOCK"), ChatColor.RED + "Player Access",
            Arrays.asList(
                ChatColor.GRAY + "Manage player access to TickWatch",
                ChatColor.GRAY + "• Grant monitoring access",
                ChatColor.GRAY + "• Revoke player access",
                ChatColor.GRAY + "• Check access status"
            )));
        
        gui.setItem(12, createItem(getMaterialSafe("REDSTONE_BLOCK"), ChatColor.GOLD + "Server Control",
            Arrays.asList(
                ChatColor.GRAY + "Server management tools",
                ChatColor.GRAY + "• Reload plugin data",
                ChatColor.GRAY + "• Clear cached data",
                ChatColor.GRAY + "• Export reports"
            )));
        
        gui.setItem(14, createItem(getMaterialSafe("BOOK"), ChatColor.AQUA + "System Logs",
            Arrays.asList(
                ChatColor.GRAY + "View system information",
                ChatColor.GRAY + "• Plugin logs",
                ChatColor.GRAY + "• Performance history",
                ChatColor.GRAY + "• Error reports"
            )));
        
        gui.setItem(16, createItem(getMaterialSafe("PAPER"), ChatColor.GREEN + "Data Management",
            Arrays.asList(
                ChatColor.GRAY + "Manage plugin data",
                ChatColor.GRAY + "• Export JSON data",
                ChatColor.GRAY + "• Import settings",
                ChatColor.GRAY + "• Reset statistics"
            )));
        
        gui.setItem(22, createItem(getMaterialSafe("EMERALD"), ChatColor.YELLOW + "Online Players",
            Arrays.asList(
                ChatColor.GRAY + "Currently online: " + Bukkit.getOnlinePlayers().size(),
                ChatColor.GRAY + "Click to view player list"
            )));
        
        gui.setItem(31, createItem(getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Main Menu", null));
        
        player.openInventory(gui);
    }

    private void handleAdminCommand(Player admin, String[] args) {
        String subCommand = args[1].toLowerCase();
        
        switch (subCommand) {
            case "add":
            case "give":
                if (args.length >= 3) {
                    String targetPlayerName = args[2];
                    Player targetPlayer = Bukkit.getPlayer(targetPlayerName);
                    
                    if (targetPlayer != null) {
                        givePlayerAccess(admin, targetPlayer);
                    } else {
                        admin.sendMessage(ChatColor.RED + "Player '" + targetPlayerName + "' not found or offline!");
                        admin.sendMessage(ChatColor.YELLOW + "Note: Player must be online to grant access.");
                    }
                } else {
                    admin.sendMessage(ChatColor.YELLOW + "Usage: /tw admin add <playername>");
                }
                break;
                
            case "remove":
            case "revoke":
                if (args.length >= 3) {
                    String targetPlayerName = args[2];
                    Player targetPlayer = Bukkit.getPlayer(targetPlayerName);
                    
                    if (targetPlayer != null) {
                        removePlayerAccess(admin, targetPlayer);
                    } else {
                        admin.sendMessage(ChatColor.RED + "Player '" + targetPlayerName + "' not found or offline!");
                        admin.sendMessage(ChatColor.YELLOW + "Note: Player must be online to revoke access.");
                    }
                } else {
                    admin.sendMessage(ChatColor.YELLOW + "Usage: /tw admin remove <playername>");
                }
                break;
                
            case "check":
            case "status":
                if (args.length >= 3) {
                    String targetPlayerName = args[2];
                    Player targetPlayer = Bukkit.getPlayer(targetPlayerName);
                    
                    if (targetPlayer != null) {
                        checkPlayerAccess(admin, targetPlayer);
                    } else {
                        admin.sendMessage(ChatColor.RED + "Player '" + targetPlayerName + "' not found or offline!");
                    }
                } else {
                    admin.sendMessage(ChatColor.YELLOW + "Usage: /tw admin check <playername>");
                }
                break;
                
            case "reload":
                loadData();
                admin.sendMessage(ChatColor.GREEN + "TickWatch data reloaded successfully!");
                break;
                
            case "clear":
                lastReloadTime = System.currentTimeMillis();
                lastShutdownTime = 0;
                saveData();
                admin.sendMessage(ChatColor.GREEN + "TickWatch statistics cleared!");
                break;
                
            case "export":
                admin.sendMessage(ChatColor.GREEN + "Exporting data to JSON...");
                saveData();
                admin.sendMessage(ChatColor.GREEN + "Data exported to: " + dataFile.getPath());
                break;
                
            case "list":
            case "players":
                showOnlinePlayersList(admin);
                                 break;
                 
             default:
                 admin.sendMessage(ChatColor.YELLOW + "Usage: /tw admin [add|remove|check|reload|clear|export|list]");
                 admin.sendMessage(ChatColor.GRAY + "• add <player> - Give player access to TickWatch");
                 admin.sendMessage(ChatColor.GRAY + "• remove <player> - Remove player access");
                 admin.sendMessage(ChatColor.GRAY + "• check <player> - Check player access status");
                 break;
         }
    }

    private void givePlayerAccess(Player admin, Player targetPlayer) {
        if (targetPlayer.hasPermission("tickwatch.use")) {
            playSound(admin, "warning");
            admin.sendMessage(ChatColor.YELLOW + "Player " + ChatColor.WHITE + targetPlayer.getName() + 
                ChatColor.YELLOW + " already has access to TickWatch!");
        } else {
            targetPlayer.addAttachment(this, "tickwatch.use", true);
            
            playSound(admin, "success");
            playSound(targetPlayer, "success");
            
            admin.sendMessage(ChatColor.GREEN + "✓ Granted TickWatch access to " + ChatColor.WHITE + 
                targetPlayer.getName() + ChatColor.GREEN + "!");
            
            targetPlayer.sendMessage("");
            targetPlayer.sendMessage(ChatColor.GOLD + "=== TickWatch Access Granted ===");
            targetPlayer.sendMessage(ChatColor.GREEN + "You now have access to the server monitoring system!");
            targetPlayer.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.AQUA + "/tw" + 
                ChatColor.YELLOW + " to open the monitoring GUI.");
            targetPlayer.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.AQUA + "/tw help" + 
                ChatColor.YELLOW + " to see all available commands.");
            targetPlayer.sendMessage("");
            
            getLogger().info("Admin " + admin.getName() + " granted TickWatch access to " + targetPlayer.getName());
        }
    }

    private void removePlayerAccess(Player admin, Player targetPlayer) {
        if (!targetPlayer.hasPermission("tickwatch.use") || targetPlayer.isOp()) {
            playSound(admin, "warning");
            if (targetPlayer.isOp()) {
                admin.sendMessage(ChatColor.YELLOW + "Cannot remove access from OP player " + 
                    ChatColor.WHITE + targetPlayer.getName() + ChatColor.YELLOW + "!");
            } else {
                admin.sendMessage(ChatColor.YELLOW + "Player " + ChatColor.WHITE + targetPlayer.getName() + 
                    ChatColor.YELLOW + " doesn't have TickWatch access!");
            }
        } else {
            targetPlayer.addAttachment(this, "tickwatch.use", false);
            
            playSound(admin, "deny");
            playSound(targetPlayer, "deny");
            
            admin.sendMessage(ChatColor.RED + "✗ Removed TickWatch access from " + ChatColor.WHITE + 
                targetPlayer.getName() + ChatColor.RED + "!");
            
            targetPlayer.sendMessage("");
            targetPlayer.sendMessage(ChatColor.GOLD + "=== TickWatch Access Revoked ===");
            targetPlayer.sendMessage(ChatColor.RED + "Your access to the server monitoring system has been removed.");
            targetPlayer.sendMessage(ChatColor.GRAY + "Contact an administrator if you need access restored.");
            targetPlayer.sendMessage("");
            
            getLogger().info("Admin " + admin.getName() + " removed TickWatch access from " + targetPlayer.getName());
        }
    }

    private void checkPlayerAccess(Player admin, Player targetPlayer) {
        boolean hasAccess = targetPlayer.hasPermission("tickwatch.use");
        boolean isOp = targetPlayer.isOp();
        boolean isAdmin = targetPlayer.hasPermission("tickwatch.admin");
        
        admin.sendMessage("");
        admin.sendMessage(ChatColor.GOLD + "=== Access Status: " + targetPlayer.getName() + " ===");
        
        String accessStatus = hasAccess ? ChatColor.GREEN + "✓ GRANTED" : ChatColor.RED + "✗ DENIED";
        admin.sendMessage(ChatColor.YELLOW + "TickWatch Access: " + accessStatus);
        
        if (isOp) {
            admin.sendMessage(ChatColor.GOLD + "Status: " + ChatColor.RED + "OP Player" + 
                ChatColor.GRAY + " (Full Access)");
        } else if (isAdmin) {
            admin.sendMessage(ChatColor.GOLD + "Status: " + ChatColor.AQUA + "Admin" + 
                ChatColor.GRAY + " (Admin Access)");
        } else if (hasAccess) {
            admin.sendMessage(ChatColor.GOLD + "Status: " + ChatColor.GREEN + "User" + 
                ChatColor.GRAY + " (Basic Access)");
        } else {
            admin.sendMessage(ChatColor.GOLD + "Status: " + ChatColor.RED + "No Access");
        }
        
        admin.sendMessage(ChatColor.GRAY + "World: " + ChatColor.WHITE + targetPlayer.getWorld().getName());
        admin.sendMessage(ChatColor.GRAY + "Online: " + ChatColor.WHITE + 
            formatDuration(System.currentTimeMillis() - targetPlayer.getFirstPlayed()));
        admin.sendMessage("");
        
        if (!hasAccess && !isOp) {
            admin.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.AQUA + "/tw admin add " + 
                targetPlayer.getName() + ChatColor.YELLOW + " to grant access.");
        } else if (hasAccess && !isOp && !isAdmin) {
            admin.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.AQUA + "/tw admin remove " + 
                targetPlayer.getName() + ChatColor.YELLOW + " to revoke access.");
        }
    }

    private void openPlayerMonitorGUI(Player admin, Player targetPlayer) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.BLUE + "§8[§6TW§8] §9" + targetPlayer.getName());
        
        long playerOnlineTime = System.currentTimeMillis() - targetPlayer.getFirstPlayed();
        String onlineTime = formatDuration(playerOnlineTime);
        
        gui.setItem(10, createItem(getMaterialSafe("SKULL_ITEM"), ChatColor.GREEN + targetPlayer.getName(),
            Arrays.asList(
                ChatColor.GRAY + "Player Information",
                ChatColor.WHITE + "Online: " + (targetPlayer.isOnline() ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"),
                ChatColor.WHITE + "World: " + ChatColor.YELLOW + targetPlayer.getWorld().getName(),
                ChatColor.WHITE + "Game Mode: " + ChatColor.YELLOW + targetPlayer.getGameMode().toString()
            )));
        
        gui.setItem(12, createItem(Material.CLOCK, ChatColor.AQUA + "Time Statistics",
            Arrays.asList(
                ChatColor.WHITE + "First Played: " + dateFormat.format(new Date(targetPlayer.getFirstPlayed())),
                ChatColor.WHITE + "Last Played: " + dateFormat.format(new Date(targetPlayer.getLastPlayed())),
                ChatColor.WHITE + "Total Play Time: " + onlineTime
            )));
        
        gui.setItem(14, createItem(getMaterialSafe("COMPASS"), ChatColor.YELLOW + "Location Info",
            Arrays.asList(
                ChatColor.WHITE + "X: " + (int)targetPlayer.getLocation().getX(),
                ChatColor.WHITE + "Y: " + (int)targetPlayer.getLocation().getY(),
                ChatColor.WHITE + "Z: " + (int)targetPlayer.getLocation().getZ(),
                ChatColor.WHITE + "World: " + targetPlayer.getWorld().getName()
            )));
        
        gui.setItem(16, createItem(getMaterialSafe("DIAMOND_SWORD"), ChatColor.RED + "Player Stats",
            Arrays.asList(
                ChatColor.WHITE + "Health: " + (int)targetPlayer.getHealth() + "/20",
                ChatColor.WHITE + "Food: " + targetPlayer.getFoodLevel() + "/20",
                ChatColor.WHITE + "Level: " + targetPlayer.getLevel(),
                ChatColor.WHITE + "XP: " + (int)targetPlayer.getExp() * 100 + "%"
            )));
        
        gui.setItem(22, createItem(getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Admin Panel", null));
        
        admin.openInventory(gui);
    }

    private void showOnlinePlayersList(Player admin) {
        admin.sendMessage("");
        admin.sendMessage(ChatColor.GOLD + "=== Online Players (" + Bukkit.getOnlinePlayers().size() + ") ===");
        
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            admin.sendMessage(ChatColor.GRAY + "No players online.");
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                String status = player.isOp() ? ChatColor.RED + "[OP]" : ChatColor.GREEN + "[Player]";
                admin.sendMessage(ChatColor.GRAY + "- " + status + " " + ChatColor.WHITE + player.getName() + 
                    ChatColor.GRAY + " (" + player.getWorld().getName() + ")");
            }
        }
        
        admin.sendMessage("");
        admin.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.AQUA + "/tw admin player <name>" + 
            ChatColor.YELLOW + " to monitor a specific player.");
    }

    @EventHandler
    public void onPlayerChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        if (awaitingChatInput.containsKey(playerId)) {
            event.setCancelled(true);
            String inputType = awaitingChatInput.get(playerId);
            String message = event.getMessage().trim();
            
            if (message.equalsIgnoreCase("cancel")) {
                awaitingChatInput.remove(playerId);
                player.sendMessage(ChatColor.RED + "Operation cancelled.");
                Bukkit.getScheduler().runTask(this, () -> {
                    if (inputType.equals("check-interval")) {
                        openWebhookSettingsGUI(player);
                    } else if (inputType.equals("alert-cooldown")) {
                        openWebhookSettingsGUI(player);
                    } else if (inputType.contains("-")) {
                        String type = inputType.split("-")[0];
                        if (type.equals("tps")) openTPSThresholdsGUI(player);
                        else if (type.equals("memory")) openMemoryThresholdsGUI(player);
                        else if (type.equals("cpu")) openCPUThresholdsGUI(player);
                    }
                });
                return;
            }
            
            if (inputType.equals("check-interval")) {
                handleCheckIntervalInput(player, message);
            } else if (inputType.equals("alert-cooldown")) {
                handleAlertCooldownInput(player, message);
            } else if (inputType.contains("-")) {
                handleThresholdInput(player, message, inputType);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        
        Player player = (Player) event.getWhoClicked();
        
        if (!isValidTickWatchGUI(player, event.getInventory())) {
            return;
        }
        
        String title = getInventoryTitle(event.getInventory());
        
        event.setCancelled(true);
        
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) {
            return;
        }
        
        ItemStack item = event.getCurrentItem();
        String itemName = item.getItemMeta().getDisplayName();
        
        if (title.contains("Server Monitor")) {
            if (itemName.contains("Complete Dashboard")) {
                playSound(player, "gui_click");
                openCompleteDashboardGUI(player);
            } else if (itemName.contains("Performance Overview")) {
                playSound(player, "gui_click");
                openPerformanceGUI(player);
            } else if (itemName.contains("Help & Commands")) {
                playSound(player, "notification");
                player.closeInventory();
                showHelp(player);
            } else if (itemName.contains("Webhook Settings")) {
                playSound(player, "gui_click");
                openWebhookSettingsGUI(player);
            }

        } else if (title.contains("Webhook Settings")) {
            handleWebhookSettingsClick(player, itemName);
        } else if (title.contains("Alert Types")) {
            handleAlertTypesClick(player, itemName);
        } else if (title.contains("Thresholds")) {
            handleThresholdClick(player, itemName, title);
        } else if (itemName.contains("Back to Main Menu")) {
            playSound(player, "gui_click");
            openMainGUI(player);
        } else if (itemName.contains("Back to Admin Panel")) {
            playSound(player, "gui_click");
            openAdminGUI(player);
        } else if (itemName.contains("Close")) {
            playSound(player, "gui_close");
            player.closeInventory();
        } else if (itemName.contains("Close Dashboard")) {
            playSound(player, "gui_close");
            player.closeInventory();
        } else if (title.contains("Performance")) {
            if (itemName.contains("TPS Status")) {
                playSound(player, "gui_click");
                openTpsGUI(player);
            } else if (itemName.contains("Memory Usage")) {
                playSound(player, "gui_click");
                openMemoryGUI(player);
            } else if (itemName.contains("CPU Load")) {
                playSound(player, "gui_click");
                openCpuGUI(player);
            }
        }
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) {
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private Material getMaterialSafe(String materialName) {
        try {
            return Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            switch (materialName) {
                case "REDSTONE": return Material.valueOf("REDSTONE");
                case "COMPARATOR": return Material.valueOf("REDSTONE_COMPARATOR");
                case "COMMAND_BLOCK": 
                    try {
                        return Material.valueOf("COMMAND_BLOCK");
                    } catch (IllegalArgumentException ex) {
                        return Material.valueOf("COMMAND");
                    }
                case "PAPER": return Material.valueOf("PAPER");
                case "EMERALD": return Material.valueOf("EMERALD");
                case "REDSTONE_BLOCK": return Material.valueOf("REDSTONE_BLOCK");
                case "BARRIER": return Material.valueOf("BARRIER");
                case "ARROW": return Material.valueOf("ARROW");
                case "DIAMOND": return Material.valueOf("DIAMOND");
                case "EMERALD_BLOCK": return Material.valueOf("EMERALD_BLOCK");
                case "GOLD_BLOCK": return Material.valueOf("GOLD_BLOCK");
                case "IRON_BLOCK": return Material.valueOf("IRON_BLOCK");
                case "REDSTONE_TORCH": 
                    try {
                        return Material.valueOf("REDSTONE_TORCH");
                    } catch (IllegalArgumentException ex) {
                        return Material.valueOf("REDSTONE_TORCH_ON");
                    }
                case "TORCH": return Material.valueOf("TORCH");
                case "BOOK": return Material.valueOf("BOOK");
                case "ENCHANTED_BOOK": return Material.valueOf("ENCHANTED_BOOK");
                case "SKULL_ITEM": 
                    try {
                        return Material.valueOf("PLAYER_HEAD");
                    } catch (IllegalArgumentException ex) {
                        return Material.valueOf("SKULL_ITEM");
                    }
                case "COMPASS": return Material.valueOf("COMPASS");
                case "DIAMOND_SWORD": return Material.valueOf("DIAMOND_SWORD");
                case "NETHER_STAR": return Material.valueOf("NETHER_STAR");
                case "WATCH": return Material.valueOf("CLOCK");
                case "BELL": 
                    try {
                        return Material.valueOf("BELL");
                    } catch (IllegalArgumentException ex) {
                        return Material.valueOf("GOLD_BLOCK");
                    }
                case "HOPPER": return Material.valueOf("HOPPER");
                case "PISTON": return Material.valueOf("PISTON");
                case "TNT": return Material.valueOf("TNT");
                case "GOLD_INGOT": return Material.valueOf("GOLD_INGOT");
                default: return Material.STONE;
            }
        }
    }



    private void showAllInfoText(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== TickWatch Server Info ===");
        showCurrentTime(sender);
        showReloadTime(sender);
        showShutdownTime(sender);
        sender.sendMessage("");
        showMemoryInfo(sender);
        showCpuInfo(sender);
        sender.sendMessage(ChatColor.GRAY + "Data saved in: " + dataFile.getPath());
    }

    private void showCurrentTime(CommandSender sender) {
        String currentTime = dateFormat.format(new Date());
        sender.sendMessage(ChatColor.GREEN + "Current server time: " + ChatColor.WHITE + currentTime);
    }

    private void showReloadTime(CommandSender sender) {
        long timeSince = System.currentTimeMillis() - lastReloadTime;
        String reloadTime = dateFormat.format(new Date(lastReloadTime));
        String duration = formatDuration(timeSince);
        
        sender.sendMessage(ChatColor.AQUA + "Last reload: " + ChatColor.WHITE + reloadTime);
        sender.sendMessage(ChatColor.AQUA + "Time since reload: " + ChatColor.WHITE + duration);
    }

    private void showShutdownTime(CommandSender sender) {
        if (lastShutdownTime == 0) {
            sender.sendMessage(ChatColor.YELLOW + "No shutdown recorded since plugin start");
            return;
        }
        
        long timeSince = System.currentTimeMillis() - lastShutdownTime;
        String shutdownTime = dateFormat.format(new Date(lastShutdownTime));
        String duration = formatDuration(timeSince);
        
        sender.sendMessage(ChatColor.RED + "Last shutdown attempt: " + ChatColor.WHITE + shutdownTime);
        sender.sendMessage(ChatColor.RED + "Time since shutdown attempt: " + ChatColor.WHITE + duration);
    }

    private void showMemoryInfo(CommandSender sender) {
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        
        double maxMemMB = bytesToMB(maxMem);
        double totalMemMB = bytesToMB(totalMem);
        double usedMemMB = bytesToMB(usedMem);
        double freeMemMB = bytesToMB(freeMem);
        double usagePercent = (usedMemMB / maxMemMB) * 100;
        
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "=== Memory Info ===");
        sender.sendMessage(ChatColor.GREEN + "Max Memory: " + ChatColor.WHITE + decimalFormat.format(maxMemMB) + " MB");
        sender.sendMessage(ChatColor.RED + "Used Memory: " + ChatColor.WHITE + decimalFormat.format(usedMemMB) + " MB (" + decimalFormat.format(usagePercent) + "%)");
        sender.sendMessage(ChatColor.AQUA + "Free Memory: " + ChatColor.WHITE + decimalFormat.format(freeMemMB) + " MB");
        sender.sendMessage(ChatColor.YELLOW + "Total Allocated: " + ChatColor.WHITE + decimalFormat.format(totalMemMB) + " MB");
        
        if (usagePercent > 80) {
            sender.sendMessage(ChatColor.RED + "⚠ Memory usage is high!");
        } else if (usagePercent > 60) {
            sender.sendMessage(ChatColor.YELLOW + "⚠ Memory usage is moderate");
        } else {
            sender.sendMessage(ChatColor.GREEN + "✓ Memory usage is normal");
        }
    }

    private void showCpuInfo(CommandSender sender) {
        int availableProcessors = osBean.getAvailableProcessors();
        double systemLoadAvg = osBean.getSystemLoadAverage();
        
        sender.sendMessage(ChatColor.GOLD + "=== CPU Info ===");
        sender.sendMessage(ChatColor.YELLOW + "Available Processors: " + ChatColor.WHITE + availableProcessors);
        
        if (systemLoadAvg >= 0) {
            double loadPercent = (systemLoadAvg / availableProcessors) * 100;
            sender.sendMessage(ChatColor.RED + "System Load Average: " + ChatColor.WHITE + decimalFormat.format(systemLoadAvg));
            sender.sendMessage(ChatColor.RED + "Load Percentage: " + ChatColor.WHITE + decimalFormat.format(loadPercent) + "%");
            sender.sendMessage(ChatColor.AQUA + "Available CPU Capacity: " + ChatColor.WHITE + decimalFormat.format(100 - loadPercent) + "%");
            
            if (loadPercent > 80) {
                sender.sendMessage(ChatColor.RED + "⚠ CPU load is very high!");
            } else if (loadPercent > 60) {
                sender.sendMessage(ChatColor.YELLOW + "⚠ CPU load is high");
            } else if (loadPercent > 40) {
                sender.sendMessage(ChatColor.YELLOW + "⚠ CPU load is moderate");
            } else {
                sender.sendMessage(ChatColor.GREEN + "✓ CPU load is normal");
            }
        } else {
            sender.sendMessage(ChatColor.GRAY + "System load average: Not available on this platform");
        }
        
        try {
            com.sun.management.OperatingSystemMXBean sunOsBean = 
                (com.sun.management.OperatingSystemMXBean) osBean;
            double processCpuLoad = sunOsBean.getProcessCpuLoad() * 100;
            double systemCpuLoad = sunOsBean.getSystemCpuLoad() * 100;
            
            if (processCpuLoad >= 0) {
                sender.sendMessage(ChatColor.RED + "Process CPU Usage: " + ChatColor.WHITE + decimalFormat.format(processCpuLoad) + "%");
                sender.sendMessage(ChatColor.AQUA + "Free Process CPU: " + ChatColor.WHITE + decimalFormat.format(100 - processCpuLoad) + "%");
            }
            
            if (systemCpuLoad >= 0) {
                sender.sendMessage(ChatColor.RED + "System CPU Usage: " + ChatColor.WHITE + decimalFormat.format(systemCpuLoad) + "%");
                sender.sendMessage(ChatColor.AQUA + "Free System CPU: " + ChatColor.WHITE + decimalFormat.format(100 - systemCpuLoad) + "%");
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.GRAY + "Detailed CPU usage: Not available on this JVM");
        }
    }

    private double bytesToMB(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private void playSound(Player player, String soundType) {
        try {
            Sound sound;
            float volume = 0.5f;
            float pitch = 1.0f;
            
            switch (soundType.toLowerCase()) {
                case "gui_open":
                case "menu_open":
                    sound = getSoundSafe("UI_BUTTON_CLICK", "CLICK");
                    pitch = 1.2f;
                    break;
                case "gui_click":
                case "button_click":
                    sound = getSoundSafe("UI_BUTTON_CLICK", "CLICK");
                    volume = 0.3f;
                    break;
                case "success":
                case "grant":
                    sound = getSoundSafe("ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
                    pitch = 1.5f;
                    break;
                case "error":
                case "deny":
                    sound = getSoundSafe("ENTITY_VILLAGER_NO", "VILLAGER_NO");
                    volume = 0.4f;
                    break;
                case "warning":
                    sound = getSoundSafe("ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
                    volume = 0.6f;
                    pitch = 0.5f;
                    break;
                case "admin":
                    sound = getSoundSafe("ENTITY_ENDER_DRAGON_GROWL", "ENDERDRAGON_GROWL");
                    volume = 0.3f;
                    pitch = 1.8f;
                    break;
                case "notification":
                    sound = getSoundSafe("ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
                    volume = 0.4f;
                    break;
                case "save":
                    sound = getSoundSafe("ENTITY_ITEM_PICKUP", "ITEM_PICKUP");
                    pitch = 1.3f;
                    break;
                case "reload":
                    sound = getSoundSafe("BLOCK_ANVIL_USE", "ANVIL_USE");
                    volume = 0.3f;
                    pitch = 1.5f;
                    break;
                default:
                    sound = getSoundSafe("UI_BUTTON_CLICK", "CLICK");
                    break;
            }
            
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception e) {
            // Silently fail if sound doesn't work
        }
    }

    private Sound getSoundSafe(String modernSound, String legacySound) {
        try {
            return Sound.valueOf(modernSound);
        } catch (IllegalArgumentException e) {
            try {
                return Sound.valueOf(legacySound);
            } catch (IllegalArgumentException e2) {
                try {
                    return Sound.valueOf("CLICK");
                } catch (IllegalArgumentException e3) {
                    return Sound.values()[0]; // Fallback to first available sound
                }
            }
        }
    }

    private String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format("%dd %dh %dm %ds", days, hours % 24, minutes % 60, seconds % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String cmd = event.getMessage().toLowerCase();
        
        if (cmd.startsWith("/reload") || cmd.equals("/rl")) {
            lastReloadTime = System.currentTimeMillis();
            saveData();
        } else if (cmd.startsWith("/stop")) {
            lastShutdownTime = System.currentTimeMillis();
            saveData();
        }
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        String cmd = event.getCommand().toLowerCase();
        
        if (cmd.startsWith("reload") || cmd.equals("rl")) {
            lastReloadTime = System.currentTimeMillis();
            saveData();
        } else if (cmd.startsWith("stop")) {
            lastShutdownTime = System.currentTimeMillis();
            saveData();
        }
    }

    private void startTpsTracking() {
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                tpsHistory[tpsIndex] = currentTime;
                tpsIndex = (tpsIndex + 1) % tpsHistory.length;
                lastTpsCheck = currentTime;
            }
        }, 0L, 1L);
    }

    private double getCurrentTPS() {
        try {
            return getServerTPS()[0];
        } catch (Exception e) {
            return calculateTpsFromHistory();
        }
    }

    private double[] getServerTPS() {
        try {
            Object server = Bukkit.getServer();
            
            try {
                Object handle = server.getClass().getMethod("getServer").invoke(server);
                double[] recentTps = (double[]) handle.getClass().getField("recentTps").get(handle);
                return recentTps;
            } catch (Exception e1) {
                try {
                    Class<?> serverClass = Class.forName("net.minecraft.server." + getNMSVersion() + ".MinecraftServer");
                    Object minecraftServer = serverClass.getMethod("getServer").invoke(null);
                    double[] recentTps = (double[]) serverClass.getField("recentTps").get(minecraftServer);
                    return recentTps;
                } catch (Exception e2) {
                    try {
                        Object craftServer = server;
                        Object dedicatedServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
                        double[] recentTps = (double[]) dedicatedServer.getClass().getField("recentTps").get(dedicatedServer);
                        return recentTps;
                    } catch (Exception e3) {
                        return new double[]{20.0, 20.0, 20.0};
                    }
                }
            }
        } catch (Exception e) {
            return new double[]{20.0, 20.0, 20.0};
        }
    }

    private double calculateTpsFromHistory() {
        if (tpsHistory[0] == 0) return 20.0;
        
        long currentTime = System.currentTimeMillis();
        int validSamples = 0;
        long totalTime = 0;
        
        for (int i = 0; i < tpsHistory.length; i++) {
            if (tpsHistory[i] > 0) {
                validSamples++;
                if (i > 0 && tpsHistory[i - 1] > 0) {
                    totalTime += tpsHistory[i] - tpsHistory[i - 1];
                }
            }
        }
        
        if (validSamples < 2) return 20.0;
        
        double avgTickTime = (double) totalTime / (validSamples - 1);
        return Math.min(20.0, 1000.0 / Math.max(avgTickTime, 50.0));
    }

    private double getAverageTPS(int seconds) {
        try {
            double[] tpsArray = getServerTPS();
            
            if (seconds <= 60) {
                return Math.min(20.0, tpsArray[0]);
            } else if (seconds <= 300) {
                return Math.min(20.0, tpsArray[1]);
            } else {
                return Math.min(20.0, tpsArray[2]);
            }
        } catch (Exception e) {
            return getCurrentTPS();
        }
    }

    private String getNMSVersion() {
        String version = Bukkit.getServer().getClass().getPackage().getName();
        return version.substring(version.lastIndexOf('.') + 1);
    }

    private void openTpsGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "§8[§6TW§8] §2TPS");
        
        trackActiveGui(player, "tps");

        double currentTps = getCurrentTPS();
        double avgTps1m = getAverageTPS(60);
        double avgTps15m = getAverageTPS(900);
        
        String tpsStatus = getTpsStatus(currentTps);
        ChatColor statusColor = getTpsColor(currentTps);

        inventory.setItem(10, createItem(getMaterialSafe("CLOCK"), 
            statusColor + "Current TPS", 
            Arrays.asList(
                ChatColor.WHITE + "Current: " + statusColor + String.format("%.2f", currentTps),
                ChatColor.WHITE + "Status: " + statusColor + tpsStatus,
                "",
                ChatColor.GRAY + "20.0 TPS = Perfect",
                ChatColor.GRAY + "15+ TPS = Good", 
                ChatColor.GRAY + "10+ TPS = Acceptable",
                ChatColor.GRAY + "< 10 TPS = Poor"
            )));

        inventory.setItem(12, createItem(getMaterialSafe("COMPASS"), 
            ChatColor.YELLOW + "Average TPS (1m)", 
            Arrays.asList(
                ChatColor.WHITE + "1 Minute: " + getTpsColor(avgTps1m) + String.format("%.2f", avgTps1m),
                ChatColor.WHITE + "Status: " + getTpsColor(avgTps1m) + getTpsStatus(avgTps1m),
                "",
                ChatColor.GRAY + "Based on last 60 seconds"
            )));

        inventory.setItem(14, createItem(getMaterialSafe("WATCH"), 
            ChatColor.AQUA + "Average TPS (15m)", 
            Arrays.asList(
                ChatColor.WHITE + "15 Minutes: " + getTpsColor(avgTps15m) + String.format("%.2f", avgTps15m),
                ChatColor.WHITE + "Status: " + getTpsColor(avgTps15m) + getTpsStatus(avgTps15m),
                "",
                ChatColor.GRAY + "Based on last 15 minutes"
            )));

        inventory.setItem(16, createItem(getMaterialSafe("REDSTONE"), 
            ChatColor.RED + "Server Performance", 
            Arrays.asList(
                ChatColor.WHITE + "Tick Rate: " + statusColor + String.format("%.1f", currentTps * 2.5) + "ms",
                ChatColor.WHITE + "Expected: " + ChatColor.GREEN + "50.0ms",
                ChatColor.WHITE + "Deviation: " + getDeviationColor(currentTps) + String.format("%.1f", Math.abs(50 - (currentTps * 2.5))) + "ms",
                "",
                ChatColor.GRAY + "Lower deviation = better"
            )));

        inventory.setItem(22, createItem(getMaterialSafe("BARRIER"), ChatColor.RED + "Close", 
            Arrays.asList(ChatColor.GRAY + "Click to close this menu")));

        player.openInventory(inventory);
    }

    private void openPerformanceGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 45, ChatColor.GOLD + "§8[§6TW§8] §ePerformance");
        
        trackActiveGui(player, "performance");

        double tps = getCurrentTPS();
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        double memUsage = (double) usedMem / maxMem * 100;

        inventory.setItem(10, createItem(getMaterialSafe("CLOCK"), 
            getTpsColor(tps) + "TPS Status", 
            Arrays.asList(
                ChatColor.WHITE + "Current TPS: " + getTpsColor(tps) + String.format("%.2f", tps),
                ChatColor.WHITE + "Status: " + getTpsColor(tps) + getTpsStatus(tps),
                "",
                ChatColor.GRAY + "Click for detailed TPS info"
            )));

        inventory.setItem(12, createItem(getMaterialSafe("REDSTONE"), 
            getMemoryColor(memUsage) + "Memory Usage", 
            Arrays.asList(
                ChatColor.WHITE + "Used: " + getMemoryColor(memUsage) + String.format("%.1f", memUsage) + "%",
                ChatColor.WHITE + "Amount: " + ChatColor.YELLOW + String.format("%.0f", bytesToMB(usedMem)) + "MB",
                ChatColor.WHITE + "Available: " + ChatColor.GREEN + String.format("%.0f", bytesToMB(maxMem)) + "MB",
                "",
                ChatColor.GRAY + "Click for detailed memory info"
            )));

        inventory.setItem(14, createItem(getMaterialSafe("COMPARATOR"), 
            ChatColor.AQUA + "CPU Load", 
            Arrays.asList(
                ChatColor.WHITE + "System Load: " + getCpuColor() + String.format("%.1f", getCpuLoadPercentage()) + "%",
                ChatColor.WHITE + "Processors: " + ChatColor.YELLOW + String.valueOf(Runtime.getRuntime().availableProcessors()),
                "",
                ChatColor.GRAY + "Click for detailed CPU info"
            )));

        inventory.setItem(16, createItem(getMaterialSafe("PAPER"), 
            ChatColor.YELLOW + "Server Info", 
            Arrays.asList(
                ChatColor.WHITE + "Uptime: " + ChatColor.GREEN + formatDuration(System.currentTimeMillis() - startTime),
                ChatColor.WHITE + "Players: " + ChatColor.AQUA + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(),
                ChatColor.WHITE + "Worlds: " + ChatColor.LIGHT_PURPLE + Bukkit.getWorlds().size(),
                "",
                ChatColor.GRAY + "General server statistics"
            )));

        String overallStatus = getOverallStatus(tps, memUsage);
        ChatColor overallColor = getOverallStatusColor(tps, memUsage);

        inventory.setItem(22, createItem(getMaterialSafe("BEACON"), 
            overallColor + "Overall Status", 
            Arrays.asList(
                ChatColor.WHITE + "Status: " + overallColor + overallStatus,
                "",
                ChatColor.GRAY + "Combined performance rating"
            )));

        inventory.setItem(40, createItem(getMaterialSafe("BARRIER"), ChatColor.RED + "Close", 
            Arrays.asList(ChatColor.GRAY + "Click to close this menu")));

        player.openInventory(inventory);
    }

    private void openCompleteDashboardGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, ChatColor.LIGHT_PURPLE + "§8[§6TW§8] §dComplete Dashboard");
        
        trackActiveGui(player, "dashboard");

        double tps = getCurrentTPS();
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        double memUsage = (double) usedMem / maxMem * 100;
        double cpuLoad = getCpuLoadPercentage();

        inventory.setItem(10, createItem(getMaterialSafe("CLOCK"), 
            getTpsColor(tps) + "Server TPS", 
            Arrays.asList(
                ChatColor.WHITE + "Current: " + getTpsColor(tps) + String.format("%.2f", tps),
                ChatColor.WHITE + "Status: " + getTpsColor(tps) + getTpsStatus(tps),
                ChatColor.WHITE + "1m Avg: " + getTpsColor(getAverageTPS(60)) + String.format("%.2f", getAverageTPS(60)),
                ChatColor.WHITE + "15m Avg: " + getTpsColor(getAverageTPS(900)) + String.format("%.2f", getAverageTPS(900)),
                "",
                ChatColor.YELLOW + "🔄 Live updating every 3s"
            )));

        inventory.setItem(11, createItem(getMaterialSafe("REDSTONE"), 
            getMemoryColor(memUsage) + "Memory Status", 
            Arrays.asList(
                ChatColor.WHITE + "Usage: " + getMemoryColor(memUsage) + String.format("%.1f", memUsage) + "%",
                ChatColor.WHITE + "Used: " + ChatColor.YELLOW + String.format("%.0f", bytesToMB(usedMem)) + "MB",
                ChatColor.WHITE + "Free: " + ChatColor.GREEN + String.format("%.0f", bytesToMB(freeMem)) + "MB",
                ChatColor.WHITE + "Max: " + ChatColor.AQUA + String.format("%.0f", bytesToMB(maxMem)) + "MB",
                "",
                ChatColor.YELLOW + "🔄 Real-time monitoring"
            )));

        inventory.setItem(12, createItem(getMaterialSafe("COMPARATOR"), 
            getCpuColor() + "CPU Performance", 
            Arrays.asList(
                ChatColor.WHITE + "Load: " + getCpuColor() + String.format("%.1f", cpuLoad) + "%",
                ChatColor.WHITE + "Cores: " + ChatColor.YELLOW + Runtime.getRuntime().availableProcessors(),
                ChatColor.WHITE + "Available: " + ChatColor.GREEN + String.format("%.1f", 100 - cpuLoad) + "%",
                "",
                ChatColor.YELLOW + "🔄 Live CPU monitoring"
            )));

        inventory.setItem(13, createItem(getMaterialSafe("PAPER"), 
            ChatColor.AQUA + "Server Statistics", 
            Arrays.asList(
                ChatColor.WHITE + "Uptime: " + ChatColor.GREEN + formatDuration(System.currentTimeMillis() - startTime),
                ChatColor.WHITE + "Players: " + ChatColor.AQUA + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(),
                ChatColor.WHITE + "Worlds: " + ChatColor.LIGHT_PURPLE + Bukkit.getWorlds().size(),
                ChatColor.WHITE + "Plugins: " + ChatColor.GOLD + Bukkit.getPluginManager().getPlugins().length,
                "",
                ChatColor.YELLOW + "🔄 Live server data"
            )));

        String overallStatus = getOverallStatus(tps, memUsage);
        ChatColor overallColor = getOverallStatusColor(tps, memUsage);

        inventory.setItem(14, createItem(getMaterialSafe("BEACON"), 
            overallColor + "System Health", 
            Arrays.asList(
                ChatColor.WHITE + "Overall: " + overallColor + overallStatus,
                ChatColor.WHITE + "TPS: " + getTpsColor(tps) + getTpsStatus(tps),
                ChatColor.WHITE + "Memory: " + getMemoryColor(memUsage) + (memUsage <= 50 ? "Healthy" : memUsage <= 80 ? "Warning" : "Critical"),
                ChatColor.WHITE + "CPU: " + getCpuColor() + (cpuLoad <= 50 ? "Normal" : cpuLoad <= 80 ? "High" : "Critical"),
                "",
                ChatColor.YELLOW + "🔄 Continuous health check"
            )));

        inventory.setItem(16, createItem(getMaterialSafe("EMERALD"), 
            ChatColor.GREEN + "Current Time", 
            Arrays.asList(
                ChatColor.WHITE + dateFormat.format(new Date()),
                ChatColor.WHITE + "Last Reload: " + formatDuration(System.currentTimeMillis() - lastReloadTime) + " ago",
                "",
                ChatColor.YELLOW + "🔄 Live time display"
            )));

        inventory.setItem(20, createItem(getMaterialSafe("DIAMOND_SWORD"), 
            ChatColor.GOLD + "Performance Metrics", 
            Arrays.asList(
                ChatColor.WHITE + "Tick Rate: " + getTpsColor(tps) + String.format("%.1f", tps * 2.5) + "ms",
                ChatColor.WHITE + "Expected: " + ChatColor.GREEN + "50.0ms",
                ChatColor.WHITE + "Deviation: " + getDeviationColor(tps) + String.format("%.1f", Math.abs(50 - (tps * 2.5))) + "ms",
                ChatColor.WHITE + "Efficiency: " + getTpsColor(tps) + String.format("%.1f", (tps / 20.0) * 100) + "%",
                "",
                ChatColor.YELLOW + "🔄 Real-time calculations"
            )));

        inventory.setItem(21, createItem(getMaterialSafe("NETHER_STAR"), 
            ChatColor.LIGHT_PURPLE + "System Resources", 
            Arrays.asList(
                ChatColor.WHITE + "JVM Version: " + ChatColor.YELLOW + System.getProperty("java.version"),
                ChatColor.WHITE + "OS: " + ChatColor.AQUA + System.getProperty("os.name"),
                ChatColor.WHITE + "Architecture: " + ChatColor.GREEN + System.getProperty("os.arch"),
                ChatColor.WHITE + "Server Version: " + ChatColor.GOLD + Bukkit.getVersion().split("-")[0],
                "",
                ChatColor.GRAY + "Static system information"
            )));

        inventory.setItem(22, createItem(getMaterialSafe("WATCH"), 
            ChatColor.YELLOW + "Live Performance Graph", 
            Arrays.asList(
                ChatColor.WHITE + "TPS Trend: " + getTpsGraphBar(tps),
                ChatColor.WHITE + "Memory Trend: " + getMemoryGraphBar(memUsage),
                ChatColor.WHITE + "CPU Trend: " + getCpuGraphBar(cpuLoad),
                "",
                ChatColor.GRAY + "Visual performance indicators",
                ChatColor.YELLOW + "🔄 Updates every 2 seconds"
            )));

        inventory.setItem(23, createItem(getMaterialSafe("BOOK"), 
            ChatColor.AQUA + "Detailed Analytics", 
            Arrays.asList(
                ChatColor.WHITE + "Avg TPS (5m): " + getTpsColor(getAverageTPS(300)) + String.format("%.2f", getAverageTPS(300)),
                ChatColor.WHITE + "Memory Efficiency: " + getMemoryColor(memUsage) + String.format("%.1f", 100 - memUsage) + "%",
                ChatColor.WHITE + "System Stability: " + getStabilityRating(tps, memUsage, cpuLoad),
                "",
                ChatColor.YELLOW + "🔄 Advanced metrics"
            )));

        inventory.setItem(24, createItem(getMaterialSafe("REDSTONE_TORCH"), 
            ChatColor.RED + "Alert Status", 
            Arrays.asList(
                ChatColor.WHITE + "TPS Alert: " + (tps < 15 ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "NORMAL"),
                ChatColor.WHITE + "Memory Alert: " + (memUsage > 80 ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "NORMAL"),
                ChatColor.WHITE + "CPU Alert: " + (cpuLoad > 80 ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "NORMAL"),
                "",
                ChatColor.YELLOW + "🔄 Live alert monitoring"
            )));

        inventory.setItem(49, createItem(getMaterialSafe("BARRIER"), ChatColor.RED + "Close Dashboard", 
            Arrays.asList(ChatColor.GRAY + "Click to close this dashboard")));

        player.openInventory(inventory);
    }

    private String getTpsGraphBar(double tps) {
        int bars = (int) Math.round((tps / 20.0) * 10);
        StringBuilder graph = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i < bars) {
                graph.append(ChatColor.GREEN + "█");
            } else {
                graph.append(ChatColor.GRAY + "█");
            }
        }
        return graph.toString();
    }

    private String getMemoryGraphBar(double memUsage) {
        int bars = (int) Math.round((memUsage / 100.0) * 10);
        StringBuilder graph = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i < bars) {
                if (memUsage <= 50) graph.append(ChatColor.GREEN + "█");
                else if (memUsage <= 80) graph.append(ChatColor.YELLOW + "█");
                else graph.append(ChatColor.RED + "█");
            } else {
                graph.append(ChatColor.GRAY + "█");
            }
        }
        return graph.toString();
    }

    private String getCpuGraphBar(double cpuLoad) {
        int bars = (int) Math.round((cpuLoad / 100.0) * 10);
        StringBuilder graph = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i < bars) {
                if (cpuLoad <= 50) graph.append(ChatColor.GREEN + "█");
                else if (cpuLoad <= 80) graph.append(ChatColor.YELLOW + "█");
                else graph.append(ChatColor.RED + "█");
            } else {
                graph.append(ChatColor.GRAY + "█");
            }
        }
        return graph.toString();
    }

    private String getStabilityRating(double tps, double memUsage, double cpuLoad) {
        int score = 0;
        if (tps >= 18) score += 3;
        else if (tps >= 15) score += 2;
        else if (tps >= 12) score += 1;
        
        if (memUsage <= 50) score += 3;
        else if (memUsage <= 70) score += 2;
        else if (memUsage <= 85) score += 1;
        
        if (cpuLoad <= 50) score += 3;
        else if (cpuLoad <= 70) score += 2;
        else if (cpuLoad <= 85) score += 1;
        
        if (score >= 8) return ChatColor.DARK_GREEN + "Excellent";
        if (score >= 6) return ChatColor.GREEN + "Good";
        if (score >= 4) return ChatColor.YELLOW + "Fair";
        if (score >= 2) return ChatColor.GOLD + "Poor";
        return ChatColor.RED + "Critical";
    }

    private void showPingInfo(Player player) {
        try {
            Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);
            int ping = (Integer) craftPlayer.getClass().getField("ping").get(craftPlayer);
            
            String pingStatus = getPingStatus(ping);
            ChatColor pingColor = getPingColor(ping);
            
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "==================== " + ChatColor.YELLOW + "Ping Information" + ChatColor.GOLD + " ====================");
            player.sendMessage("");
            player.sendMessage(ChatColor.AQUA + "Your Connection:");
            player.sendMessage(ChatColor.WHITE + "• Ping: " + pingColor + ping + "ms");
            player.sendMessage(ChatColor.WHITE + "• Status: " + pingColor + pingStatus);
            player.sendMessage("");
            player.sendMessage(ChatColor.YELLOW + "Ping Guide:");
            player.sendMessage(ChatColor.GREEN + "• 0-50ms: Excellent");
            player.sendMessage(ChatColor.YELLOW + "• 51-100ms: Good");
            player.sendMessage(ChatColor.GOLD + "• 101-200ms: Fair");
            player.sendMessage(ChatColor.RED + "• 201ms+: Poor");
            player.sendMessage("");
            
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Unable to retrieve ping information!");
        }
    }

    private void showTpsText(Player player) {
        try {
            double[] tpsArray = getServerTPS();
            double tps1m = Math.min(20.0, tpsArray[0]);
            double tps5m = Math.min(20.0, tpsArray[1]);
            double tps15m = Math.min(20.0, tpsArray[2]);
            
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "==================== " + ChatColor.YELLOW + "TPS Information" + ChatColor.GOLD + " ====================");
            player.sendMessage("");
            player.sendMessage(ChatColor.AQUA + "Server TPS (Ticks Per Second):");
            player.sendMessage(ChatColor.WHITE + "• 1 Minute: " + getTpsColor(tps1m) + String.format("%.2f", tps1m) + ChatColor.WHITE + " (" + getTpsStatus(tps1m) + ChatColor.WHITE + ")");
            player.sendMessage(ChatColor.WHITE + "• 5 Minutes: " + getTpsColor(tps5m) + String.format("%.2f", tps5m) + ChatColor.WHITE + " (" + getTpsStatus(tps5m) + ChatColor.WHITE + ")");
            player.sendMessage(ChatColor.WHITE + "• 15 Minutes: " + getTpsColor(tps15m) + String.format("%.2f", tps15m) + ChatColor.WHITE + " (" + getTpsStatus(tps15m) + ChatColor.WHITE + ")");
            player.sendMessage("");
            player.sendMessage(ChatColor.YELLOW + "TPS Guide:");
            player.sendMessage(ChatColor.DARK_GREEN + "• 20.0 TPS: Perfect");
            player.sendMessage(ChatColor.GREEN + "• 18+ TPS: Excellent");
            player.sendMessage(ChatColor.YELLOW + "• 15+ TPS: Good");
            player.sendMessage(ChatColor.GOLD + "• 12+ TPS: Fair");
            player.sendMessage(ChatColor.RED + "• 10+ TPS: Poor");
            player.sendMessage(ChatColor.DARK_RED + "• <10 TPS: Critical");
            player.sendMessage("");
            
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Unable to retrieve TPS information!");
            player.sendMessage(ChatColor.GRAY + "Error: " + e.getMessage());
        }
    }

    private String getTpsStatus(double tps) {
        if (tps >= 19.5) return "Perfect";
        if (tps >= 18.0) return "Excellent";
        if (tps >= 15.0) return "Good";
        if (tps >= 12.0) return "Fair";
        if (tps >= 10.0) return "Poor";
        return "Critical";
    }

    private ChatColor getTpsColor(double tps) {
        if (tps >= 19.5) return ChatColor.DARK_GREEN;
        if (tps >= 18.0) return ChatColor.GREEN;
        if (tps >= 15.0) return ChatColor.YELLOW;
        if (tps >= 12.0) return ChatColor.GOLD;
        if (tps >= 10.0) return ChatColor.RED;
        return ChatColor.DARK_RED;
    }

    private ChatColor getDeviationColor(double tps) {
        double deviation = Math.abs(50 - (tps * 2.5));
        if (deviation <= 5) return ChatColor.GREEN;
        if (deviation <= 15) return ChatColor.YELLOW;
        if (deviation <= 30) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    private ChatColor getMemoryColor(double memUsage) {
        if (memUsage <= 50) return ChatColor.GREEN;
        if (memUsage <= 70) return ChatColor.YELLOW;
        if (memUsage <= 85) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    private ChatColor getCpuColor() {
        double cpuLoad = getCpuLoadPercentage();
        if (cpuLoad <= 30) return ChatColor.GREEN;
        if (cpuLoad <= 50) return ChatColor.YELLOW;
        if (cpuLoad <= 75) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    private double getCpuLoadPercentage() {
        try {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double processCpuLoad = sunOsBean.getProcessCpuLoad() * 100;
            return processCpuLoad < 0 ? 0 : processCpuLoad;
        } catch (Exception e) {
            // Fallback for other JVMs
        try {
            double load = osBean.getSystemLoadAverage();
            if (load < 0) return 0;
            return Math.min(100, (load / Runtime.getRuntime().availableProcessors()) * 100);
            } catch (Exception e2) {
            return 0;
            }
        }
    }

    private String getPingStatus(int ping) {
        if (ping <= 50) return "Excellent";
        if (ping <= 100) return "Good";
        if (ping <= 200) return "Fair";
        return "Poor";
    }

    private ChatColor getPingColor(int ping) {
        if (ping <= 50) return ChatColor.GREEN;
        if (ping <= 100) return ChatColor.YELLOW;
        if (ping <= 200) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    private String getOverallStatus(double tps, double memUsage) {
        int score = 0;
        if (tps >= 18.0) score += 2;
        else if (tps >= 15.0) score += 1;
        
        if (memUsage <= 50) score += 2;
        else if (memUsage <= 70) score += 1;
        
        if (score >= 4) return "Excellent";
        if (score >= 3) return "Good";
        if (score >= 2) return "Fair";
        if (score >= 1) return "Poor";
        return "Critical";
    }

    private ChatColor getOverallStatusColor(double tps, double memUsage) {
        String status = getOverallStatus(tps, memUsage);
        switch (status) {
            case "Excellent": return ChatColor.DARK_GREEN;
            case "Good": return ChatColor.GREEN;
            case "Fair": return ChatColor.YELLOW;
            case "Poor": return ChatColor.GOLD;
            default: return ChatColor.RED;
        }
    }

    private void startRealtimeUpdates() {
        updateTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                updateActiveGuis();
                checkAndSendWarnings();
            }
        }, 40L, 40L);
    }

    private void updateActiveGuis() {
        if (activeGuis.isEmpty()) return;
        
        for (Map.Entry<Player, String> entry : new HashMap<>(activeGuis).entrySet()) {
            Player player = entry.getKey();
            String guiType = entry.getValue();
            
            if (!player.isOnline()) {
                activeGuis.remove(player);
                continue;
            }
            
            if (player.getOpenInventory() == null || 
                !isValidTickWatchGUI(player, player.getOpenInventory().getTopInventory())) {
                activeGuis.remove(player);
                continue;
            }
            
            try {
                switch (guiType.toLowerCase()) {
                    case "tps":
                        updateTpsGUI(player);
                        break;
                    case "performance":
                        updatePerformanceGUI(player);
                        break;
                    case "memory":
                        updateMemoryGUI(player);
                        break;
                    case "cpu":
                        updateCpuGUI(player);
                        break;
                    case "system":
                        updateSystemGUI(player);
                        break;
                    case "dashboard":
                        updateCompleteDashboardGUI(player);
                        break;
                }
            } catch (Exception e) {
                activeGuis.remove(player);
            }
        }
    }

    private void updateTpsGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        
        double currentTps = getCurrentTPS();
        double avgTps1m = getAverageTPS(60);
        double avgTps15m = getAverageTPS(900);
        
        String tpsStatus = getTpsStatus(currentTps);
        ChatColor statusColor = getTpsColor(currentTps);

        inventory.setItem(10, createItem(getMaterialSafe("CLOCK"), 
            statusColor + "Current TPS", 
            Arrays.asList(
                ChatColor.WHITE + "Current: " + statusColor + String.format("%.2f", currentTps),
                ChatColor.WHITE + "Status: " + statusColor + tpsStatus,
                "",
                ChatColor.GRAY + "20.0 TPS = Perfect",
                ChatColor.GRAY + "15+ TPS = Good", 
                ChatColor.GRAY + "10+ TPS = Acceptable",
                ChatColor.GRAY + "< 10 TPS = Poor",
                "",
                ChatColor.YELLOW + "🔄 Auto-updating every 2 seconds"
            )));

        inventory.setItem(12, createItem(getMaterialSafe("COMPASS"), 
            ChatColor.YELLOW + "Average TPS (1m)", 
            Arrays.asList(
                ChatColor.WHITE + "1 Minute: " + getTpsColor(avgTps1m) + String.format("%.2f", avgTps1m),
                ChatColor.WHITE + "Status: " + getTpsColor(avgTps1m) + getTpsStatus(avgTps1m),
                "",
                ChatColor.GRAY + "Based on last 60 seconds",
                ChatColor.YELLOW + "🔄 Auto-updating"
            )));

        inventory.setItem(14, createItem(getMaterialSafe("WATCH"), 
            ChatColor.AQUA + "Average TPS (15m)", 
            Arrays.asList(
                ChatColor.WHITE + "15 Minutes: " + getTpsColor(avgTps15m) + String.format("%.2f", avgTps15m),
                ChatColor.WHITE + "Status: " + getTpsColor(avgTps15m) + getTpsStatus(avgTps15m),
                "",
                ChatColor.GRAY + "Based on last 15 minutes",
                ChatColor.YELLOW + "🔄 Auto-updating"
            )));

        inventory.setItem(16, createItem(getMaterialSafe("REDSTONE"), 
            ChatColor.RED + "Server Performance", 
            Arrays.asList(
                ChatColor.WHITE + "Tick Rate: " + statusColor + String.format("%.1f", currentTps * 2.5) + "ms",
                ChatColor.WHITE + "Expected: " + ChatColor.GREEN + "50.0ms",
                ChatColor.WHITE + "Deviation: " + getDeviationColor(currentTps) + String.format("%.1f", Math.abs(50 - (currentTps * 2.5))) + "ms",
                "",
                ChatColor.GRAY + "Lower deviation = better",
                ChatColor.YELLOW + "🔄 Real-time updates"
            )));
    }

    private void updatePerformanceGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();

        double tps = getCurrentTPS();
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        double memUsage = (double) usedMem / maxMem * 100;

        inventory.setItem(10, createItem(getMaterialSafe("CLOCK"), 
            getTpsColor(tps) + "TPS Status", 
            Arrays.asList(
                ChatColor.WHITE + "Current TPS: " + getTpsColor(tps) + String.format("%.2f", tps),
                ChatColor.WHITE + "Status: " + getTpsColor(tps) + getTpsStatus(tps),
                "",
                ChatColor.GRAY + "Click for detailed TPS info",
                ChatColor.YELLOW + "🔄 Live updating"
            )));

        inventory.setItem(12, createItem(getMaterialSafe("REDSTONE"), 
            getMemoryColor(memUsage) + "Memory Usage", 
            Arrays.asList(
                ChatColor.WHITE + "Used: " + getMemoryColor(memUsage) + String.format("%.1f", memUsage) + "%",
                ChatColor.WHITE + "Amount: " + ChatColor.YELLOW + String.format("%.0f", bytesToMB(usedMem)) + "MB",
                ChatColor.WHITE + "Available: " + ChatColor.GREEN + String.format("%.0f", bytesToMB(maxMem)) + "MB",
                "",
                ChatColor.GRAY + "Click for detailed memory info",
                ChatColor.YELLOW + "🔄 Live updating"
            )));

        inventory.setItem(14, createItem(getMaterialSafe("COMPARATOR"), 
            ChatColor.AQUA + "CPU Load", 
            Arrays.asList(
                ChatColor.WHITE + "System Load: " + getCpuColor() + String.format("%.1f", getCpuLoadPercentage()) + "%",
                ChatColor.WHITE + "Processors: " + ChatColor.YELLOW + Runtime.getRuntime().availableProcessors(),
                "",
                ChatColor.GRAY + "Click for detailed CPU info",
                ChatColor.YELLOW + "🔄 Live updating"
            )));

        inventory.setItem(16, createItem(getMaterialSafe("PAPER"), 
            ChatColor.YELLOW + "Server Info", 
            Arrays.asList(
                ChatColor.WHITE + "Uptime: " + ChatColor.GREEN + formatDuration(System.currentTimeMillis() - startTime),
                ChatColor.WHITE + "Players: " + ChatColor.AQUA + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(),
                ChatColor.WHITE + "Worlds: " + ChatColor.LIGHT_PURPLE + Bukkit.getWorlds().size(),
                "",
                ChatColor.GRAY + "General server statistics",
                ChatColor.YELLOW + "🔄 Live updating"
            )));

        String overallStatus = getOverallStatus(tps, memUsage);
        ChatColor overallColor = getOverallStatusColor(tps, memUsage);

        inventory.setItem(22, createItem(getMaterialSafe("BEACON"), 
            overallColor + "Overall Status", 
            Arrays.asList(
                ChatColor.WHITE + "Status: " + overallColor + overallStatus,
                "",
                ChatColor.GRAY + "Combined performance rating",
                ChatColor.YELLOW + "🔄 Real-time analysis"
            )));
    }

    private void updateMemoryGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        
        double maxMemMB = bytesToMB(maxMem);
        double usedMemMB = bytesToMB(usedMem);
        double freeMemMB = bytesToMB(freeMem);
        double usagePercent = (usedMemMB / maxMemMB) * 100;
        
        String status;
        Material statusMaterial;
        if (usagePercent > 80) {
            status = ChatColor.RED + "HIGH USAGE";
            statusMaterial = getMaterialSafe("REDSTONE_BLOCK");
        } else if (usagePercent > 60) {
            status = ChatColor.YELLOW + "MODERATE USAGE";
            statusMaterial = getMaterialSafe("GOLD_BLOCK");
        } else {
            status = ChatColor.GREEN + "NORMAL USAGE";
            statusMaterial = getMaterialSafe("EMERALD_BLOCK");
        }
        
        inventory.setItem(10, createItem(getMaterialSafe("DIAMOND"), ChatColor.GREEN + "Max Memory",
            Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(maxMemMB) + " MB",
                "",
                ChatColor.YELLOW + "🔄 Live monitoring"
            )));
        
        inventory.setItem(12, createItem(getMaterialSafe("REDSTONE"), ChatColor.RED + "Used Memory",
            Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(usedMemMB) + " MB",
                ChatColor.GRAY + "(" + decimalFormat.format(usagePercent) + "%)",
                "",
                ChatColor.YELLOW + "🔄 Real-time usage"
            )));
        
        inventory.setItem(14, createItem(getMaterialSafe("EMERALD"), ChatColor.AQUA + "Free Memory",
            Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(freeMemMB) + " MB",
                "",
                ChatColor.YELLOW + "🔄 Live updates"
            )));
        
        inventory.setItem(16, createItem(statusMaterial, ChatColor.YELLOW + "Memory Status",
            Arrays.asList(
                status,
                "",
                ChatColor.YELLOW + "🔄 Auto-refreshing"
            )));
    }

    private void updateCpuGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        
        int availableProcessors = osBean.getAvailableProcessors();
        double systemLoadAvg = osBean.getSystemLoadAverage();
        
        inventory.setItem(10, createItem(getMaterialSafe("COMPARATOR"), ChatColor.YELLOW + "Processors",
            Arrays.asList(
                ChatColor.WHITE + String.valueOf(availableProcessors) + " cores",
                "",
                ChatColor.YELLOW + "🔄 System info"
            )));
        
        if (systemLoadAvg >= 0) {
            double loadPercent = (systemLoadAvg / availableProcessors) * 100;
            String status;
            Material statusMaterial;
            
            if (loadPercent > 80) {
                status = ChatColor.RED + "VERY HIGH LOAD";
                statusMaterial = getMaterialSafe("REDSTONE_BLOCK");
            } else if (loadPercent > 60) {
                status = ChatColor.YELLOW + "HIGH LOAD";
                statusMaterial = getMaterialSafe("GOLD_BLOCK");
            } else if (loadPercent > 40) {
                status = ChatColor.YELLOW + "MODERATE LOAD";
                statusMaterial = getMaterialSafe("IRON_BLOCK");
            } else {
                status = ChatColor.GREEN + "NORMAL LOAD";
                statusMaterial = getMaterialSafe("EMERALD_BLOCK");
            }
            
            inventory.setItem(12, createItem(getMaterialSafe("REDSTONE_TORCH"), ChatColor.RED + "System Load",
                Arrays.asList(
                    ChatColor.WHITE + decimalFormat.format(systemLoadAvg),
                    ChatColor.GRAY + "(" + decimalFormat.format(loadPercent) + "%)",
                    "",
                    ChatColor.YELLOW + "🔄 Live monitoring"
                )));
            
            inventory.setItem(14, createItem(getMaterialSafe("TORCH"), ChatColor.AQUA + "Available Capacity",
                Arrays.asList(
                    ChatColor.WHITE + decimalFormat.format(100 - loadPercent) + "%",
                    "",
                    ChatColor.YELLOW + "🔄 Real-time"
                )));
            
            inventory.setItem(16, createItem(statusMaterial, ChatColor.YELLOW + "CPU Status",
                Arrays.asList(
                    status,
                    "",
                    ChatColor.YELLOW + "🔄 Auto-updating"
                )));
        }
    }

    private void updateSystemGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long usedMem = runtime.totalMemory() - runtime.freeMemory();
        double maxMemMB = bytesToMB(maxMem);
        double usedMemMB = bytesToMB(usedMem);
        double memUsagePercent = (usedMemMB / maxMemMB) * 100;
        
        int availableProcessors = osBean.getAvailableProcessors();
        double systemLoadAvg = osBean.getSystemLoadAverage();
        double cpuLoadPercent = systemLoadAvg >= 0 ? (systemLoadAvg / availableProcessors) * 100 : -1;
        
        inventory.setItem(10, createItem(getMaterialSafe("REDSTONE"), ChatColor.LIGHT_PURPLE + "Memory Usage",
            Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(usedMemMB) + " / " + decimalFormat.format(maxMemMB) + " MB",
                ChatColor.GRAY + "(" + decimalFormat.format(memUsagePercent) + "%)",
                "",
                ChatColor.YELLOW + "🔄 Live updates"
            )));
        
        inventory.setItem(12, createItem(getMaterialSafe("COMPARATOR"), ChatColor.GOLD + "CPU Usage",
            cpuLoadPercent >= 0 ? Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(cpuLoadPercent) + "%",
                ChatColor.GRAY + String.valueOf(availableProcessors) + " cores",
                "",
                ChatColor.YELLOW + "🔄 Real-time monitoring"
            ) : Arrays.asList(
                ChatColor.GRAY + "Not available",
                ChatColor.GRAY + String.valueOf(availableProcessors) + " cores",
                "",
                ChatColor.YELLOW + "🔄 Live system info"
            )));
    }

    private void checkAndSendWarnings() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("tickwatch.admin")) {
                checkPlayerWarnings(player);
            }
        }
    }

    private void checkPlayerWarnings(Player player) {
        double tps = getCurrentTPS();
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long usedMem = runtime.totalMemory() - runtime.freeMemory();
        double memUsage = (double) usedMem / maxMem * 100;
        double cpuUsage = getCpuLoadPercentage();
        
        double tpsWarning = getConfig().getDouble("thresholds.tps.warning", 15.0);
        double tpsCritical = getConfig().getDouble("thresholds.tps.critical", 10.0);
        double memWarning = getConfig().getDouble("thresholds.memory.warning", 80.0);
        double memCritical = getConfig().getDouble("thresholds.memory.critical", 90.0);
        double cpuWarning = getConfig().getDouble("thresholds.cpu.warning", 85.0);
        double cpuCritical = getConfig().getDouble("thresholds.cpu.critical", 95.0);
        
        if (tps <= tpsCritical) {
            sendWarningMessage(player, "TPS", String.format("%.1f", tps), "CRITICALLY LOW", "Server performance is severely degraded!");
        } else if (tps <= tpsWarning) {
            sendWarningMessage(player, "TPS", String.format("%.1f", tps), "LOW", "Server performance is declining!");
        }
        
        if (memUsage >= memCritical) {
            sendWarningMessage(player, "Memory Usage", String.format("%.1f%%", memUsage), "CRITICALLY HIGH", "Server may run out of memory soon!");
        } else if (memUsage >= memWarning) {
            sendWarningMessage(player, "Memory Usage", String.format("%.1f%%", memUsage), "HIGH", "Memory usage is approaching limits!");
        }
        
        if (cpuUsage >= cpuCritical) {
            sendWarningMessage(player, "CPU Usage", String.format("%.1f%%", cpuUsage), "CRITICALLY HIGH", "Server CPU is heavily overloaded!");
        } else if (cpuUsage >= cpuWarning) {
            sendWarningMessage(player, "CPU Usage", String.format("%.1f%%", cpuUsage), "HIGH", "CPU usage is elevated!");
        }
    }

    private void sendWarningMessage(Player player, String metric, String value, String severity, String advice) {
        String warningKey = metric.toLowerCase().replace(" ", "_") + "_" + severity.toLowerCase().replace(" ", "_");
        long currentTime = System.currentTimeMillis();
        long lastWarning = lastAlertTimes.getOrDefault(warningKey + "_screen", 0L);
        
        if (currentTime - lastWarning < 30000) return;
        
        lastAlertTimes.put(warningKey + "_screen", currentTime);
        
        ChatColor severityColor;
        String severityText;
        if (severity.contains("CRITICALLY")) {
            severityColor = ChatColor.DARK_RED;
            severityText = ChatColor.RED + "CRITICAL";
        } else if (severity.contains("HIGH")) {
            severityColor = ChatColor.RED;
            severityText = ChatColor.YELLOW + "HIGH";
        } else {
            severityColor = ChatColor.YELLOW;
            severityText = ChatColor.YELLOW + "MEDIUM";
        }
        
        player.sendMessage("");
        player.sendMessage(ChatColor.RED + "======== Warning ========");
        player.sendMessage(ChatColor.WHITE + "WARNING: Your " + ChatColor.AQUA + metric + ChatColor.WHITE + " is " + 
                          ChatColor.YELLOW + value + ChatColor.WHITE + " - Status: " + severityText);
        player.sendMessage("");
        player.sendMessage(severityColor + advice);
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "Use " + ChatColor.GREEN + "/tw dashboard" + ChatColor.GRAY + " to monitor server performance");
        player.sendMessage(ChatColor.RED + "======= Warning =======");
        player.sendMessage("");
        
        playSound(player, "warning");
    }

    private void updateCompleteDashboardGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();

        double tps = getCurrentTPS();
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        double memUsage = (double) usedMem / maxMem * 100;
        double cpuLoad = getCpuLoadPercentage();

        inventory.setItem(10, createItem(getMaterialSafe("CLOCK"), 
            getTpsColor(tps) + "Server TPS", 
            Arrays.asList(
                ChatColor.WHITE + "Current: " + getTpsColor(tps) + String.format("%.2f", tps),
                ChatColor.WHITE + "Status: " + getTpsColor(tps) + getTpsStatus(tps),
                ChatColor.WHITE + "1m Avg: " + getTpsColor(getAverageTPS(60)) + String.format("%.2f", getAverageTPS(60)),
                ChatColor.WHITE + "15m Avg: " + getTpsColor(getAverageTPS(900)) + String.format("%.2f", getAverageTPS(900)),
                "",
                ChatColor.YELLOW + "🔄 Live updating every 3s"
            )));

        inventory.setItem(11, createItem(getMaterialSafe("REDSTONE"), 
            getMemoryColor(memUsage) + "Memory Status", 
            Arrays.asList(
                ChatColor.WHITE + "Usage: " + getMemoryColor(memUsage) + String.format("%.1f", memUsage) + "%",
                ChatColor.WHITE + "Used: " + ChatColor.YELLOW + String.format("%.0f", bytesToMB(usedMem)) + "MB",
                ChatColor.WHITE + "Free: " + ChatColor.GREEN + String.format("%.0f", bytesToMB(freeMem)) + "MB",
                ChatColor.WHITE + "Max: " + ChatColor.AQUA + String.format("%.0f", bytesToMB(maxMem)) + "MB",
                "",
                ChatColor.YELLOW + "🔄 Real-time monitoring"
            )));

        inventory.setItem(12, createItem(getMaterialSafe("COMPARATOR"), 
            getCpuColor() + "CPU Performance", 
            Arrays.asList(
                ChatColor.WHITE + "Load: " + getCpuColor() + String.format("%.1f", cpuLoad) + "%",
                ChatColor.WHITE + "Cores: " + ChatColor.YELLOW + Runtime.getRuntime().availableProcessors(),
                ChatColor.WHITE + "Available: " + ChatColor.GREEN + String.format("%.1f", 100 - cpuLoad) + "%",
                "",
                ChatColor.YELLOW + "🔄 Live CPU monitoring"
            )));

        inventory.setItem(13, createItem(getMaterialSafe("PAPER"), 
            ChatColor.AQUA + "Server Statistics", 
            Arrays.asList(
                ChatColor.WHITE + "Uptime: " + ChatColor.GREEN + formatDuration(System.currentTimeMillis() - startTime),
                ChatColor.WHITE + "Players: " + ChatColor.AQUA + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(),
                ChatColor.WHITE + "Worlds: " + ChatColor.LIGHT_PURPLE + Bukkit.getWorlds().size(),
                ChatColor.WHITE + "Plugins: " + ChatColor.GOLD + Bukkit.getPluginManager().getPlugins().length,
                "",
                ChatColor.YELLOW + "🔄 Live server data"
            )));

        String overallStatus = getOverallStatus(tps, memUsage);
        ChatColor overallColor = getOverallStatusColor(tps, memUsage);

        inventory.setItem(14, createItem(getMaterialSafe("BEACON"), 
            overallColor + "System Health", 
            Arrays.asList(
                ChatColor.WHITE + "Overall: " + overallColor + overallStatus,
                ChatColor.WHITE + "TPS: " + getTpsColor(tps) + getTpsStatus(tps),
                ChatColor.WHITE + "Memory: " + getMemoryColor(memUsage) + (memUsage <= 50 ? "Healthy" : memUsage <= 80 ? "Warning" : "Critical"),
                ChatColor.WHITE + "CPU: " + getCpuColor() + (cpuLoad <= 50 ? "Normal" : cpuLoad <= 80 ? "High" : "Critical"),
                "",
                ChatColor.YELLOW + "🔄 Continuous health check"
            )));

        inventory.setItem(16, createItem(getMaterialSafe("EMERALD"), 
            ChatColor.GREEN + "Current Time", 
            Arrays.asList(
                ChatColor.WHITE + dateFormat.format(new Date()),
                ChatColor.WHITE + "Last Reload: " + formatDuration(System.currentTimeMillis() - lastReloadTime) + " ago",
                "",
                ChatColor.YELLOW + "🔄 Live time display"
            )));

        inventory.setItem(20, createItem(getMaterialSafe("DIAMOND_SWORD"), 
            ChatColor.GOLD + "Performance Metrics", 
            Arrays.asList(
                ChatColor.WHITE + "Tick Rate: " + getTpsColor(tps) + String.format("%.1f", tps * 2.5) + "ms",
                ChatColor.WHITE + "Expected: " + ChatColor.GREEN + "50.0ms",
                ChatColor.WHITE + "Deviation: " + getDeviationColor(tps) + String.format("%.1f", Math.abs(50 - (tps * 2.5))) + "ms",
                ChatColor.WHITE + "Efficiency: " + getTpsColor(tps) + String.format("%.1f", (tps / 20.0) * 100) + "%",
                "",
                ChatColor.YELLOW + "🔄 Real-time calculations"
            )));

        inventory.setItem(21, createItem(getMaterialSafe("NETHER_STAR"), 
            ChatColor.LIGHT_PURPLE + "System Resources", 
            Arrays.asList(
                ChatColor.WHITE + "JVM Version: " + ChatColor.YELLOW + System.getProperty("java.version"),
                ChatColor.WHITE + "OS: " + ChatColor.AQUA + System.getProperty("os.name"),
                ChatColor.WHITE + "Architecture: " + ChatColor.GREEN + System.getProperty("os.arch"),
                ChatColor.WHITE + "Server Version: " + ChatColor.GOLD + Bukkit.getVersion().split("-")[0],
                "",
                ChatColor.GRAY + "Static system information"
            )));

        inventory.setItem(22, createItem(getMaterialSafe("WATCH"), 
            ChatColor.YELLOW + "Live Performance Graph", 
            Arrays.asList(
                ChatColor.WHITE + "TPS Trend: " + getTpsGraphBar(tps),
                ChatColor.WHITE + "Memory Trend: " + getMemoryGraphBar(memUsage),
                ChatColor.WHITE + "CPU Trend: " + getCpuGraphBar(cpuLoad),
                "",
                ChatColor.GRAY + "Visual performance indicators",
                ChatColor.YELLOW + "🔄 Updates every 3 seconds"
            )));

        inventory.setItem(23, createItem(getMaterialSafe("BOOK"), 
            ChatColor.AQUA + "Detailed Analytics", 
            Arrays.asList(
                ChatColor.WHITE + "Avg TPS (5m): " + getTpsColor(getAverageTPS(300)) + String.format("%.2f", getAverageTPS(300)),
                ChatColor.WHITE + "Memory Efficiency: " + getMemoryColor(memUsage) + String.format("%.1f", 100 - memUsage) + "%",
                ChatColor.WHITE + "System Stability: " + getStabilityRating(tps, memUsage, cpuLoad),
                "",
                ChatColor.YELLOW + "🔄 Advanced metrics"
            )));

        inventory.setItem(24, createItem(getMaterialSafe("REDSTONE_TORCH"), 
            ChatColor.RED + "Alert Status", 
            Arrays.asList(
                ChatColor.WHITE + "TPS Alert: " + (tps < 15 ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "NORMAL"),
                ChatColor.WHITE + "Memory Alert: " + (memUsage > 80 ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "NORMAL"),
                ChatColor.WHITE + "CPU Alert: " + (cpuLoad > 80 ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "NORMAL"),
                "",
                ChatColor.YELLOW + "🔄 Live alert monitoring"
            )));
    }

    private void trackActiveGui(Player player, String guiType) {
        activeGuis.put(player, guiType);
    }

    private void removeActiveGui(Player player) {
        activeGuis.remove(player);
    }

    private boolean isValidTickWatchGUI(Player player, Inventory inventory) {
        try {
            String title = getInventoryTitle(inventory);
            if (title == null) return false;
            
            return title.contains("§8[§6TW§8]");
        } catch (Exception e) {
            return false;
        }
    }

    private String getInventoryTitle(Inventory inventory) {
        try {
            return (String) inventory.getClass().getMethod("getTitle").invoke(inventory);
        } catch (Exception e1) {
            try {
                return (String) inventory.getClass().getMethod("getName").invoke(inventory);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            try {
                if (isValidTickWatchGUI(player, event.getInventory())) {
                    removeActiveGui(player);
                }
            } catch (Exception e) {
                removeActiveGui(player);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeActiveGui(event.getPlayer());
    }

    private void loadWebhookConfig() {
        webhookUrl = getConfig().getString("webhook.url", "").trim();
        webhookEnabled = getConfig().getBoolean("webhook.enabled", false);
        checkInterval = Math.max(1, Math.min(3600, getConfig().getInt("webhook.notifications.check-interval-seconds", 30)));
        alertCooldown = Math.max(1, getConfig().getInt("webhook.notifications.alert-cooldown", 15));
        
        if (webhookEnabled) {
            if (webhookUrl.isEmpty()) {
            getLogger().warning("Webhook is enabled but no URL is configured!");
            webhookEnabled = false;
            } else if (!isValidWebhookUrl(webhookUrl)) {
                getLogger().warning("Webhook URL is invalid: " + webhookUrl);
                webhookEnabled = false;
            } else {
                getLogger().info("Webhook alert system enabled - URL: " + webhookUrl.substring(0, Math.min(50, webhookUrl.length())) + "...");
                getLogger().info("Check interval: " + checkInterval + " seconds, Alert cooldown: " + alertCooldown + " minutes");
            }
        }
    }
    
    private boolean isValidWebhookUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        
        try {
            new java.net.URL(url);
            return url.toLowerCase().startsWith("http://") || url.toLowerCase().startsWith("https://");
        } catch (Exception e) {
            return false;
        }
    }

    private void startWebhookSystem() {
        if (!webhookEnabled) {
            getLogger().info("Webhook system is disabled");
            return;
        }
        
        if (webhookTask != null) {
            webhookTask.cancel();
        }
        
        long intervalTicks = checkInterval * 20L;
        
        getLogger().info("Starting webhook monitoring system - first check in " + checkInterval + " seconds");
        
        webhookTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                try {
                checkAndSendAlerts();
                } catch (Exception e) {
                    getLogger().severe("Error in webhook monitoring task: " + e.getMessage());
                    if (getConfig().getBoolean("advanced.debug", false)) {
                        e.printStackTrace();
                    }
                }
            }
        }, intervalTicks, intervalTicks);
    }

    private void checkAndSendAlerts() {
        if (!webhookEnabled || webhookUrl == null || webhookUrl.trim().isEmpty()) {
            if (getConfig().getBoolean("advanced.debug", false)) {
                getLogger().info("Webhook check skipped - enabled: " + webhookEnabled + ", URL configured: " + (webhookUrl != null && !webhookUrl.trim().isEmpty()));
            }
            return;
        }
        
        double tps = getCurrentTPS();
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long usedMem = runtime.totalMemory() - runtime.freeMemory();
        double memUsage = (double) usedMem / maxMem * 100;
        double cpuUsage = getCpuLoadPercentage();
        
        double tpsWarning = getConfig().getDouble("thresholds.tps.warning", 15.0);
        double tpsCritical = getConfig().getDouble("thresholds.tps.critical", 10.0);
        double memWarning = getConfig().getDouble("thresholds.memory.warning", 80.0);
        double memCritical = getConfig().getDouble("thresholds.memory.critical", 90.0);
        double cpuWarning = getConfig().getDouble("thresholds.cpu.warning", 85.0);
        double cpuCritical = getConfig().getDouble("thresholds.cpu.critical", 95.0);
        
        if (getConfig().getBoolean("advanced.debug", false)) {
            getLogger().info("Webhook check - TPS: " + String.format("%.2f", tps) + 
                           ", Memory: " + String.format("%.1f", memUsage) + "%" + 
                           ", CPU: " + String.format("%.1f", cpuUsage) + "%");
            getLogger().info("Thresholds - TPS(W:" + tpsWarning + "/C:" + tpsCritical + 
                           "), Memory(W:" + memWarning + "%/C:" + memCritical + "%)" + 
                           ", CPU(W:" + cpuWarning + "%/C:" + cpuCritical + "%)");
        }
        
        long currentTime = System.currentTimeMillis();
        long cooldownMs = alertCooldown * 60 * 1000L;
        
        boolean alertSent = false;
        
        if (tps <= tpsCritical && canSendAlert("tps-critical", currentTime, cooldownMs)) {
            getLogger().info("Sending TPS Critical alert - TPS: " + String.format("%.2f", tps) + " (threshold: " + tpsCritical + ")");
            if (sendAlert("critical", "TPS Critical", "Server TPS has dropped to " + String.format("%.2f", tps) + " (Critical: ≤" + tpsCritical + ")", "#FF0000")) {
                recordAlert("tps-critical", currentTime);
                alertSent = true;
            }
        } else if (tps <= tpsWarning && canSendAlert("tps-warning", currentTime, cooldownMs)) {
            getLogger().info("Sending TPS Warning alert - TPS: " + String.format("%.2f", tps) + " (threshold: " + tpsWarning + ")");
            if (sendAlert("warning", "TPS Warning", "Server TPS has dropped to " + String.format("%.2f", tps) + " (Warning: ≤" + tpsWarning + ")", "#FFA500")) {
                recordAlert("tps-warning", currentTime);
                alertSent = true;
            }
        }
        
        if (memUsage >= memCritical && canSendAlert("memory-critical", currentTime, cooldownMs)) {
            getLogger().info("Sending Memory Critical alert - Usage: " + String.format("%.1f", memUsage) + "% (threshold: " + memCritical + "%)");
            if (sendAlert("critical", "Memory Critical", "Memory usage has reached " + String.format("%.1f", memUsage) + "% (Critical: ≥" + memCritical + "%)", "#FF0000")) {
                recordAlert("memory-critical", currentTime);
                alertSent = true;
            }
        } else if (memUsage >= memWarning && canSendAlert("memory-warning", currentTime, cooldownMs)) {
            getLogger().info("Sending Memory Warning alert - Usage: " + String.format("%.1f", memUsage) + "% (threshold: " + memWarning + "%)");
            if (sendAlert("warning", "Memory Warning", "Memory usage has reached " + String.format("%.1f", memUsage) + "% (Warning: ≥" + memWarning + "%)", "#FFA500")) {
                recordAlert("memory-warning", currentTime);
                alertSent = true;
            }
        }
        
        if (cpuUsage >= cpuCritical && canSendAlert("cpu-critical", currentTime, cooldownMs)) {
            getLogger().info("Sending CPU Critical alert - Usage: " + String.format("%.1f", cpuUsage) + "% (threshold: " + cpuCritical + "%)");
            if (sendAlert("critical", "CPU Critical", "CPU usage has reached " + String.format("%.1f", cpuUsage) + "% (Critical: ≥" + cpuCritical + "%)", "#FF0000")) {
                recordAlert("cpu-critical", currentTime);
                alertSent = true;
            }
        } else if (cpuUsage >= cpuWarning && canSendAlert("cpu-warning", currentTime, cooldownMs)) {
            getLogger().info("Sending CPU Warning alert - Usage: " + String.format("%.1f", cpuUsage) + "% (threshold: " + cpuWarning + "%)");
            if (sendAlert("warning", "CPU Warning", "CPU usage has reached " + String.format("%.1f", cpuUsage) + "% (Warning: ≥" + cpuWarning + "%)", "#FFA500")) {
                recordAlert("cpu-warning", currentTime);
                alertSent = true;
        }
    }

        if (getConfig().getBoolean("advanced.debug", false) && !alertSent) {
            getLogger().info("No alerts triggered during this check cycle");
        }
    }

    private boolean canSendAlert(String alertType, long currentTime, long cooldownMs) {
        boolean alertEnabled = getConfig().getBoolean("webhook.notifications.alerts." + alertType, true);
        if (!alertEnabled) {
            if (getConfig().getBoolean("advanced.debug", false)) {
                getLogger().info("Alert type " + alertType + " is disabled in config");
            }
            return false;
        }
        
        Long lastAlert = lastAlertTimes.get(alertType);
        long timeSinceLastAlert = lastAlert == null ? Long.MAX_VALUE : (currentTime - lastAlert);
        
        boolean canSend = lastAlert == null || timeSinceLastAlert >= cooldownMs;
        
        if (getConfig().getBoolean("advanced.debug", false) && !canSend) {
            getLogger().info("Alert " + alertType + " blocked by cooldown - time since last: " + timeSinceLastAlert + "ms, cooldown: " + cooldownMs + "ms");
        }
        
        return canSend;
    }
    
    private void recordAlert(String alertType, long currentTime) {
        lastAlertTimes.put(alertType, currentTime);
        if (getConfig().getBoolean("advanced.debug", false)) {
            getLogger().info("Recorded alert time for " + alertType + " at " + currentTime);
        }
    }
    
    private boolean shouldSendAlert(String alertType, long currentTime, long cooldownMs) {
        return canSendAlert(alertType, currentTime, cooldownMs);
    }

    private boolean sendAlert(String severity, String title, String description, String color) {
        try {
            String jsonPayload = buildAlertPayload(severity, title, description, color);
            
            if (getConfig().getBoolean("advanced.debug", false)) {
                getLogger().info("Sending webhook to: " + webhookUrl);
                getLogger().info("Payload: " + jsonPayload);
            }
            
            URL url = new URL(webhookUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "TickWatch-Monitor/1.0");
            connection.setDoOutput(true);
            connection.setConnectTimeout(getConfig().getInt("advanced.timeout", 10) * 1000);
            connection.setReadTimeout(getConfig().getInt("advanced.timeout", 10) * 1000);
            
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.getBytes("utf-8");
                os.write(input, 0, input.length);
            }
            
            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                getLogger().info("Webhook alert sent successfully: " + title + " (Status: " + responseCode + ")");
                connection.disconnect();
                return true;
            } else {
                String responseMessage = "";
                try {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getErrorStream()));
                    String line;
                    StringBuilder response = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    responseMessage = response.toString();
                    reader.close();
                } catch (Exception e) {
                    responseMessage = "Unable to read error response";
                }
                getLogger().warning("Failed to send webhook alert: " + title + " (Status: " + responseCode + ") Response: " + responseMessage);
                connection.disconnect();
                return false;
            }
            
        } catch (Exception e) {
            getLogger().severe("Failed to send webhook alert: " + title + " - " + e.getMessage());
            if (getConfig().getBoolean("advanced.debug", false)) {
                e.printStackTrace();
            }
            return false;
        }
    }

    private String buildAlertPayload(String severity, String title, String description, String color) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        
        String username = getConfig().getString("webhook.messages.username", "TickWatch Monitor");
        String avatarUrl = getConfig().getString("webhook.messages.avatar-url", "");
        
        json.append("\"username\":\"").append(username).append("\",");
        
        if (!avatarUrl.isEmpty()) {
            json.append("\"avatar_url\":\"").append(avatarUrl).append("\",");
        }
        
        json.append("\"embeds\":[{");
        
        String embedTitle = getConfig().getString("webhook.messages.titles." + severity, title);
        String embedColor = getConfig().getString("webhook.messages.colors." + severity, color);
        
        json.append("\"title\":\"").append(embedTitle).append("\",");
        json.append("\"description\":\"").append(description).append("\",");
        json.append("\"color\":").append(Integer.parseInt(embedColor.substring(1), 16)).append(",");
        json.append("\"timestamp\":\"").append(java.time.Instant.now().toString()).append("\",");
        
        json.append("\"fields\":[");
        json.append("{\"name\":\"Server\",\"value\":\"").append(Bukkit.getServer().getName()).append("\",\"inline\":true},");
        json.append("{\"name\":\"Players Online\",\"value\":\"").append(Bukkit.getOnlinePlayers().size()).append("/").append(Bukkit.getMaxPlayers()).append("\",\"inline\":true},");
        json.append("{\"name\":\"Uptime\",\"value\":\"").append(formatDuration(System.currentTimeMillis() - startTime)).append("\",\"inline\":true}");
        json.append("],");
        
        String footer = getConfig().getString("webhook.messages.footer", "TickWatch Server Monitor");
        json.append("\"footer\":{\"text\":\"").append(footer).append("\"}");
        
        json.append("}]");
        json.append("}");
        
        return json.toString();
    }

}
