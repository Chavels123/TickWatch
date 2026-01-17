package ticks.tickWatch.manager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import ticks.tickWatch.utils.Utils;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class WebhookManager {

    private final JavaPlugin plugin;
    private String webhookUrl;
    private boolean webhookEnabled;
    private int checkInterval;
    private int alertCooldown;
    private Map<String, Long> lastAlertTimes;
    private PerformanceManager performanceManager;

    public WebhookManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.lastAlertTimes = new HashMap<>();
        loadConfig();
    }

    public void setPerformanceManager(PerformanceManager performanceManager) {
        this.performanceManager = performanceManager;
    }

    public void loadConfig() {
        webhookUrl = plugin.getConfig().getString("webhook.url", "").trim();
        webhookEnabled = plugin.getConfig().getBoolean("webhook.enabled", false);
        checkInterval = Math.max(1, Math.min(3600, plugin.getConfig().getInt("webhook.notifications.check-interval-seconds", 30)));
        alertCooldown = Math.max(1, plugin.getConfig().getInt("webhook.notifications.alert-cooldown", 15));

        if (webhookEnabled) {
            if (webhookUrl.isEmpty()) {
                plugin.getLogger().warning("Webhook is enabled but no URL is configured!");
                webhookEnabled = false;
            } else if (!isValidWebhookUrl(webhookUrl)) {
                plugin.getLogger().warning("Webhook URL is invalid: " + webhookUrl);
                webhookEnabled = false;
            } else {
                plugin.getLogger().info("Webhook alert system enabled - URL: " + webhookUrl.substring(0, Math.min(50, webhookUrl.length())) + "...");
                plugin.getLogger().info("Check interval: " + checkInterval + " seconds, Alert cooldown: " + alertCooldown + " minutes");
            }
        }
    }

    public boolean isWebhookEnabled() {
        return webhookEnabled;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public boolean isValidWebhookUrl(String url) {
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

    public void startWebhookSystem() {
        if (!webhookEnabled) {
            plugin.getLogger().info("Webhook system is disabled");
            return;
        }

        long intervalTicks = checkInterval * 20L;

        plugin.getLogger().info("Starting webhook monitoring system - first check in " + checkInterval + " seconds");

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkAndSendAlerts, intervalTicks, intervalTicks);
    }

    // Exposed for testing
    public void sendTestAlert(String severity, String title, String description, String color) {
         sendAlert(severity, title, description, color);
    }

    private void checkAndSendAlerts() {
        if (!webhookEnabled || webhookUrl == null || webhookUrl.trim().isEmpty()) {
            if (plugin.getConfig().getBoolean("advanced.debug", false)) {
                plugin.getLogger().info("Webhook check skipped - enabled: " + webhookEnabled);
            }
            return;
        }

        if (performanceManager == null) return;

        double tps = performanceManager.getCurrentTPS();
        double memUsage = performanceManager.getMemoryUsagePercentage();
        double cpuUsage = performanceManager.getCpuLoadPercentage();

        double tpsWarning = plugin.getConfig().getDouble("thresholds.tps.warning", 15.0);
        double tpsCritical = plugin.getConfig().getDouble("thresholds.tps.critical", 10.0);
        double memWarning = plugin.getConfig().getDouble("thresholds.memory.warning", 80.0);
        double memCritical = plugin.getConfig().getDouble("thresholds.memory.critical", 90.0);
        double cpuWarning = plugin.getConfig().getDouble("thresholds.cpu.warning", 85.0);
        double cpuCritical = plugin.getConfig().getDouble("thresholds.cpu.critical", 95.0);

        if (plugin.getConfig().getBoolean("advanced.debug", false)) {
            plugin.getLogger().info("Webhook check - TPS: " + String.format("%.2f", tps) +
                    ", Memory: " + String.format("%.1f", memUsage) + "%" +
                    ", CPU: " + String.format("%.1f", cpuUsage) + "%");
        }

        long currentTime = System.currentTimeMillis();
        long cooldownMs = alertCooldown * 60 * 1000L;
        boolean alertSent = false;

        // TPS Alerts
        if (tps <= tpsCritical && canSendAlert("tps-critical", currentTime, cooldownMs)) {
            if (sendAlert("critical", "TPS Critical", "Server TPS has dropped to " + String.format("%.2f", tps) + " (Critical: ≤" + tpsCritical + ")", "#FF0000")) {
                recordAlert("tps-critical", currentTime);
                alertSent = true;
            }
        } else if (tps <= tpsWarning && canSendAlert("tps-warning", currentTime, cooldownMs)) {
            if (sendAlert("warning", "TPS Warning", "Server TPS has dropped to " + String.format("%.2f", tps) + " (Warning: ≤" + tpsWarning + ")", "#FFA500")) {
                recordAlert("tps-warning", currentTime);
                alertSent = true;
            }
        }

        // Memory Alerts
        if (memUsage >= memCritical && canSendAlert("memory-critical", currentTime, cooldownMs)) {
            if (sendAlert("critical", "Memory Critical", "Memory usage has reached " + String.format("%.1f", memUsage) + "% (Critical: ≥" + memCritical + "%)", "#FF0000")) {
                recordAlert("memory-critical", currentTime);
                alertSent = true;
            }
        } else if (memUsage >= memWarning && canSendAlert("memory-warning", currentTime, cooldownMs)) {
            if (sendAlert("warning", "Memory Warning", "Memory usage has reached " + String.format("%.1f", memUsage) + "% (Warning: ≥" + memWarning + "%)", "#FFA500")) {
                recordAlert("memory-warning", currentTime);
                alertSent = true;
            }
        }

        // CPU Alerts
        if (cpuUsage >= cpuCritical && canSendAlert("cpu-critical", currentTime, cooldownMs)) {
            if (sendAlert("critical", "CPU Critical", "CPU usage has reached " + String.format("%.1f", cpuUsage) + "% (Critical: ≥" + cpuCritical + "%)", "#FF0000")) {
                recordAlert("cpu-critical", currentTime);
                alertSent = true;
            }
        } else if (cpuUsage >= cpuWarning && canSendAlert("cpu-warning", currentTime, cooldownMs)) {
            if (sendAlert("warning", "CPU Warning", "CPU usage has reached " + String.format("%.1f", cpuUsage) + "% (Warning: ≥" + cpuWarning + "%)", "#FFA500")) {
                recordAlert("cpu-warning", currentTime);
                alertSent = true;
            }
        }

        if (plugin.getConfig().getBoolean("advanced.debug", false) && !alertSent) {
            plugin.getLogger().info("No alerts triggered during this check cycle");
        }
    }

    private boolean canSendAlert(String alertType, long currentTime, long cooldownMs) {
        boolean alertEnabled = plugin.getConfig().getBoolean("webhook.notifications.alerts." + alertType, true);
        if (!alertEnabled) return false;

        Long lastAlert = lastAlertTimes.get(alertType);
        return lastAlert == null || (currentTime - lastAlert) >= cooldownMs;
    }

    private void recordAlert(String alertType, long currentTime) {
        lastAlertTimes.put(alertType, currentTime);
    }

    public boolean sendAlert(String severity, String title, String description, String color) {
        try {
            String jsonPayload = buildAlertPayload(severity, title, description, color);

            URL url = new URL(webhookUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "TickWatch-Monitor/1.0");
            connection.setDoOutput(true);
            connection.setConnectTimeout(plugin.getConfig().getInt("advanced.timeout", 10) * 1000);
            connection.setReadTimeout(plugin.getConfig().getInt("advanced.timeout", 10) * 1000);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                if (plugin.getConfig().getBoolean("advanced.debug", false)) {
                    plugin.getLogger().info("Webhook alert sent successfully: " + title);
                }
                connection.disconnect();
                return true;
            } else {
                plugin.getLogger().warning("Failed to send webhook alert: " + title + " (Status: " + responseCode + ")");
                connection.disconnect();
                return false;
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to send webhook alert: " + title + " - " + e.getMessage());
            return false;
        }
    }

    private String buildAlertPayload(String severity, String title, String description, String color) {
        StringBuilder json = new StringBuilder();
        json.append("{");

        String username = plugin.getConfig().getString("webhook.messages.username", "TickWatch Monitor");
        String avatarUrl = plugin.getConfig().getString("webhook.messages.avatar-url", "");

        json.append("\"username\":\"").append(username).append("\",");

        if (!avatarUrl.isEmpty()) {
            json.append("\"avatar_url\":\"").append(avatarUrl).append("\",");
        }

        json.append("\"embeds\":[{");

        String embedTitle = plugin.getConfig().getString("webhook.messages.titles." + severity, title);
        String embedColor = plugin.getConfig().getString("webhook.messages.colors." + severity, color);

        json.append("\"title\":\"").append(embedTitle).append("\",");
        json.append("\"description\":\"").append(description).append("\",");
        json.append("\"color\":").append(Integer.parseInt(embedColor.substring(1), 16)).append(",");
        json.append("\"timestamp\":\"").append(java.time.Instant.now().toString()).append("\",");

        json.append("\"fields\":[");
        json.append("{\"name\":\"Server\",\"value\":\"").append(Bukkit.getServer().getName()).append("\",\"inline\":true},");
        json.append("{\"name\":\"Players Online\",\"value\":\"").append(Bukkit.getOnlinePlayers().size()).append("/").append(Bukkit.getMaxPlayers()).append("\",\"inline\":true},");
        long startTime = System.currentTimeMillis(); // Note: This should ideally come from DataManager, but for simplicity we can estimate or pass it in.
        // Actually, let's fix this properly. For now we use 0 or skip uptime in fields if not available, but let's assume we can pass it or get it later.
        // Ideally WebhookManager should have access to DataManager or startTime.
        // I'll leave it as is for now and maybe inject DataManager later if needed for precise start time, or just use performanceManager if I add getter there.
        // For strict refactoring, I should have passed DataManager. Let's fix this later or assume performanceManager handles it.
        // Wait, I can get uptime from ManagementFactory runtimeMXBean as well.
        json.append("{\"name\":\"Uptime\",\"value\":\"").append(Utils.formatDuration(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime())).append("\",\"inline\":true}");
        json.append("],");

        String footer = plugin.getConfig().getString("webhook.messages.footer", "TickWatch Server Monitor");
        json.append("\"footer\":{\"text\":\"").append(footer).append("\"}");

        json.append("}]");
        json.append("}");

        return json.toString();
    }
}
