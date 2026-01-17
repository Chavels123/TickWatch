package ticks.tickWatch.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ticks.tickWatch.data.DataManager;
import ticks.tickWatch.gui.GuiManager;
import ticks.tickWatch.manager.PerformanceManager;
import ticks.tickWatch.manager.WebhookManager;
import ticks.tickWatch.utils.Utils;

public class CommandManager implements CommandExecutor {

    private final JavaPlugin plugin;
    private final GuiManager guiManager;
    private final WebhookManager webhookManager;
    private final DataManager dataManager;

    public CommandManager(JavaPlugin plugin, GuiManager guiManager, WebhookManager webhookManager, DataManager dataManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.webhookManager = webhookManager;
        this.dataManager = dataManager;
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
            Utils.playSound(player, "gui_open");
            guiManager.openMainGUI(player);
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
                Utils.playSound(player, "gui_open");
                guiManager.openCompleteDashboardGUI(player);
                break;
            case "performance":
            case "perf":
                Utils.playSound(player, "gui_open");
                guiManager.openPerformanceGUI(player);
                break;
            case "reload":
                if (player.hasPermission("tickwatch.admin")) {
                    Utils.playSound(player, "reload");
                    plugin.reloadConfig();
                    webhookManager.loadConfig();
                    webhookManager.startWebhookSystem();
                    dataManager.loadData();
                    player.sendMessage(ChatColor.GREEN + "TickWatch configuration and data reloaded!");
                } else {
                    Utils.playSound(player, "error");
                    player.sendMessage(ChatColor.RED + "You don't have permission to reload data!");
                }
                break;
            case "debug":
                if (player.hasPermission("tickwatch.admin")) {
                    boolean currentDebug = plugin.getConfig().getBoolean("advanced.debug", false);
                    plugin.getConfig().set("advanced.debug", !currentDebug);
                    plugin.saveConfig();

                    player.sendMessage(ChatColor.GREEN + "Debug mode " + (!currentDebug ? "enabled" : "disabled") + "!");
                    player.sendMessage(ChatColor.GRAY + "Check server console for webhook debug information.");
                } else {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                }
                break;
            case "testwebhook":
                if (player.hasPermission("tickwatch.admin")) {
                    if (!webhookManager.isWebhookEnabled()) {
                        player.sendMessage(ChatColor.RED + "Webhook is not enabled!");
                        break;
                    }
                    if (webhookManager.getWebhookUrl() == null || webhookManager.getWebhookUrl().trim().isEmpty()) {
                        player.sendMessage(ChatColor.RED + "Webhook URL is not configured!");
                        break;
                    }

                    player.sendMessage(ChatColor.YELLOW + "Sending test webhook...");
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        webhookManager.sendTestAlert("warning", "Manual Test Alert", "This is a manual test webhook triggered by " + player.getName(), "#0099FF");
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            player.sendMessage(ChatColor.GREEN + "Test webhook sent! Check Discord and console.");
                        });
                    });
                } else {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                }
                break;
            default:
                Utils.playSound(player, "gui_open");
                guiManager.openMainGUI(player);
                break;
        }

        return true;
    }

    private void showHelp(Player player) {
        Utils.playSound(player, "notification");

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
}
