package ticks.tickWatch.utils;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.List;

public class Utils {

    public static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    public static final DecimalFormat decimalFormat = new DecimalFormat("#.##");

    public static String formatDuration(long milliseconds) {
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

    public static double bytesToMB(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    public static ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static Material getMaterialSafe(String materialName) {
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

    public static Sound getSoundSafe(String modernSound, String legacySound) {
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

    public static void playSound(Player player, String soundType) {
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

    // Helper colors for TPS
    public static ChatColor getTpsColor(double tps) {
        if (tps >= 19.5) return ChatColor.DARK_GREEN;
        if (tps >= 18.0) return ChatColor.GREEN;
        if (tps >= 15.0) return ChatColor.YELLOW;
        if (tps >= 12.0) return ChatColor.GOLD;
        if (tps >= 10.0) return ChatColor.RED;
        return ChatColor.DARK_RED;
    }

    public static String getTpsStatus(double tps) {
        if (tps >= 19.5) return "Perfect";
        if (tps >= 18.0) return "Excellent";
        if (tps >= 15.0) return "Good";
        if (tps >= 12.0) return "Fair";
        if (tps >= 10.0) return "Poor";
        return "Critical";
    }
}
