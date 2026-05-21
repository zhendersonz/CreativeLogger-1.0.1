package com.creativelogger.utils;

import com.creativelogger.CreativeLogger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ItemUtils {
    private static final NamespacedKey CREATIVE_ORIGIN = new NamespacedKey("creativelogger", "creative_origin");
    private static CreativeLogger plugin;

    public static void init(CreativeLogger p) {
        plugin = p;
    }

    public static boolean hasCreativeTag(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(CREATIVE_ORIGIN, PersistentDataType.STRING);
    }

    public static void tagCreativeItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(CREATIVE_ORIGIN, PersistentDataType.STRING, "true");
        item.setItemMeta(meta);
    }

    public static String getSHA256(ItemStack item) {
        if (item == null) return "null";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = item.getType().toString() + ":" + item.getAmount();
            if (item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                data += ":" + meta.getAsComponentString();
            }
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return "error";
        }
    }

    public static String getActionSuffix(org.bukkit.entity.Player player) {
        if (isVanished(player)) return "_vanish";
        return "";
    }

    public static boolean isVanished(org.bukkit.entity.Player player) {
        if (player.hasMetadata("vanished") && player.getMetadata("vanished").get(0).asBoolean()) {
            return true;
        }
        return player.isInvisible();
    }
}
