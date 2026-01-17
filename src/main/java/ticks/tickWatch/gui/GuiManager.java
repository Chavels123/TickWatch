package ticks.tickWatch.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import ticks.tickWatch.data.DataManager;
import ticks.tickWatch.manager.PerformanceManager;
import ticks.tickWatch.manager.WebhookManager;
import ticks.tickWatch.utils.Utils;

import java.text.DecimalFormat;
import java.util.*;

public class GuiManager implements Listener {

    private final JavaPlugin plugin;
    private final PerformanceManager performanceManager;
    private final WebhookManager webhookManager;
    private final DataManager dataManager;
    private final Map<Player, String> activeGuis;
    private final DecimalFormat decimalFormat = new DecimalFormat("#.##");

    public GuiManager(JavaPlugin plugin, PerformanceManager performanceManager, WebhookManager webhookManager, DataManager dataManager) {
        this.plugin = plugin;
        this.performanceManager = performanceManager;
        this.webhookManager = webhookManager;
        this.dataManager = dataManager;
        this.activeGuis = new HashMap<>();

        Bukkit.getPluginManager().registerEvents(this, plugin);
        startRealtimeUpdates();
    }

    private void startRealtimeUpdates() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            updateActiveGuis();
        }, 40L, 40L); // Update every 2 seconds
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

    private void trackActiveGui(Player player, String guiType) {
        activeGuis.put(player, guiType);
    }

    private void removeActiveGui(Player player) {
        activeGuis.remove(player);
    }

    public void cleanup() {
         activeGuis.clear();
    }

    public void openMainGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GOLD + "§8[§6TW§8] §eServer Monitor");

        gui.setItem(11, Utils.createItem(Utils.getMaterialSafe("NETHER_STAR"), ChatColor.LIGHT_PURPLE + "Complete Dashboard",
                Arrays.asList(
                        ChatColor.GRAY + "All-in-one monitoring interface",
                        ChatColor.GRAY + "• Real-time TPS, Memory & CPU",
                        ChatColor.GRAY + "• Performance graphs & analytics",
                        ChatColor.GRAY + "• System health monitoring",
                        "",
                        ChatColor.GREEN + "Click to open dashboard"
                )));

        gui.setItem(13, Utils.createItem(Utils.getMaterialSafe("BEACON"), ChatColor.AQUA + "Performance Overview",
                Arrays.asList(
                        ChatColor.GRAY + "Quick performance summary",
                        ChatColor.GRAY + "• TPS status & trends",
                        ChatColor.GRAY + "• Memory usage overview",
                        ChatColor.GRAY + "• CPU load information",
                        "",
                        ChatColor.GREEN + "Click to view performance"
                )));

        gui.setItem(15, Utils.createItem(Utils.getMaterialSafe("BOOK"), ChatColor.YELLOW + "Help & Commands",
                Arrays.asList(
                        ChatColor.GRAY + "View available commands",
                        ChatColor.GRAY + "• Command usage guide",
                        ChatColor.GRAY + "• Plugin information",
                        "",
                        ChatColor.GREEN + "Click for help"
                )));

        if (webhookManager.isWebhookEnabled() && webhookManager.getWebhookUrl() != null && !webhookManager.getWebhookUrl().trim().isEmpty()) {
            gui.setItem(22, Utils.createItem(Utils.getMaterialSafe("BELL"), ChatColor.GOLD + "Webhook Settings",
                    Arrays.asList(
                            ChatColor.GRAY + "Configure webhook alerts",
                            ChatColor.GRAY + "• Enable/disable alerts",
                            ChatColor.GRAY + "• Adjust thresholds",
                            ChatColor.GRAY + "• Set intervals & cooldowns",
                            "",
                            ChatColor.GREEN + "Status: " + (webhookManager.isWebhookEnabled() ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                            ChatColor.GREEN + "Click to configure"
                    )));
        }

        player.openInventory(gui);
    }

    public void openCompleteDashboardGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, ChatColor.LIGHT_PURPLE + "§8[§6TW§8] §dComplete Dashboard");
        trackActiveGui(player, "dashboard");
        updateCompleteDashboardGUI(player);
        player.openInventory(inventory);
    }

    private void updateCompleteDashboardGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        double tps = performanceManager.getCurrentTPS();
        double memUsage = performanceManager.getMemoryUsagePercentage();
        double cpuLoad = performanceManager.getCpuLoadPercentage();

        inventory.setItem(10, Utils.createItem(Utils.getMaterialSafe("CLOCK"),
                Utils.getTpsColor(tps) + "Server TPS",
                Arrays.asList(
                        ChatColor.WHITE + "Current: " + Utils.getTpsColor(tps) + String.format("%.2f", tps),
                        ChatColor.WHITE + "Status: " + Utils.getTpsColor(tps) + Utils.getTpsStatus(tps),
                        ChatColor.WHITE + "1m Avg: " + Utils.getTpsColor(performanceManager.getAverageTPS(60)) + String.format("%.2f", performanceManager.getAverageTPS(60)),
                        ChatColor.WHITE + "15m Avg: " + Utils.getTpsColor(performanceManager.getAverageTPS(900)) + String.format("%.2f", performanceManager.getAverageTPS(900)),
                        "",
                        ChatColor.YELLOW + "🔄 Live updating every 3s"
                )));

         inventory.setItem(11, Utils.createItem(Utils.getMaterialSafe("REDSTONE"),
            getMemoryColor(memUsage) + "Memory Status",
            Arrays.asList(
                ChatColor.WHITE + "Usage: " + getMemoryColor(memUsage) + String.format("%.1f", memUsage) + "%",
                "",
                ChatColor.YELLOW + "🔄 Real-time monitoring"
            )));

         inventory.setItem(12, Utils.createItem(Utils.getMaterialSafe("COMPARATOR"),
            getCpuColor(cpuLoad) + "CPU Performance",
            Arrays.asList(
                ChatColor.WHITE + "Load: " + getCpuColor(cpuLoad) + String.format("%.1f", cpuLoad) + "%",
                "",
                ChatColor.YELLOW + "🔄 Live CPU monitoring"
            )));

         inventory.setItem(49, Utils.createItem(Utils.getMaterialSafe("BARRIER"), ChatColor.RED + "Close Dashboard",
            Arrays.asList(ChatColor.GRAY + "Click to close this dashboard")));
    }

    public void openPerformanceGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 45, ChatColor.GOLD + "§8[§6TW§8] §ePerformance");
        trackActiveGui(player, "performance");
        updatePerformanceGUI(player);
        player.openInventory(inventory);
    }

    private void updatePerformanceGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        double tps = performanceManager.getCurrentTPS();
        double memUsage = performanceManager.getMemoryUsagePercentage();

        inventory.setItem(10, Utils.createItem(Utils.getMaterialSafe("CLOCK"),
            Utils.getTpsColor(tps) + "TPS Status",
            Arrays.asList(
                ChatColor.WHITE + "Current TPS: " + Utils.getTpsColor(tps) + String.format("%.2f", tps),
                "",
                 ChatColor.YELLOW + "🔄 Live updating"
            )));

        inventory.setItem(12, Utils.createItem(Utils.getMaterialSafe("REDSTONE"),
            getMemoryColor(memUsage) + "Memory Usage",
            Arrays.asList(
                ChatColor.WHITE + "Used: " + getMemoryColor(memUsage) + String.format("%.1f", memUsage) + "%",
                "",
                ChatColor.YELLOW + "🔄 Live updating"
            )));

         inventory.setItem(40, Utils.createItem(Utils.getMaterialSafe("BARRIER"), ChatColor.RED + "Close",
            Arrays.asList(ChatColor.GRAY + "Click to close this menu")));
    }

    public void openWebhookSettingsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 45, ChatColor.GOLD + "§8[§6TW§8] §eWebhook Settings");

        boolean enabled = plugin.getConfig().getBoolean("webhook.enabled", false);

        gui.setItem(10, Utils.createItem(Utils.getMaterialSafe("REDSTONE_TORCH"),
            (enabled ? ChatColor.GREEN + "Webhook: Enabled" : ChatColor.RED + "Webhook: Disabled"),
            Arrays.asList(
                ChatColor.GRAY + "Toggle webhook notifications",
                "",
                ChatColor.WHITE + "Status: " + (enabled ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"),
                "",
                ChatColor.YELLOW + "Click to " + (enabled ? "disable" : "enable")
            )));

        gui.setItem(31, Utils.createItem(Utils.getMaterialSafe("EMERALD"), ChatColor.GREEN + "Test Webhook",
            Arrays.asList(
                ChatColor.GRAY + "Send a test notification",
                "",
                ChatColor.GREEN + "Click to test"
            )));

        gui.setItem(40, Utils.createItem(Utils.getMaterialSafe("ARROW"), ChatColor.GRAY + "Back to Main Menu",
            Arrays.asList(ChatColor.GRAY + "Return to main interface")));

        player.openInventory(gui);
    }

    public void openTpsGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "§8[§6TW§8] §2TPS");
        trackActiveGui(player, "tps");
        updateTpsGUI(player);
        player.openInventory(inventory);
    }

    private void updateTpsGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        double currentTps = performanceManager.getCurrentTPS();
        double avgTps1m = performanceManager.getAverageTPS(60);
        double avgTps15m = performanceManager.getAverageTPS(900);

        inventory.setItem(10, Utils.createItem(Utils.getMaterialSafe("CLOCK"),
            Utils.getTpsColor(currentTps) + "Current TPS",
            Arrays.asList(
                ChatColor.WHITE + "Current: " + Utils.getTpsColor(currentTps) + String.format("%.2f", currentTps),
                "",
                ChatColor.YELLOW + "🔄 Live updating"
            )));

        inventory.setItem(12, Utils.createItem(Utils.getMaterialSafe("COMPASS"),
            ChatColor.YELLOW + "Average TPS (1m)",
            Arrays.asList(
                ChatColor.WHITE + "1 Minute: " + Utils.getTpsColor(avgTps1m) + String.format("%.2f", avgTps1m),
                "",
                ChatColor.YELLOW + "🔄 Live updating"
            )));

        inventory.setItem(14, Utils.createItem(Utils.getMaterialSafe("WATCH"),
            ChatColor.AQUA + "Average TPS (15m)",
            Arrays.asList(
                ChatColor.WHITE + "15 Minutes: " + Utils.getTpsColor(avgTps15m) + String.format("%.2f", avgTps15m),
                "",
                ChatColor.YELLOW + "🔄 Live updating"
            )));
    }

    public void openMemoryGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, ChatColor.LIGHT_PURPLE + "§8[§6TW§8] §dMemory");
        trackActiveGui(player, "memory");
        updateMemoryGUI(player);
        player.openInventory(inventory);
    }

    private void updateMemoryGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        double memUsage = performanceManager.getMemoryUsagePercentage();

        inventory.setItem(10, Utils.createItem(Utils.getMaterialSafe("REDSTONE"),
            ChatColor.RED + "Used Memory",
            Arrays.asList(
                ChatColor.WHITE + "Usage: " + getMemoryColor(memUsage) + String.format("%.1f", memUsage) + "%",
                "",
                ChatColor.YELLOW + "🔄 Live monitoring"
            )));
    }

    public void openCpuGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, ChatColor.GOLD + "§8[§6TW§8] §6CPU Info");
        trackActiveGui(player, "cpu");
        updateCpuGUI(player);
        player.openInventory(inventory);
    }

    private void updateCpuGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        double cpuLoad = performanceManager.getCpuLoadPercentage();

        inventory.setItem(10, Utils.createItem(Utils.getMaterialSafe("COMPARATOR"),
            ChatColor.YELLOW + "CPU Load",
            Arrays.asList(
                ChatColor.WHITE + "System Load: " + getCpuColor(cpuLoad) + String.format("%.1f", cpuLoad) + "%",
                "",
                ChatColor.YELLOW + "🔄 Live monitoring"
            )));
    }

    public void openSystemGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, ChatColor.DARK_PURPLE + "§8[§6TW§8] §5System");
        trackActiveGui(player, "system");
        updateSystemGUI(player);
        player.openInventory(inventory);
    }

    private void updateSystemGUI(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();

        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long usedMem = runtime.totalMemory() - runtime.freeMemory();
        double maxMemMB = Utils.bytesToMB(maxMem);
        double usedMemMB = Utils.bytesToMB(usedMem);
        double memUsagePercent = (usedMemMB / maxMemMB) * 100;

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        double cpuLoadPercent = performanceManager.getCpuLoadPercentage();

        inventory.setItem(10, Utils.createItem(Utils.getMaterialSafe("REDSTONE"), ChatColor.LIGHT_PURPLE + "Memory Usage",
            Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(usedMemMB) + " / " + decimalFormat.format(maxMemMB) + " MB",
                ChatColor.GRAY + "(" + decimalFormat.format(memUsagePercent) + "%)",
                "",
                ChatColor.YELLOW + "🔄 Live updates"
            )));

        inventory.setItem(12, Utils.createItem(Utils.getMaterialSafe("COMPARATOR"), ChatColor.GOLD + "CPU Usage",
            Arrays.asList(
                ChatColor.WHITE + decimalFormat.format(cpuLoadPercent) + "%",
                ChatColor.GRAY + String.valueOf(availableProcessors) + " cores",
                "",
                ChatColor.YELLOW + "🔄 Real-time monitoring"
            )));
    }

    // Helper methods for colors
    private ChatColor getMemoryColor(double memUsage) {
        if (memUsage <= 50) return ChatColor.GREEN;
        if (memUsage <= 70) return ChatColor.YELLOW;
        if (memUsage <= 85) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    private ChatColor getCpuColor(double cpuLoad) {
        if (cpuLoad <= 30) return ChatColor.GREEN;
        if (cpuLoad <= 50) return ChatColor.YELLOW;
        if (cpuLoad <= 75) return ChatColor.GOLD;
        return ChatColor.RED;
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
        if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return;
        String itemName = item.getItemMeta().getDisplayName();

        if (title.contains("Server Monitor")) {
            if (itemName.contains("Complete Dashboard")) {
                Utils.playSound(player, "gui_click");
                openCompleteDashboardGUI(player);
            } else if (itemName.contains("Performance Overview")) {
                Utils.playSound(player, "gui_click");
                openPerformanceGUI(player);
            } else if (itemName.contains("Webhook Settings")) {
                Utils.playSound(player, "gui_click");
                openWebhookSettingsGUI(player);
            }
        } else if (title.contains("Performance")) {
            if (itemName.contains("TPS Status")) {
                Utils.playSound(player, "gui_click");
                openTpsGUI(player);
            } else if (itemName.contains("Memory Usage")) {
                Utils.playSound(player, "gui_click");
                openMemoryGUI(player);
            } else if (itemName.contains("CPU Load") || itemName.contains("System Load")) { // "System Load" if CPU text varies
                Utils.playSound(player, "gui_click");
                openCpuGUI(player);
            }
        } else if (title.contains("Webhook Settings")) {
             if (itemName.contains("Webhook: Enabled") || itemName.contains("Webhook: Disabled")) {
                boolean currentState = plugin.getConfig().getBoolean("webhook.enabled", false);
                plugin.getConfig().set("webhook.enabled", !currentState);
                plugin.saveConfig();
                webhookManager.loadConfig(); // Reload config in manager

                if (!currentState) {
                    webhookManager.startWebhookSystem();
                }

                Utils.playSound(player, "gui_click");
                player.sendMessage(ChatColor.GREEN + "Webhook " + (!currentState ? "enabled" : "disabled") + "!");
                openWebhookSettingsGUI(player);
            } else if (itemName.contains("Test Webhook")) {
                 if (webhookManager.isWebhookEnabled()) {
                     player.sendMessage(ChatColor.YELLOW + "Sending test webhook...");
                     Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                         webhookManager.sendTestAlert("info", "Test Notification", "This is a test.", "#0099FF");
                     });
                 } else {
                     player.sendMessage(ChatColor.RED + "Enable webhook first!");
                 }
            } else if (itemName.contains("Back to Main Menu")) {
                Utils.playSound(player, "gui_click");
                openMainGUI(player);
            }
        } else if (itemName.contains("Close")) {
            Utils.playSound(player, "gui_close");
            player.closeInventory();
        } else if (itemName.contains("Back to Main Menu")) {
            Utils.playSound(player, "gui_click");
            openMainGUI(player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            if (isValidTickWatchGUI(player, event.getInventory())) {
                removeActiveGui(player);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeActiveGui(event.getPlayer());
    }
}
