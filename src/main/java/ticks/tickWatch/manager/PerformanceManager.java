package ticks.tickWatch.manager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ticks.tickWatch.utils.Utils;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;

public class PerformanceManager {

    private final JavaPlugin plugin;
    private final MemoryMXBean memoryBean;
    private final OperatingSystemMXBean osBean;
    private final long[] tpsHistory;
    private int tpsIndex;
    private long lastTpsCheck;
    private final Map<String, Long> lastAlertTimes;

    public PerformanceManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.tpsHistory = new long[60];
        this.tpsIndex = 0;
        this.lastTpsCheck = System.currentTimeMillis();
        this.lastAlertTimes = new HashMap<>();

        startTpsTracking();
    }

    private void startTpsTracking() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long currentTime = System.currentTimeMillis();
            tpsHistory[tpsIndex] = currentTime;
            tpsIndex = (tpsIndex + 1) % tpsHistory.length;
            lastTpsCheck = currentTime;
        }, 0L, 1L);
    }

    public double getCurrentTPS() {
        try {
            return getServerTPS()[0];
        } catch (Exception e) {
            return calculateTpsFromHistory();
        }
    }

    public double getAverageTPS(int seconds) {
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

    public double[] getServerTPS() {
        try {
            Object server = Bukkit.getServer();
            try {
                Object handle = server.getClass().getMethod("getServer").invoke(server);
                return (double[]) handle.getClass().getField("recentTps").get(handle);
            } catch (Exception e1) {
                // Fallback implementation or different NMS version handling could go here
                // For simplicity, returning mock or calculated TPS if reflection fails
                return new double[]{calculateTpsFromHistory(), 20.0, 20.0};
            }
        } catch (Exception e) {
            return new double[]{20.0, 20.0, 20.0};
        }
    }

    private double calculateTpsFromHistory() {
        if (tpsHistory[0] == 0) return 20.0;

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

    public double getCpuLoadPercentage() {
        try {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            double processCpuLoad = sunOsBean.getProcessCpuLoad() * 100;
            return processCpuLoad < 0 ? 0 : processCpuLoad;
        } catch (Exception e) {
            try {
                double load = osBean.getSystemLoadAverage();
                if (load < 0) return 0;
                return Math.min(100, (load / Runtime.getRuntime().availableProcessors()) * 100);
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    public double getMemoryUsagePercentage() {
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long usedMem = runtime.totalMemory() - runtime.freeMemory();
        return (double) usedMem / maxMem * 100;
    }

    public void checkAndSendWarnings() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("tickwatch.admin")) {
                checkPlayerWarnings(player);
            }
        }
    }

    private void checkPlayerWarnings(Player player) {
        double tps = getCurrentTPS();
        double memUsage = getMemoryUsagePercentage();
        double cpuUsage = getCpuLoadPercentage();

        double tpsWarning = plugin.getConfig().getDouble("thresholds.tps.warning", 15.0);
        double tpsCritical = plugin.getConfig().getDouble("thresholds.tps.critical", 10.0);
        double memWarning = plugin.getConfig().getDouble("thresholds.memory.warning", 80.0);
        double memCritical = plugin.getConfig().getDouble("thresholds.memory.critical", 90.0);
        double cpuWarning = plugin.getConfig().getDouble("thresholds.cpu.warning", 85.0);
        double cpuCritical = plugin.getConfig().getDouble("thresholds.cpu.critical", 95.0);

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

        Utils.playSound(player, "warning");
    }

    public OperatingSystemMXBean getOsBean() {
        return osBean;
    }
}
