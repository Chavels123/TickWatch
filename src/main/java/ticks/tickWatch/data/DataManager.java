package ticks.tickWatch.data;

import org.bukkit.plugin.java.JavaPlugin;
import ticks.tickWatch.utils.Utils;

import java.io.File;
import java.nio.file.Files;
import java.util.Date;

public class DataManager {

    private final JavaPlugin plugin;
    private long startTime;
    private long lastReloadTime;
    private long lastShutdownTime;
    private File dataFolder;
    private File dataFile;

    public DataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.startTime = System.currentTimeMillis();
        setupDataFolder();
        loadData();
    }

    private void setupDataFolder() {
        dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        dataFile = new File(dataFolder, "timedata.json");
    }

    public void loadData() {
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
            plugin.getLogger().warning("Failed to load time data: " + e.getMessage());
            lastReloadTime = startTime;
            lastShutdownTime = 0;
        }
    }

    public void saveData() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"startTime\": ").append(startTime).append(",\n");
            json.append("  \"lastReloadTime\": ").append(lastReloadTime).append(",\n");
            json.append("  \"lastShutdownTime\": ").append(lastShutdownTime).append(",\n");
            json.append("  \"lastSaved\": \"").append(Utils.dateFormat.format(new Date())).append("\"\n");
            json.append("}");

            Files.write(dataFile.toPath(), json.toString().getBytes());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save time data: " + e.getMessage());
        }
    }

    public long getStartTime() {
        return startTime;
    }

    public long getLastReloadTime() {
        return lastReloadTime;
    }

    public void setLastReloadTime(long lastReloadTime) {
        this.lastReloadTime = lastReloadTime;
    }

    public long getLastShutdownTime() {
        return lastShutdownTime;
    }

    public void setLastShutdownTime(long lastShutdownTime) {
        this.lastShutdownTime = lastShutdownTime;
    }

    public File getDataFile() {
        return dataFile;
    }
}
