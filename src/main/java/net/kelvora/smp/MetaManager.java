package net.kelvora.smp;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Read-only view of canonical /worth prices. */
final class MetaManager implements Listener, CommandExecutor {
    private static final String TITLE = "KELVORA // META";
    private final JavaPlugin plugin;
    private final WorthManager worth;

    MetaManager(JavaPlugin plugin, WorthManager worth) {
        this.plugin = plugin;
        this.worth = worth;
    }

    void register() {
        if (plugin.getCommand("meta") != null) {
            plugin.getCommand("meta").setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("Use this command in-game.");
            return true;
        }
        Inventory gui = Bukkit.createInventory(null, 27, Component.text(TITLE, NamedTextColor.GREEN));
        for (int slot = 0; slot < gui.getSize(); slot++) {
            if (slot < 9 || slot >= 18) gui.setItem(slot, named(Material.GREEN_STAINED_GLASS_PANE, " ", null));
        }
        gui.setItem(11, priceItem(Material.KELP));
        gui.setItem(13, priceItem(Material.PINK_PETALS));
        gui.setItem(15, priceItem(Material.SEA_PICKLE));
        gui.setItem(22, named(Material.BARRIER, "Close", null));
        player.openInventory(gui);
        return true;
    }

    private ItemStack priceItem(Material material) {
        double value = worth.priceOf(material);
        String formatted = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US)).format(value);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(prettyName(material), NamedTextColor.GREEN));
        meta.lore(java.util.List.of(
            Component.text("Current /worth value", NamedTextColor.GRAY),
            Component.text(formatted + " Money per item", NamedTextColor.GOLD),
            Component.text("Information only — does not give items.", NamedTextColor.DARK_GRAY)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack named(Material material, String title, String unused) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.DARK_GREEN));
        item.setItemMeta(meta);
        return item;
    }

    private String prettyName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private boolean isMeta(InventoryClickEvent event) {
        return ChatColor.stripColor(event.getView().getTitle()).equals(TITLE);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!isMeta(event)) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (ChatColor.stripColor(event.getView().getTitle()).equals(TITLE)) event.setCancelled(true);
    }
}
